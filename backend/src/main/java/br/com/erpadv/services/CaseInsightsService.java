package br.com.erpadv.services;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.dto.CaseDtos.CaseFilter;
import br.com.erpadv.dto.CaseDtos.CaseRelated;
import br.com.erpadv.dto.CaseDtos.CaseResponse;
import br.com.erpadv.dto.CaseDtos.RelatedAppointment;
import br.com.erpadv.dto.CaseDtos.RelatedCard;
import br.com.erpadv.dto.CaseDtos.RelatedContract;
import br.com.erpadv.dto.CaseDtos.RelatedDeadline;
import br.com.erpadv.dto.CaseDtos.RelatedTransaction;
import br.com.erpadv.dto.CursorPage;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.Appointment;
import br.com.erpadv.models.Board;
import br.com.erpadv.models.BoardCard;
import br.com.erpadv.models.CaseMovement;
import br.com.erpadv.models.Contract;
import br.com.erpadv.models.Deadline;
import br.com.erpadv.models.FinanceScope;
import br.com.erpadv.models.FinancialTransaction;
import br.com.erpadv.models.Office;
import br.com.erpadv.repositories.AppointmentRepository;
import br.com.erpadv.repositories.BoardRepositories;
import br.com.erpadv.repositories.ContractRepositories;
import br.com.erpadv.repositories.DeadlineRepository;
import br.com.erpadv.repositories.FinancialTransactionRepository;
import br.com.erpadv.repositories.OfficeRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Permission;
import br.com.erpadv.services.generation.ReportExporter;
import br.com.erpadv.services.generation.ReportExporter.Report;
import br.com.erpadv.services.generation.ReportExporter.Section;
import org.springframework.stereotype.Service;

import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Visão consolidada do processo (abas de prazos, audiências, financeiro, contratos e tarefas),
 * ficha/relatório completo e exportação da lista — sempre respeitando as permissões de cada módulo.
 */
@Service
public class CaseInsightsService {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final CaseService cases;
    private final DeadlineRepository deadlines;
    private final AppointmentRepository appointments;
    private final FinancialTransactionRepository transactions;
    private final ContractRepositories.Contracts contracts;
    private final BoardRepositories.Cards cards;
    private final BoardRepositories.Boards boards;
    private final OfficeRepository offices;
    private final AuditService audit;

    public CaseInsightsService(CaseService cases, DeadlineRepository deadlines, AppointmentRepository appointments,
                               FinancialTransactionRepository transactions, ContractRepositories.Contracts contracts,
                               BoardRepositories.Cards cards, BoardRepositories.Boards boards, OfficeRepository offices,
                               AuditService audit) {
        this.cases = cases;
        this.deadlines = deadlines;
        this.appointments = appointments;
        this.transactions = transactions;
        this.contracts = contracts;
        this.cards = cards;
        this.boards = boards;
        this.offices = offices;
        this.audit = audit;
    }

