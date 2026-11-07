package br.com.erpadv.services;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.dto.BoardDtos.BoardInsights;
import br.com.erpadv.dto.BoardDtos.BoardRequest;
import br.com.erpadv.dto.BoardDtos.BoardSummary;
import br.com.erpadv.dto.BoardDtos.BoardTemplate;
import br.com.erpadv.dto.BoardDtos.BoardView;
import br.com.erpadv.dto.BoardDtos.CardDetail;
import br.com.erpadv.dto.BoardDtos.CardRequest;
import br.com.erpadv.dto.BoardDtos.ChecklistItemRequest;
import br.com.erpadv.dto.BoardDtos.ColumnInsight;
import br.com.erpadv.dto.BoardDtos.ColumnRequest;
import br.com.erpadv.dto.BoardDtos.CommentRequest;
import br.com.erpadv.dto.BoardDtos.LabelRequest;
import br.com.erpadv.dto.BoardDtos.MemberInsight;
import br.com.erpadv.dto.BoardDtos.MoveRequest;
import br.com.erpadv.dto.BoardDtos.MyCard;
import br.com.erpadv.dto.DeadlineDtos.DeadlineRequest;
import br.com.erpadv.dto.DeadlineDtos.DeadlineResponse;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.ConflictException;
import br.com.erpadv.exceptions.ForbiddenException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.models.Board;
import br.com.erpadv.models.Board.BoardColumn;
import br.com.erpadv.models.Board.BoardLabel;
import br.com.erpadv.models.BoardCard;
import br.com.erpadv.models.CardActivity;
import br.com.erpadv.models.Client;
import br.com.erpadv.models.LegalCase;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.repositories.BoardRepositories;
import br.com.erpadv.repositories.UserRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Permission;
import br.com.erpadv.security.Role;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Quadros Kanban do escritório, pensados para a rotina jurídica: limites WIP, automações por coluna,
 * cartões vinculados a clientes/processos, conversão de cartão em prazo processual, checklist,
 * comentários com menções, tempo em coluna e indicadores de fluxo (vazão, ciclo, gargalos).
 */
@Service
public class BoardService {

    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final int STALE_DAYS = 7;
    private static final double GAP = 1024d;

    private static final List<BoardTemplate> TEMPLATES = List.of(
            new BoardTemplate("contencioso", "Contencioso",
                    "Fluxo de peças processuais: da triagem ao protocolo e acompanhamento.",
                    List.of("Triagem", "Em elaboração", "Revisão do advogado", "Protocolar", "Aguardando decisão", "Concluído")),
            new BoardTemplate("onboarding", "Entrada de cliente",
                    "Do primeiro atendimento à procuração e contrato assinados.",
                    List.of("Primeiro contato", "Documentos pendentes", "Contrato e procuração", "Cadastro concluído")),
            new BoardTemplate("consultivo", "Consultivo",
                    "Pareceres e consultas com prazo interno de entrega.",
                    List.of("Recebido", "Pesquisa", "Redação", "Revisão", "Entregue")),
            new BoardTemplate("audiencias", "Preparação de audiências",
                    "Checklist de preparação: testemunhas, provas e roteiro.",
                    List.of("Agendada", "Preparando", "Pronta", "Realizada")),
            new BoardTemplate("simples", "Kanban simples", "A fazer, fazendo e feito.",
                    List.of("A fazer", "Fazendo", "Feito")));

    private static final Map<String, String> COLUMN_COLORS = Map.of(
            "Concluído", "#2e7d32", "Feito", "#2e7d32", "Entregue", "#2e7d32", "Realizada", "#2e7d32",
            "Cadastro concluído", "#2e7d32", "Revisão do advogado", "#b8860b", "Revisão", "#b8860b");

    private final BoardRepositories.Boards boards;
    private final BoardRepositories.Cards cards;
    private final BoardRepositories.Activities activities;
    private final UserRepository users;
    private final ClientService clientService;
    private final CaseService caseService;
    private final DeadlineService deadlineService;
    private final NotificationService notifications;
    private final AuditService audit;

    public BoardService(BoardRepositories.Boards boards, BoardRepositories.Cards cards,
                        BoardRepositories.Activities activities, UserRepository users, ClientService clientService,
                        CaseService caseService, DeadlineService deadlineService, NotificationService notifications,
                        AuditService audit) {
        this.boards = boards;
        this.cards = cards;
        this.activities = activities;
        this.users = users;
        this.clientService = clientService;
        this.caseService = caseService;
        this.deadlineService = deadlineService;
        this.notifications = notifications;
        this.audit = audit;
    }

    // ================================================================== quadros

    public List<BoardTemplate> templates() {
        return TEMPLATES;
    }

