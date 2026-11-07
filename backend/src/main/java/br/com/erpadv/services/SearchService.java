package br.com.erpadv.services;

import br.com.erpadv.dto.CaseDtos.CaseFilter;
import br.com.erpadv.dto.CaseDtos.CaseResponse;
import br.com.erpadv.dto.ClientDtos.ClientSummary;
import br.com.erpadv.dto.ContractDtos.ContractResponse;
import br.com.erpadv.dto.DocumentDtos.DocumentFilter;
import br.com.erpadv.dto.DocumentDtos.DocumentResponse;
import br.com.erpadv.firebase.SearchTokens;
import br.com.erpadv.models.Appointment;
import br.com.erpadv.models.Board;
import br.com.erpadv.models.BoardCard;
import br.com.erpadv.models.CaseMovement;
import br.com.erpadv.models.Deadline;
import br.com.erpadv.models.DeadlineStatus;
import br.com.erpadv.models.LegalCase;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.repositories.AppointmentRepository;
import br.com.erpadv.repositories.BoardRepositories;
import br.com.erpadv.repositories.CaseMovementRepository;
import br.com.erpadv.repositories.DeadlineRepository;
import br.com.erpadv.repositories.UserRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Permission;
import com.google.cloud.firestore.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Pesquisa global: clientes, CPF/CNPJ, processos, nº CNJ, advogados, documentos, contratos, prazos,
 * audiências/compromissos, tarefas e movimentações — cada categoria só aparece para quem tem a
 * permissão correspondente, e processos restritos continuam ocultos para quem não pode vê-los.
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final int PER_GROUP = 5;
    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public record SearchHit(String id, String title, String subtitle, String link) {
    }

    public record SearchGroup(String key, String label, String icon, List<SearchHit> items) {
    }

    private final ClientService clients;
    private final CaseService cases;
    private final DocumentService documents;
    private final ContractService contracts;
    private final DeadlineRepository deadlines;
    private final AppointmentRepository appointments;
    private final BoardRepositories.Cards cards;
    private final BoardRepositories.Boards boards;
    private final CaseMovementRepository movements;
    private final UserRepository users;

    public SearchService(ClientService clients, CaseService cases, DocumentService documents, ContractService contracts,
                         DeadlineRepository deadlines, AppointmentRepository appointments, BoardRepositories.Cards cards,
                         BoardRepositories.Boards boards, CaseMovementRepository movements, UserRepository users) {
        this.clients = clients;
        this.cases = cases;
        this.documents = documents;
        this.contracts = contracts;
        this.deadlines = deadlines;
        this.appointments = appointments;
        this.cards = cards;
        this.boards = boards;
        this.movements = movements;
        this.users = users;
    }

    public List<SearchGroup> search(AuthenticatedUser user, String q) {
        String officeId = user.requireOffice();
        String term = q == null ? "" : q.trim();
        if (term.length() < 2) {
            return List.of();
        }
        String norm = SearchTokens.normalize(term);
        List<SearchGroup> groups = new ArrayList<>();

        if (user.has(Permission.CLIENTS_READ)) {
            add(groups, "clients", "Clientes", "person", () -> clients.list(user, term, null, null, null, null, PER_GROUP).items()
                    .stream().map((ClientSummary c) -> new SearchHit(c.id(), c.name(),
                            c.documentMasked() + (c.phone() == null ? "" : " · " + c.phone()), "/clientes/" + c.id())).toList());
        }
        if (user.has(Permission.CASES_READ)) {
            add(groups, "cases", "Processos", "gavel", () -> cases.list(user, new CaseFilter(term, null, null, null, null, null, true),
                    null, PER_GROUP).items().stream().map((CaseResponse c) -> new SearchHit(c.id(),
                    c.cnjNumber() == null ? "Processo não distribuído" : c.cnjNumber(),
                    c.clientName() + (c.opposingParty() == null ? "" : " x " + c.opposingParty()), "/processos/" + c.id())).toList());
        }
        add(groups, "lawyers", "Advogados e equipe", "badge", () -> users.activeStaff(officeId).stream()
                .filter(u -> contains(u.getDisplayNameLower(), norm) || contains(u.getOabNumber(), norm) || contains(u.getEmail(), norm))
                .limit(PER_GROUP)
                .map((UserProfile u) -> new SearchHit(u.getId(), u.getDisplayName(),
                        (u.getRole() == null ? "" : u.getRole().label()) + (u.getOabNumber() == null ? "" : " · OAB " + u.getOabNumber()),
                        user.has(Permission.EMPLOYEES_READ) ? "/equipe" : null)).toList());
        if (user.has(Permission.DOCUMENTS_READ)) {
            add(groups, "documents", "Documentos", "description", () -> documents.list(user,
                    new DocumentFilter(term, null, null, null, false), null, PER_GROUP).items().stream()
                    .map((DocumentResponse d) -> new SearchHit(d.id(), d.title(),
                            (d.categoryLabel() == null ? "" : d.categoryLabel()) + (d.clientName() == null ? "" : " · " + d.clientName()),
                            "/documentos?doc=" + d.id())).toList());
        }
        if (user.has(Permission.CONTRACTS_READ)) {
            add(groups, "contracts", "Contratos", "handshake", () -> contracts.list(user, null, null, term, false, null, PER_GROUP)
                    .items().stream().map((ContractResponse c) -> new SearchHit(c.id(), c.number() + " · " + c.title(),
                            c.clientName(), "/contratos/" + c.id())).toList());
        }
        if (user.has(Permission.DEADLINES_READ)) {
            add(groups, "deadlines", "Prazos", "alarm", () -> deadlines.list(deadlines.collection(officeId)
                            .whereEqualTo("status", DeadlineStatus.PENDENTE.name()).orderBy("dueDate"), 300).stream()
                    .filter(d -> !d.isRestricted() || user.has(Permission.CRIMINAL_READ))
                    .filter(d -> contains(d.getDescription(), norm) || contains(d.getCaseNumber(), norm) || contains(d.getClientName(), norm))
                    .limit(PER_GROUP)
                    .map((Deadline d) -> new SearchHit(d.getId(), d.getDescription(),
                            "vence " + (d.getDueDate() == null ? "—" : d.getDueDate().format(DATE))
                                    + (d.getCaseNumber() == null ? "" : " · " + d.getCaseNumber()), "/prazos?id=" + d.getId())).toList());
        }
        if (user.has(Permission.CALENDAR_READ)) {
            long from = Instant.now().minus(30, ChronoUnit.DAYS).toEpochMilli();
            add(groups, "appointments", "Audiências e agenda", "event", () -> appointments.list(appointments.collection(officeId)
                            .whereGreaterThanOrEqualTo("start", from).orderBy("start"), 400).stream()
                    .filter(a -> !a.isRestricted() || user.has(Permission.CRIMINAL_READ))
                    .filter(a -> contains(a.getTitle(), norm) || contains(a.getCaseNumber(), norm) || contains(a.getClientName(), norm)
                            || contains(a.getLocation(), norm))
                    .limit(PER_GROUP)
                    .map((Appointment a) -> new SearchHit(a.getId(), a.getTitle(),
                            (a.getType() == null ? "" : a.getType().label() + " · ")
                                    + (a.getStart() == null ? "" : a.getStart().atZone(ZONE).format(DATETIME)), "/agenda")).toList());
        }
        if (user.has(Permission.BOARDS_USE)) {
            Map<String, Optional<Board>> boardCache = new HashMap<>();
            add(groups, "tasks", "Tarefas", "task_alt", () -> cards.list(cards.collection(officeId)
                            .whereEqualTo("archived", false), 500).stream()
                    .filter(c -> contains(c.getTitle(), norm) || contains(c.getCaseNumber(), norm) || contains(c.getClientName(), norm))
                    .filter(c -> boardCache.computeIfAbsent(c.getBoardId(), id -> boards.findById(officeId, id))
                            .filter(b -> !b.isArchived() && BoardService.canView(user, b)).isPresent())
                    .limit(PER_GROUP)
                    .map((BoardCard c) -> new SearchHit(c.getId(), c.getTitle(),
                            boardCache.get(c.getBoardId()).map(Board::getName).orElse("") + (c.isCompleted() ? " · concluída" : ""),
                            "/quadros/" + c.getBoardId() + "?card=" + c.getId())).toList());
        }
        if (user.has(Permission.CASES_READ)) {
            Map<String, Boolean> visible = new HashMap<>();
            add(groups, "movements", "Movimentações", "timeline", () -> movements.list(movements.collection(officeId)
                            .orderBy("createdAt", Query.Direction.DESCENDING), 400).stream()
                    .filter(m -> m.getType() != br.com.erpadv.models.MovementType.SISTEMA)
                    .filter(m -> contains(m.getTitle(), norm) || contains(m.getDescription(), norm))
                    .filter(m -> visible.computeIfAbsent(m.getCaseId(), id -> canSeeCase(user, id)))
                    .limit(PER_GROUP)
                    .map((CaseMovement m) -> new SearchHit(m.getId(), m.getTitle(),
                            (m.getType() == null ? "" : m.getType().label()) + " · " + (m.getDate() == null ? "" : m.getDate().format(DATE)),
                            "/processos/" + m.getCaseId())).toList());
        }
        return groups;
    }

    private boolean canSeeCase(AuthenticatedUser user, String caseId) {
        try {
            LegalCase c = cases.load(user, caseId);
            return c != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Cada categoria é independente: uma falha (ex.: índice ausente) não derruba a pesquisa inteira. */
    private void add(List<SearchGroup> groups, String key, String label, String icon, Supplier<List<SearchHit>> supplier) {
        try {
            List<SearchHit> items = supplier.get();
            if (!items.isEmpty()) {
                groups.add(new SearchGroup(key, label, icon, items));
            }
        } catch (RuntimeException e) {
            log.warn("Pesquisa global: categoria {} indisponível: {}", key, e.getMessage());
        }
    }

    static boolean contains(String value, String normalizedTerm) {
        return value != null && SearchTokens.normalize(value).contains(normalizedTerm);
    }
}