    public CaseRelated related(AuthenticatedUser user, String caseId) {
        cases.load(user, caseId);
        String officeId = user.officeId();

        List<RelatedDeadline> dl = null;
        if (user.has(Permission.DEADLINES_READ)) {
            dl = deadlines.list(deadlines.collection(officeId).whereEqualTo("caseId", caseId), 200).stream()
                    .sorted(Comparator.comparing(Deadline::getDueDate, Comparator.nullsLast(Comparator.reverseOrder())))
                    .map(d -> new RelatedDeadline(d.getId(), d.getDescription(), d.getDueDate(),
                            d.getStatus() == null ? null : d.getStatus().name(), d.isValidated(), d.getResponsibleName()))
                    .toList();
        }
        List<RelatedAppointment> ap = null;
        if (user.has(Permission.CALENDAR_READ)) {
            ap = appointments.list(appointments.collection(officeId).whereEqualTo("caseId", caseId), 200).stream()
                    .sorted(Comparator.comparing(Appointment::getStart, Comparator.nullsLast(Comparator.reverseOrder())))
                    .map(a -> new RelatedAppointment(a.getId(), a.getTitle(), a.getType() == null ? null : a.getType().name(),
                            a.getType() == null ? null : a.getType().label(), a.getStart(), a.getEnd(), a.getLocation()))
                    .toList();
        }
        List<RelatedTransaction> tx = null;
        if (user.has(Permission.FINANCE_READ)) {
            tx = transactions.list(transactions.collection(officeId).whereEqualTo("caseId", caseId), 300).stream()
                    .filter(t -> t.getScope() != FinanceScope.PESSOAL)
                    .sorted(Comparator.comparing(FinancialTransaction::getDueDate, Comparator.nullsLast(Comparator.reverseOrder())))
                    .map(t -> new RelatedTransaction(t.getId(), t.getDirection() == null ? null : t.getDirection().name(),
                            t.getCategory() == null ? null : t.getCategory().label(), t.getDescription(), t.getAmountCents(),
                            t.getDueDate(), t.getStatus() == null ? null : t.getStatus().name(), t.getPaidDate()))
                    .toList();
        }
        List<RelatedContract> ct = null;
        if (user.has(Permission.CONTRACTS_READ)) {
            ct = contracts.list(contracts.collection(officeId).whereEqualTo("caseId", caseId), 100).stream()
                    .map(c -> new RelatedContract(c.getId(), c.getNumber(), c.getTitle(),
                            c.getStatus() == null ? null : c.getStatus().name(), c.getFeeModel() == null ? null : c.getFeeModel().name()))
                    .toList();
        }
        List<RelatedCard> cd = null;
        if (user.has(Permission.BOARDS_USE)) {
            Map<String, Board> boardCache = new HashMap<>();
            cd = cards.list(cards.collection(officeId).whereEqualTo("caseId", caseId), 200).stream()
                    .filter(c -> !c.isArchived())
                    .map(c -> {
                        Board b = boardCache.computeIfAbsent(c.getBoardId(), id -> boards.findById(officeId, id).orElse(null));
                        if (b == null || b.isArchived() || !BoardService.canView(user, b)) {
                            return null;
                        }
                        String column = b.getColumns().stream().filter(x -> x.getId().equals(c.getColumnId()))
                                .map(Board.BoardColumn::getName).findFirst().orElse("");
                        return new RelatedCard(c.getId(), b.getId(), b.getName(), c.getTitle(), column, c.getDueDate(), c.isCompleted());
                    })
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(RelatedCard::completed).thenComparing(RelatedCard::dueDate, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
        }
        long docs = user.has(Permission.DOCUMENTS_READ)
                ? Futures.await(deadlines.collection(officeId).getParent().collection("documents")
                        .whereEqualTo("caseId", caseId).whereEqualTo("archived", false).count().get()).getCount()
                : 0;
        return new CaseRelated(dl, ap, tx, ct, cd, docs);
    }

    /** Relatório completo do processo (ficha + partes + movimentações + prazos + audiências). */
    public ReportExporter.Exported report(AuthenticatedUser user, String caseId, String format) {
        CaseResponse c = cases.get(user, caseId);
        CaseRelated rel = related(user, caseId);
        List<CaseMovement> movements = cases.allMovements(user, caseId);
        NumberFormat brl = NumberFormat.getCurrencyInstance(Locale.of("pt", "BR"));

        List<List<String>> data = new ArrayList<>();
        add(data, "Número CNJ", c.cnjNumber() == null ? "Não distribuído" : c.cnjNumber());
        add(data, "Cliente", c.clientName());
        add(data, "Área", c.areaLabel());
        add(data, "Classe processual", c.proceduralClass());
        add(data, "Assunto principal", c.subject());
        add(data, "Assuntos secundários", c.secondarySubjects() == null ? null : String.join("; ", c.secondarySubjects()));
        add(data, "Tribunal", c.court());
        add(data, "Instância", c.instance());
        add(data, "Juízo / órgão julgador", c.judgingBody());
        add(data, "Vara", c.courtDivision());
        add(data, "Comarca", c.district() == null ? null : c.district() + (c.state() == null ? "" : "/" + c.state()));
        add(data, "Advogado responsável", c.responsibleName() + (c.lawyerOab() == null ? "" : " — OAB " + c.lawyerOab()));
        add(data, "Distribuição", c.distributionDate() == null ? null : c.distributionDate().format(DATE));
        add(data, "Valor da causa", c.claimValueCents() == null ? null : brl.format(c.claimValueCents() / 100.0));
        add(data, "Justiça gratuita", c.freeJustice() ? "Sim" : "Não");
        add(data, "Segredo de justiça", c.confidential() ? "Sim" : "Não");
        add(data, "Situação", c.statusLabel());
        add(data, "Fase processual", c.phaseLabel());
        add(data, "Sentença", c.sentenceDate() == null ? null : c.sentenceDate().format(DATE));
        add(data, "Trânsito em julgado", c.finalJudgmentDate() == null ? null : c.finalJudgmentDate().format(DATE));
        add(data, "Observações internas", c.notes());

        List<Section> sections = new ArrayList<>();
        sections.add(Section.fields("Dados do processo", data));
        List<List<String>> parties = new ArrayList<>();
        add(parties, "Polo ativo", c.activeParty());
        add(parties, "Polo passivo", c.passiveParty());
        add(parties, "Parte contrária", c.opposingParty());
        sections.add(Section.fields("Partes", parties));
        sections.add(Section.table("Movimentações", List.of("Data", "Tipo", "Título", "Descrição", "Registrado por"),
                movements.stream().map(m -> List.of(m.getDate() == null ? "" : m.getDate().format(DATE),
                        m.getType() == null ? "" : m.getType().label() + (m.isImportant() ? " ★" : ""), nz(m.getTitle()),
                        nz(m.getDescription()), nz(m.getAuthorName()))).toList()));
        if (rel.deadlines() != null) {
            sections.add(Section.table("Prazos", List.of("Vencimento", "Descrição", "Situação", "Validado", "Responsável"),
                    rel.deadlines().stream().map(d -> List.of(d.dueDate() == null ? "" : d.dueDate().format(DATE), nz(d.description()),
                            nz(d.status()), d.validated() ? "Sim" : "Não", nz(d.responsibleName()))).toList()));
        }
        if (rel.appointments() != null) {
            sections.add(Section.table("Audiências e compromissos", List.of("Início", "Tipo", "Título", "Local"),
                    rel.appointments().stream().map(a -> List.of(a.start() == null ? "" : a.start().atZone(ZONE).format(DATETIME),
                            nz(a.typeLabel()), nz(a.title()), nz(a.location()))).toList()));
        }
        if (c.responsibleHistory() != null && !c.responsibleHistory().isEmpty()) {
            sections.add(Section.table("Histórico de responsáveis", List.of("Advogado", "De", "Até"),
                    c.responsibleHistory().stream().map(h -> List.of(nz(h.getName()),
                            h.getFrom() == null ? "" : h.getFrom().atZone(ZONE).format(DATE),
                            h.getTo() == null ? "atual" : h.getTo().atZone(ZONE).format(DATE))).toList()));
        }
        String officeName = offices.findById(user.officeId()).map(Office::getName).orElse("Escritório");
        audit.record(user, "CASE_REPORT_EXPORTED", "case", caseId, "Relatório do processo gerado",
                Map.of("format", format == null ? "pdf" : format));
        return ReportExporter.export(new Report("Relatório do processo", officeName,
                (c.cnjNumber() == null ? "Processo não distribuído" : c.cnjNumber()) + " · " + c.clientName(), sections, false),
                format, "processo-" + (c.cnjNumber() == null ? c.id() : c.cnjNumber().replaceAll("\\D", "")));
    }

    /** Exporta a lista de processos (filtros atuais ou seleção). */
    public ReportExporter.Exported export(AuthenticatedUser user, CaseFilter filter, List<String> ids, String format) {
        List<CaseResponse> rows = new ArrayList<>();
        if (ids != null && !ids.isEmpty()) {
            for (String id : ids.stream().limit(1000).toList()) {
                try {
                    rows.add(cases.get(user, id));
                } catch (RuntimeException ignored) {
                    // processo inexistente ou sem permissão: não entra na exportação
                }
            }
        } else {
            String cursor = null;
            do {
                CursorPage<CaseResponse> page = cases.list(user, filter, cursor, 100);
                rows.addAll(page.items());
                cursor = page.nextCursor();
            } while (cursor != null && rows.size() < 5000);
        }
        NumberFormat brl = NumberFormat.getCurrencyInstance(Locale.of("pt", "BR"));
        List<List<String>> table = rows.stream().map(c -> List.of(c.cnjNumber() == null ? "Não distribuído" : c.cnjNumber(),
                nz(c.clientName()), nz(c.opposingParty()), nz(c.areaLabel()), nz(c.court()), nz(c.responsibleName()),
                nz(c.statusLabel()), nz(c.phaseLabel()),
                c.claimValueCents() == null ? "" : brl.format(c.claimValueCents() / 100.0),
                c.distributionDate() == null ? "" : c.distributionDate().format(DATE))).toList();
        String officeName = offices.findById(user.officeId()).map(Office::getName).orElse("Escritório");
        String filters = Stream.of(filter.search() == null || filter.search().isBlank() ? null : "busca \"" + filter.search() + "\"",
                        filter.status() == null ? null : filter.status().label(), filter.area() == null ? null : filter.area().label(),
                        filter.court() == null || filter.court().isBlank() ? null : "tribunal " + filter.court(),
                        ids != null && !ids.isEmpty() ? "seleção manual" : null)
                .filter(Objects::nonNull).collect(Collectors.joining(", "));
        audit.record(user, "CASES_EXPORTED", "case", "*", "Exportação de processos (" + rows.size() + ")",
                Map.of("format", format == null ? "pdf" : format));
        return ReportExporter.export(new Report("Processos", officeName,
                rows.size() + " processo(s)" + (filters.isEmpty() ? "" : " · Filtros: " + filters),
                List.of(Section.table("Processos", List.of("Número CNJ", "Cliente", "Parte contrária", "Área", "Tribunal",
                        "Responsável", "Situação", "Fase", "Valor da causa", "Distribuição"), table)), true), format, "processos");
    }

    private static void add(List<List<String>> rows, String label, String value) {
        if (value != null && !value.isBlank()) {
            rows.add(List.of(label, value));
        }
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