    public List<BoardSummary> list(AuthenticatedUser user, boolean archived) {
        String officeId = user.requireOffice();
        return boards.byArchived(officeId, archived).stream()
                .filter(b -> canView(user, b))
                .sorted(Comparator.comparing((Board b) -> b.getUpdatedAt() == null ? Instant.EPOCH : b.getUpdatedAt()).reversed())
                .map(b -> new BoardSummary(b.getId(), b.getName(), b.getDescription(), b.getColor(), b.getVisibility(),
                        b.getOwnerUid(), b.getOwnerName(), b.getMemberUids(), b.getColumns().size(),
                        cards.countOpen(officeId, b.getId()), b.isArchived(), canManage(user, b), b.getUpdatedAt()))
                .toList();
    }

    public BoardView view(AuthenticatedUser user, String boardId) {
        Board board = loadBoard(user, boardId);
        List<BoardCard> list = new ArrayList<>(cards.byBoard(user.officeId(), boardId, false));
        list.sort(Comparator.comparingDouble(BoardCard::getPosition));
        return new BoardView(board, list, canManage(user, board));
    }

    public List<BoardCard> archivedCards(AuthenticatedUser user, String boardId) {
        loadBoard(user, boardId);
        return cards.byBoard(user.officeId(), boardId, true).stream()
                .sorted(Comparator.comparing((BoardCard c) -> c.getUpdatedAt() == null ? Instant.EPOCH : c.getUpdatedAt()).reversed())
                .toList();
    }

    public BoardView create(AuthenticatedUser user, BoardRequest req) {
        String officeId = user.requireOffice();
        Instant now = Instant.now();
        Board board = new Board();
        applyBoard(user, board, req);
        board.setOwnerUid(user.uid());
        board.setOwnerName(user.displayName());
        board.setTemplateKey(req.templateKey());
        BoardTemplate template = TEMPLATES.stream().filter(t -> t.key().equals(req.templateKey())).findFirst()
                .orElse(TEMPLATES.get(TEMPLATES.size() - 1));
        List<BoardColumn> columns = new ArrayList<>();
        for (int i = 0; i < template.columns().size(); i++) {
            String name = template.columns().get(i);
            BoardColumn col = new BoardColumn();
            col.setId(shortId());
            col.setName(name);
            col.setColor(COLUMN_COLORS.getOrDefault(name, null));
            col.setDone(i == template.columns().size() - 1);
            columns.add(col);
        }
        board.setColumns(columns);
        board.setLabels(defaultLabels());
        board.setCreatedAt(now);
        board.setUpdatedAt(now);
        board.setCreatedBy(user.uid());
        boards.save(officeId, board);
        audit.record(user, "BOARD_CREATED", "board", board.getId(), "Quadro criado: " + board.getName());
        return new BoardView(board, List.of(), true);
    }

    public BoardView update(AuthenticatedUser user, String boardId, BoardRequest req) {
        Board board = loadManaged(user, boardId);
        applyBoard(user, board, req);
        touch(user, board);
        boards.save(user.officeId(), board);
        audit.record(user, "BOARD_UPDATED", "board", boardId, "Quadro atualizado: " + board.getName());
        return view(user, boardId);
    }

    public BoardView updateColumns(AuthenticatedUser user, String boardId, List<ColumnRequest> requests) {
        Board board = loadManaged(user, boardId);
        Set<String> labelIds = board.getLabels().stream().map(BoardLabel::getId).collect(Collectors.toSet());
        Map<String, BoardColumn> existing = board.getColumns().stream()
                .collect(Collectors.toMap(BoardColumn::getId, c -> c));
        List<BoardColumn> columns = new ArrayList<>();
        Set<String> kept = new HashSet<>();
        for (ColumnRequest r : requests) {
            BoardColumn col = r.id() != null && existing.containsKey(r.id()) ? existing.get(r.id()) : new BoardColumn();
            if (col.getId() == null) {
                col.setId(shortId());
            }
            col.setName(r.name().trim());
            col.setColor(blankToNull(r.color()));
            col.setWipLimit(r.wipLimit());
            col.setDone(r.done());
            Board.ColumnAutomation auto = new Board.ColumnAutomation();
            auto.setNotifyAssignees(r.notifyAssignees());
            auto.setAddLabelId(r.addLabelId() != null && labelIds.contains(r.addLabelId()) ? r.addLabelId() : null);
            auto.setAssignUid(validMember(user, r.assignUid()));
            auto.setSetDueInDays(r.setDueInDays() == null || r.setDueInDays() == 0 ? null : r.setDueInDays());
            col.setAutomation(auto);
            columns.add(col);
            kept.add(col.getId());
        }
        List<BoardCard> boardCards = cards.byBoard(user.officeId(), boardId, false);
        for (BoardColumn removed : board.getColumns()) {
            if (!kept.contains(removed.getId())
                    && boardCards.stream().anyMatch(c -> removed.getId().equals(c.getColumnId()))) {
                throw new ConflictException("COLUMN_NOT_EMPTY",
                        "A coluna \"" + removed.getName() + "\" possui cartões. Mova-os antes de removê-la.");
            }
        }
        board.setColumns(columns);
        touch(user, board);
        boards.save(user.officeId(), board);
        audit.record(user, "BOARD_COLUMNS_UPDATED", "board", boardId, "Colunas atualizadas: " + board.getName(),
                Map.of("columns", columns.stream().map(BoardColumn::getName).toList().toString()));
        return view(user, boardId);
    }

    public BoardView updateLabels(AuthenticatedUser user, String boardId, List<LabelRequest> requests) {
        Board board = loadManaged(user, boardId);
        List<BoardLabel> labels = new ArrayList<>();
        for (LabelRequest r : requests) {
            BoardLabel label = new BoardLabel();
            label.setId(r.id() == null || r.id().isBlank() ? shortId() : r.id());
            label.setName(r.name().trim());
            label.setColor(r.color());
            labels.add(label);
        }
        Set<String> ids = labels.stream().map(BoardLabel::getId).collect(Collectors.toSet());
        board.setLabels(labels);
        board.getColumns().forEach(c -> {
            if (c.getAutomation() != null && c.getAutomation().getAddLabelId() != null
                    && !ids.contains(c.getAutomation().getAddLabelId())) {
                c.getAutomation().setAddLabelId(null);
            }
        });
        touch(user, board);
        boards.save(user.officeId(), board);
        return view(user, boardId);
    }

    public BoardView setArchived(AuthenticatedUser user, String boardId, boolean archived) {
        Board board = loadManaged(user, boardId);
        board.setArchived(archived);
        touch(user, board);
        boards.save(user.officeId(), board);
        audit.record(user, archived ? "BOARD_ARCHIVED" : "BOARD_RESTORED", "board", boardId,
                (archived ? "Quadro arquivado: " : "Quadro restaurado: ") + board.getName());
        return new BoardView(board, List.of(), true);
    }

    /** Exclusão definitiva (somente quadros arquivados): remove também cartões e atividades. */
    public void delete(AuthenticatedUser user, String boardId) {
        Board board = loadManaged(user, boardId);
        if (!board.isArchived()) {
            throw new BusinessException("BOARD_NOT_ARCHIVED", "Arquive o quadro antes de excluí-lo definitivamente.");
        }
        String officeId = user.officeId();
        for (BoardCard card : cards.allOfBoard(officeId, boardId)) {
            cards.delete(officeId, card.getId());
        }
        // Atividades em lotes de até 500 por consulta.
        List<CardActivity> pending = activities.byBoard(officeId, boardId);
        while (!pending.isEmpty()) {
            pending.forEach(a -> activities.delete(officeId, a.getId()));
            pending = activities.byBoard(officeId, boardId);
        }
        boards.delete(officeId, boardId);
        audit.record(user, "BOARD_DELETED", "board", boardId, "Quadro excluído: " + board.getName());
    }

    // ================================================================== cartões

    public CardDetail card(AuthenticatedUser user, String cardId) {
        BoardCard card = loadCard(user, cardId);
        Board board = loadBoard(user, card.getBoardId());
        return new CardDetail(card, activities.byCard(user.officeId(), cardId), board.getName());
    }

    public BoardCard createCard(AuthenticatedUser user, String boardId, CardRequest req) {
        Board board = loadBoard(user, boardId);
        if (board.isArchived()) {
            throw new BusinessException("BOARD_ARCHIVED", "Quadro arquivado.");
        }
        BoardColumn column = req.columnId() == null || req.columnId().isBlank()
                ? board.getColumns().getFirst() : column(board, req.columnId());
        List<BoardCard> existing = cards.byBoard(user.officeId(), boardId, false);
        assertWip(column, existing, null);
        Instant now = Instant.now();
        BoardCard card = new BoardCard();
        card.setBoardId(boardId);
        card.setColumnId(column.getId());
        card.setPosition(existing.stream().filter(c -> column.getId().equals(c.getColumnId()))
                .mapToDouble(BoardCard::getPosition).max().orElse(0) + GAP);
        applyCard(user, board, card, req);
        card.setColumnEnteredAt(now);
        card.setCreatedAt(now);
        card.setUpdatedAt(now);
        card.setLastActivityAt(now);
        card.setCreatedBy(user.uid());
        List<String> automationNotes = runAutomations(user, board, column, card);
        cards.save(user.officeId(), card);
        event(user, card, "criou o cartão em \"" + column.getName() + "\"");
        automationNotes.forEach(n -> event(user, card, n));
        notifyAssignees(user, card, new HashSet<>(card.getAssigneeUids()), "Novo cartão atribuído a você",
                board.getName() + ": " + card.getTitle());
        touchBoard(user, board);
        return card;
    }

    public BoardCard updateCard(AuthenticatedUser user, String cardId, CardRequest req) {
        BoardCard card = loadCard(user, cardId);
        Board board = loadBoard(user, card.getBoardId());
        Set<String> previousAssignees = new HashSet<>(card.getAssigneeUids());
        LocalDate previousDue = card.getDueDate();
        applyCard(user, board, card, req);
        card.setUpdatedAt(Instant.now());
        card.setUpdatedBy(user.uid());
        card.setLastActivityAt(Instant.now());
        cards.save(user.officeId(), card);
        Set<String> added = new HashSet<>(card.getAssigneeUids());
        added.removeAll(previousAssignees);
        if (!added.isEmpty()) {
            event(user, card, "atribuiu " + names(user.officeId(), added));
            notifyAssignees(user, card, added, "Cartão atribuído a você", board.getName() + ": " + card.getTitle());
        }
        if (!Objects.equals(previousDue, card.getDueDate())) {
            event(user, card, card.getDueDate() == null ? "removeu o vencimento"
                    : "definiu o vencimento para " + card.getDueDate().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        }
        touchBoard(user, board);
        return card;
    }

    public BoardCard move(AuthenticatedUser user, String cardId, MoveRequest req) {
        BoardCard card = loadCard(user, cardId);
        Board board = loadBoard(user, card.getBoardId());
        BoardColumn target = column(board, req.columnId());
        boolean columnChanged = !target.getId().equals(card.getColumnId());
        List<BoardCard> boardCards = cards.byBoard(user.officeId(), board.getId(), false);
        if (columnChanged) {
            assertWip(target, boardCards, card.getId());
        }
        double position = req.position() != null ? req.position()
                : boardCards.stream().filter(c -> target.getId().equals(c.getColumnId()) && !c.getId().equals(cardId))
                .mapToDouble(BoardCard::getPosition).max().orElse(0) + GAP;
        card.setPosition(position);
        Instant now = Instant.now();
        List<String> automationNotes = List.of();
        if (columnChanged) {
            String from = board.getColumns().stream().filter(c -> c.getId().equals(card.getColumnId()))
                    .map(BoardColumn::getName).findFirst().orElse("?");
            card.setColumnId(target.getId());
            card.setColumnEnteredAt(now);
            automationNotes = runAutomations(user, board, target, card);
            event(user, card, "moveu de \"" + from + "\" para \"" + target.getName() + "\"");
        }
        card.setUpdatedAt(now);
        card.setUpdatedBy(user.uid());
        card.setLastActivityAt(now);
        cards.save(user.officeId(), card);
        automationNotes.forEach(n -> event(user, card, n));
        if (columnChanged && target.getAutomation() != null && target.getAutomation().isNotifyAssignees()) {
            notifyAssignees(user, card, new HashSet<>(card.getAssigneeUids()),
                    "Cartão movido para " + target.getName(), board.getName() + ": " + card.getTitle());
        }
        touchBoard(user, board);
        return card;
    }

    public BoardCard toggleChecklist(AuthenticatedUser user, String cardId, String itemId) {
        BoardCard card = loadCard(user, cardId);
        BoardCard.ChecklistItem item = card.getChecklist().stream().filter(i -> i.getId().equals(itemId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Item do checklist"));
        item.setDone(!item.isDone());
        card.setUpdatedAt(Instant.now());
        card.setUpdatedBy(user.uid());
        card.setLastActivityAt(Instant.now());
        cards.save(user.officeId(), card);
        long done = card.getChecklist().stream().filter(BoardCard.ChecklistItem::isDone).count();
        if (done == card.getChecklist().size() && item.isDone()) {
            event(user, card, "concluiu todo o checklist (" + done + " itens)");
        }
        return card;
    }

    public BoardCard setCardArchived(AuthenticatedUser user, String cardId, boolean archived) {
        BoardCard card = loadCard(user, cardId);
        Board board = loadBoard(user, card.getBoardId());
        if (!archived) {
            BoardColumn col = board.getColumns().stream().filter(c -> c.getId().equals(card.getColumnId())).findFirst()
                    .orElse(board.getColumns().getFirst());
            card.setColumnId(col.getId());
        }
        card.setArchived(archived);
        card.setUpdatedAt(Instant.now());
        card.setUpdatedBy(user.uid());
        cards.save(user.officeId(), card);
        event(user, card, archived ? "arquivou o cartão" : "restaurou o cartão");
        touchBoard(user, board);
        return card;
    }

    public CardActivity comment(AuthenticatedUser user, String cardId, CommentRequest req) {
        BoardCard card = loadCard(user, cardId);
        Board board = loadBoard(user, card.getBoardId());
        Set<String> mentions = new LinkedHashSet<>();
        if (req.mentionUids() != null) {
            for (String uid : req.mentionUids()) {
                if (validMember(user, uid) != null) {
                    mentions.add(uid);
                }
            }
        }
        CardActivity activity = new CardActivity();
        activity.setCardId(cardId);
        activity.setBoardId(card.getBoardId());
        activity.setKind("COMMENT");
        activity.setText(req.text().trim());
        activity.setActorUid(user.uid());
        activity.setActorName(user.displayName());
        activity.setAt(Instant.now());
        activity.setMentionUids(new ArrayList<>(mentions));
        activity.setCreatedAt(activity.getAt());
        activity.setCreatedBy(user.uid());
        activities.save(user.officeId(), activity);
        card.setCommentCount(card.getCommentCount() + 1);
        card.setLastActivityAt(activity.getAt());
        cards.save(user.officeId(), card);
        String link = "/quadros/" + card.getBoardId() + "?card=" + cardId;
        for (String uid : mentions) {
            if (!uid.equals(user.uid())) {
                notifications.notify(user.officeId(), uid, "BOARD_MENTION", user.displayName() + " mencionou você",
                        card.getTitle() + ": " + abbreviate(activity.getText(), 140), link);
            }
        }
        Set<String> others = new HashSet<>(card.getAssigneeUids());
        others.removeAll(mentions);
        others.remove(user.uid());
        for (String uid : others) {
            notifications.notify(user.officeId(), uid, "BOARD_COMMENT", "Novo comentário em " + board.getName(),
                    card.getTitle() + ": " + abbreviate(activity.getText(), 140), link);
        }
        return activity;
    }

    /** Converte o cartão em prazo processual (com contagem do calendário forense) e vincula os dois. */
    public CardDetail convertToDeadline(AuthenticatedUser user, String cardId, DeadlineRequest req) {
        if (!user.has(Permission.DEADLINES_WRITE)) {
            throw new ForbiddenException("ACCESS_DENIED", "Sem permissão para cadastrar prazos.");
        }
        BoardCard card = loadCard(user, cardId);
        if (card.getDeadlineId() != null) {
            throw new ConflictException("ALREADY_CONVERTED", "Este cartão já gerou um prazo.");
        }
        DeadlineResponse deadline = deadlineService.create(user, req);
        card.setDeadlineId(deadline.id());
        if (card.getDueDate() == null && deadline.dueDate() != null) {
            card.setDueDate(deadline.dueDate());
        }
        card.setUpdatedAt(Instant.now());
        card.setLastActivityAt(Instant.now());
        cards.save(user.officeId(), card);
        event(user, card, "gerou o prazo processual \"" + deadline.description() + "\"");
        return card(user, cardId);
    }

    public List<MyCard> myCards(AuthenticatedUser user) {
        String officeId = user.requireOffice();
        Map<String, Board> boardCache = new HashMap<>();
        List<MyCard> result = new ArrayList<>();
        for (BoardCard card : cards.assignedTo(officeId, user.uid())) {
            Board board = boardCache.computeIfAbsent(card.getBoardId(), id -> boards.findById(officeId, id).orElse(null));
            if (board == null || board.isArchived() || !canView(user, board)) {
                continue;
            }
            String columnName = board.getColumns().stream().filter(c -> c.getId().equals(card.getColumnId()))
                    .map(BoardColumn::getName).findFirst().orElse("");
            result.add(new MyCard(card, board.getName(), columnName, board.getColor()));
        }
        result.sort(Comparator.comparing((MyCard m) -> m.card().getDueDate() == null ? LocalDate.MAX : m.card().getDueDate())
                .thenComparing(m -> priorityRank(m.card().getPriority())));
        return result;
    }

    // ================================================================== indicadores

    public BoardInsights insights(AuthenticatedUser user, String boardId) {
        Board board = loadBoard(user, boardId);
        List<BoardCard> list = cards.byBoard(user.officeId(), boardId, false);
        return computeInsights(board, list, LocalDate.now(ZONE), Instant.now(), names(user.officeId(), list));
    }

    static BoardInsights computeInsights(Board board, List<BoardCard> list, LocalDate today, Instant now,
                                         Map<String, String> memberNames) {
        Set<String> doneColumns = board.getColumns().stream().filter(BoardColumn::isDone).map(BoardColumn::getId)
                .collect(Collectors.toSet());
        List<BoardCard> open = list.stream().filter(c -> !c.isCompleted()).toList();
        long overdue = open.stream().filter(c -> c.getDueDate() != null && c.getDueDate().isBefore(today)).count();
        long dueWeek = open.stream().filter(c -> c.getDueDate() != null && !c.getDueDate().isBefore(today)
                && !c.getDueDate().isAfter(today.plusDays(7))).count();
        long unassigned = open.stream().filter(c -> c.getAssigneeUids().isEmpty()).count();
        long stale = open.stream().filter(c -> !doneColumns.contains(c.getColumnId()) && c.getColumnEnteredAt() != null
                && Duration.between(c.getColumnEnteredAt(), now).toDays() >= STALE_DAYS).count();
        List<BoardCard> completed = list.stream().filter(c -> c.isCompleted() && c.getCompletedAt() != null).toList();
        long completed7d = completed.stream().filter(c -> c.getCompletedAt().isAfter(now.minus(7, ChronoUnit.DAYS))).count();
        double avgCycle = completed.stream().filter(c -> c.getCreatedAt() != null
                        && c.getCompletedAt().isAfter(now.minus(30, ChronoUnit.DAYS)))
                .mapToDouble(c -> Duration.between(c.getCreatedAt(), c.getCompletedAt()).toHours() / 24d)
                .average().orElse(0);

        List<ColumnInsight> columns = new ArrayList<>();
        List<String> alerts = new ArrayList<>();
        for (BoardColumn col : board.getColumns()) {
            List<BoardCard> inCol = list.stream().filter(c -> col.getId().equals(c.getColumnId())).toList();
            double age = inCol.stream().filter(c -> c.getColumnEnteredAt() != null)
                    .mapToDouble(c -> Duration.between(c.getColumnEnteredAt(), now).toHours() / 24d).average().orElse(0);
            boolean over = col.getWipLimit() > 0 && inCol.size() > col.getWipLimit();
            boolean atLimit = col.getWipLimit() > 0 && inCol.size() >= col.getWipLimit();
            columns.add(new ColumnInsight(col.getId(), col.getName(), inCol.size(), col.getWipLimit(), round1(age), over));
            if (atLimit && !col.isDone()) {
                alerts.add("Coluna \"" + col.getName() + "\" no limite WIP (" + inCol.size() + "/" + col.getWipLimit() + ").");
            }
            if (!col.isDone() && age >= STALE_DAYS && inCol.size() >= 2) {
                alerts.add("Gargalo em \"" + col.getName() + "\": cartões parados há " + Math.round(age) + " dias em média.");
            }
        }
        if (overdue > 0) {
            alerts.add(overdue + " cartão(ões) com vencimento ultrapassado.");
        }
        if (unassigned > 0) {
            alerts.add(unassigned + " cartão(ões) sem responsável.");
        }

        Map<String, long[]> perMember = new HashMap<>();
        for (BoardCard c : list) {
            for (String uid : c.getAssigneeUids()) {
                long[] v = perMember.computeIfAbsent(uid, k -> new long[3]);
                if (!c.isCompleted()) {
                    v[0]++;
                    if (c.getDueDate() != null && c.getDueDate().isBefore(today)) {
                        v[1]++;
                    }
                } else if (c.getCompletedAt() != null && c.getCompletedAt().isAfter(now.minus(30, ChronoUnit.DAYS))) {
                    v[2]++;
                }
            }
        }
        List<MemberInsight> members = perMember.entrySet().stream()
                .map(e -> new MemberInsight(e.getKey(), memberNames.getOrDefault(e.getKey(), "Integrante"),
                        e.getValue()[0], e.getValue()[1], e.getValue()[2]))
                .sorted(Comparator.comparingLong(MemberInsight::open).reversed())
                .toList();
        long maxLoad = members.stream().mapToLong(MemberInsight::open).max().orElse(0);
        double avgLoad = members.stream().mapToLong(MemberInsight::open).average().orElse(0);
        if (members.size() >= 2 && maxLoad >= 5 && maxLoad > avgLoad * 2) {
            members.stream().filter(m -> m.open() == maxLoad).findFirst().ifPresent(m ->
                    alerts.add("Carga concentrada: " + m.name() + " tem " + m.open() + " cartões abertos."));
        }

        List<Long> weeks = new ArrayList<>();
        for (int w = 5; w >= 0; w--) {
            Instant start = now.minus((w + 1) * 7L, ChronoUnit.DAYS);
            Instant end = now.minus(w * 7L, ChronoUnit.DAYS);
            weeks.add(completed.stream().filter(c -> c.getCompletedAt().isAfter(start) && !c.getCompletedAt().isAfter(end)).count());
        }
        return new BoardInsights(open.size(), completed.size(), overdue, dueWeek, unassigned, stale, completed7d,
                round1(avgCycle), columns, members, weeks, alerts);
    }

    // ================================================================== regras internas

    /** Executa as automações da coluna de destino. Retorna as descrições para o histórico. */
    static List<String> runAutomations(AuthenticatedUser user, Board board, BoardColumn column, BoardCard card) {
        List<String> notes = new ArrayList<>();
        Instant now = Instant.now();
        if (column.isDone() && !card.isCompleted()) {
            card.setCompleted(true);
            card.setCompletedAt(now);
            notes.add("automação: cartão concluído ao entrar em \"" + column.getName() + "\"");
        } else if (!column.isDone() && card.isCompleted()) {
            card.setCompleted(false);
            card.setCompletedAt(null);
            notes.add("cartão reaberto");
        }
        Board.ColumnAutomation auto = column.getAutomation();
        if (auto == null) {
            return notes;
        }
        if (auto.getAddLabelId() != null && !card.getLabelIds().contains(auto.getAddLabelId())
                && board.getLabels().stream().anyMatch(l -> l.getId().equals(auto.getAddLabelId()))) {
            List<String> labels = new ArrayList<>(card.getLabelIds());
            labels.add(auto.getAddLabelId());
            card.setLabelIds(labels);
            String name = board.getLabels().stream().filter(l -> l.getId().equals(auto.getAddLabelId()))
                    .map(BoardLabel::getName).findFirst().orElse("");
            notes.add("automação: etiqueta \"" + name + "\" adicionada");
        }
        if (auto.getAssignUid() != null && !card.getAssigneeUids().contains(auto.getAssignUid())) {
            List<String> assignees = new ArrayList<>(card.getAssigneeUids());
            assignees.add(auto.getAssignUid());
            card.setAssigneeUids(assignees);
            notes.add("automação: responsável adicionado");
        }
        if (auto.getSetDueInDays() != null && card.getDueDate() == null) {
            card.setDueDate(LocalDate.now(ZONE).plusDays(auto.getSetDueInDays()));
            notes.add("automação: vencimento definido para daqui a " + auto.getSetDueInDays() + " dia(s)");
        }
        return notes;
    }

    static void assertWip(BoardColumn column, List<BoardCard> boardCards, String movingCardId) {
        if (column.getWipLimit() <= 0) {
            return;
        }
        long inColumn = boardCards.stream()
                .filter(c -> column.getId().equals(c.getColumnId()) && !c.getId().equals(movingCardId)).count();
        if (inColumn >= column.getWipLimit()) {
            throw new ConflictException("WIP_LIMIT", "A coluna \"" + column.getName() + "\" atingiu o limite de "
                    + column.getWipLimit() + " cartões. Conclua ou mova algum antes.");
        }
    }

    private void applyBoard(AuthenticatedUser user, Board board, BoardRequest req) {
        board.setName(req.name().trim());
        board.setDescription(blankToNull(req.description()));
        board.setColor(blankToNull(req.color()));
        board.setVisibility(req.visibility());
        Set<String> members = new LinkedHashSet<>();
        if (req.memberUids() != null) {
            for (String uid : req.memberUids()) {
                if (validMember(user, uid) != null) {
                    members.add(uid);
                }
            }
        }
        board.setMemberUids(new ArrayList<>(members));
    }

    private void applyCard(AuthenticatedUser user, Board board, BoardCard card, CardRequest req) {
        if (req.startDate() != null && req.dueDate() != null && req.dueDate().isBefore(req.startDate())) {
            throw new BusinessException("INVALID_DATES", "O vencimento não pode ser anterior ao início.");
        }
        card.setTitle(req.title().trim());
        card.setDescription(blankToNull(req.description()));
        card.setPriority(req.priority());
        card.setStartDate(req.startDate());
        card.setDueDate(req.dueDate());
        card.setEstimateHours(req.estimateHours());
        Set<String> labelIds = board.getLabels().stream().map(BoardLabel::getId).collect(Collectors.toSet());
        card.setLabelIds(req.labelIds() == null ? new ArrayList<>()
                : req.labelIds().stream().filter(labelIds::contains).distinct().toList());
        Set<String> assignees = new LinkedHashSet<>();
        if (req.assigneeUids() != null) {
            for (String uid : req.assigneeUids()) {
                if (validMember(user, uid) != null) {
                    assignees.add(uid);
                }
            }
        }
        card.setAssigneeUids(new ArrayList<>(assignees));
        List<BoardCard.ChecklistItem> checklist = new ArrayList<>();
        if (req.checklist() != null) {
            for (ChecklistItemRequest r : req.checklist()) {
                BoardCard.ChecklistItem item = new BoardCard.ChecklistItem();
                item.setId(r.id() == null || r.id().isBlank() ? shortId() : r.id());
                item.setText(r.text().trim());
                item.setDone(r.done());
                checklist.add(item);
            }
        }
        card.setChecklist(checklist);
        if (req.clientId() == null || req.clientId().isBlank()) {
            card.setClientId(null);
            card.setClientName(null);
        } else if (!req.clientId().equals(card.getClientId())) {
            Client client = clientService.load(user, req.clientId());
            card.setClientId(client.getId());
            card.setClientName(client.getName());
        }
        if (req.caseId() == null || req.caseId().isBlank()) {
            card.setCaseId(null);
            card.setCaseNumber(null);
        } else if (!req.caseId().equals(card.getCaseId())) {
            LegalCase legalCase = caseService.load(user, req.caseId());
            card.setCaseId(legalCase.getId());
            card.setCaseNumber(legalCase.getCnjNumber());
            if (card.getClientId() == null && legalCase.getClientId() != null) {
                card.setClientId(legalCase.getClientId());
                card.setClientName(legalCase.getClientName());
            }
        }
    }

    private void event(AuthenticatedUser user, BoardCard card, String text) {
        CardActivity activity = new CardActivity();
        activity.setCardId(card.getId());
        activity.setBoardId(card.getBoardId());
        activity.setKind("EVENT");
        activity.setText(text);
        activity.setActorUid(user.uid());
        activity.setActorName(user.displayName());
        activity.setAt(Instant.now());
        activity.setCreatedAt(activity.getAt());
        activity.setCreatedBy(user.uid());
        activities.save(user.officeId(), activity);
    }

    private void notifyAssignees(AuthenticatedUser user, BoardCard card, Set<String> uids, String title, String body) {
        for (String uid : uids) {
            if (!uid.equals(user.uid())) {
                notifications.notify(user.officeId(), uid, "BOARD_CARD", title, body,
                        "/quadros/" + card.getBoardId() + "?card=" + card.getId());
            }
        }
    }

    private String validMember(AuthenticatedUser user, String uid) {
        if (uid == null || uid.isBlank()) {
            return null;
        }
        return users.findInOffice(user.officeId(), uid)
                .filter(u -> u.getRole() != null && u.getRole().isStaff())
                .map(UserProfile::getId)
                .orElseThrow(() -> new BusinessException("INVALID_MEMBER", "Integrante não encontrado no escritório."));
    }

    private Map<String, String> names(String officeId, List<BoardCard> list) {
        Set<String> uids = new HashSet<>();
        list.forEach(c -> uids.addAll(c.getAssigneeUids()));
        Map<String, String> result = new HashMap<>();
        for (String uid : uids) {
            users.findInOffice(officeId, uid).ifPresent(u -> result.put(uid, u.getDisplayName()));
        }
        return result;
    }

    private String names(String officeId, Set<String> uids) {
        return uids.stream().map(uid -> users.findInOffice(officeId, uid).map(UserProfile::getDisplayName).orElse("integrante"))
                .collect(Collectors.joining(", "));
    }

    private Board loadBoard(AuthenticatedUser user, String boardId) {
        Board board = boards.findById(user.requireOffice(), boardId).orElseThrow(() -> new NotFoundException("Quadro"));
        if (!canView(user, board)) {
            throw new NotFoundException("Quadro");
        }
        return board;
    }

    private Board loadManaged(AuthenticatedUser user, String boardId) {
        Board board = loadBoard(user, boardId);
        if (!canManage(user, board)) {
            throw new ForbiddenException("BOARD_MANAGE_DENIED", "Somente o dono do quadro ou o sócio administrador altera a estrutura.");
        }
        return board;
    }

    private BoardCard loadCard(AuthenticatedUser user, String cardId) {
        BoardCard card = cards.findById(user.requireOffice(), cardId).orElseThrow(() -> new NotFoundException("Cartão"));
        loadBoard(user, card.getBoardId());
        return card;
    }

    static boolean canView(AuthenticatedUser user, Board board) {
        return "OFFICE".equals(board.getVisibility()) || user.uid().equals(board.getOwnerUid())
                || board.getMemberUids().contains(user.uid()) || user.role() == Role.SOCIO_ADMIN;
    }

    static boolean canManage(AuthenticatedUser user, Board board) {
        return user.uid().equals(board.getOwnerUid()) || user.role() == Role.SOCIO_ADMIN;
    }

    private static BoardColumn column(Board board, String columnId) {
        return board.getColumns().stream().filter(c -> c.getId().equals(columnId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Coluna"));
    }

    private void touch(AuthenticatedUser user, Board board) {
        board.setUpdatedAt(Instant.now());
        board.setUpdatedBy(user.uid());
    }

    private void touchBoard(AuthenticatedUser user, Board board) {
        touch(user, board);
        boards.save(user.officeId(), board);
    }

    private static List<BoardLabel> defaultLabels() {
        String[][] defaults = {{"Urgente", "#c62828"}, {"Aguardando cliente", "#ef6c00"},
                {"Bloqueado", "#6a1b9a"}, {"Revisar", "#1565c0"}, {"Audiência", "#00838f"}};
        List<BoardLabel> labels = new ArrayList<>();
        for (String[] d : defaults) {
            BoardLabel l = new BoardLabel();
            l.setId(shortId());
            l.setName(d[0]);
            l.setColor(d[1]);
            labels.add(l);
        }
        return labels;
    }

    static int priorityRank(String priority) {
        return switch (priority == null ? "" : priority) {
            case "URGENTE" -> 0;
            case "ALTA" -> 1;
            case "MEDIA" -> 2;
            default -> 3;
        };
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10d;
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
