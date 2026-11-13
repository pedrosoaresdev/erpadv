package br.com.erpadv.services;

import br.com.erpadv.dto.DashboardDtos.CaseExtra;
import br.com.erpadv.dto.DashboardDtos.CaseStats;
import br.com.erpadv.dto.DashboardDtos.Comparison;
import br.com.erpadv.dto.DashboardDtos.DeadlineMonth;
import br.com.erpadv.dto.DashboardDtos.DeadlineWindows;
import br.com.erpadv.dto.DashboardDtos.TeamMember;
import br.com.erpadv.models.CrmEnums.LeadSource;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import br.com.erpadv.dto.DashboardDtos.ClientStats;
import br.com.erpadv.dto.DashboardDtos.ContractStats;
import br.com.erpadv.dto.DashboardDtos.DeadlineStats;
import br.com.erpadv.dto.DashboardDtos.HearingStats;
import br.com.erpadv.dto.DeadlineDtos;
import br.com.erpadv.models.AppointmentType;
import br.com.erpadv.models.DeadlineStatus;
import br.com.erpadv.repositories.AppointmentRepository;
import br.com.erpadv.repositories.DeadlineRepository;
import br.com.erpadv.dto.DashboardDtos.DashboardResponse;
import br.com.erpadv.dto.DashboardDtos.MonthPoint;
import br.com.erpadv.dto.DashboardDtos.NamedCount;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.CaseStatus;
import br.com.erpadv.models.LegalArea;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.repositories.CaseRepository;
import br.com.erpadv.repositories.ClientRepository;
import br.com.erpadv.repositories.UserRepository;
import br.com.erpadv.repositories.OfficeRepository;
import br.com.erpadv.dto.FinanceDtos.FinanceSummary;
import br.com.erpadv.dto.FinanceDtos.MonthFlow;
import br.com.erpadv.models.Office;
import br.com.erpadv.security.Permission;
import br.com.erpadv.services.generation.ReportExporter;
import br.com.erpadv.services.generation.ReportExporter.Report;
import br.com.erpadv.services.generation.ReportExporter.Section;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import br.com.erpadv.security.AuthenticatedUser;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.AggregateQuerySnapshot;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.Query;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Indicadores executivos calculados com agregações {@code count()} do Firestore (cobradas por lote
 * de índice, não por documento), disparadas em paralelo e mantidas em cache por 60 s por escritório.
 */
@Service
public class DashboardService {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final int MAX_MONTHS = 24;
    /** Processo ativo sem movimentação há este número de dias é considerado "aguardando movimentação". */
    static final int STALE_DAYS = 60;
    private static final int PROJECTION_LIMIT = 5000;

    private record CacheEntry(DashboardResponse response, Instant expiresAt) {
    }

    private final ClientRepository clients;
    private final CaseRepository cases;
    private final UserRepository users;
    private final DeadlineRepository deadlines;
    private final AppointmentRepository appointments;
    private final OfficeRepository offices;
    private final FinanceReportService financeReports;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public DashboardService(ClientRepository clients, CaseRepository cases, UserRepository users,
                            DeadlineRepository deadlines, AppointmentRepository appointments, OfficeRepository offices,
                            FinanceReportService financeReports) {
        this.clients = clients;
        this.cases = cases;
        this.users = users;
        this.deadlines = deadlines;
        this.appointments = appointments;
        this.offices = offices;
        this.financeReports = financeReports;
    }

    /** Exportação do painel em PDF ou Excel (indicadores financeiros só para quem pode vê-los). */
    public ReportExporter.Exported export(AuthenticatedUser user, LocalDate from, LocalDate to, String lawyerUid, String format) {
        return export(user, from, to, lawyerUid, null, format);
    }

    public ReportExporter.Exported export(AuthenticatedUser user, LocalDate from, LocalDate to, String lawyerUid, String unitId, String format) {
        DashboardResponse d = get(user, from, to, lawyerUid, unitId);
        FinanceSummary finance = user.has(Permission.FINANCE_DASHBOARD_VIEW) && user.has(Permission.FINANCE_READ)
                ? financeReports.summary(user, null, d.from(), d.to()) : null;
        String officeName = offices.findById(user.officeId()).map(Office::getName).orElse("Escritório");
        String lawyerName = d.lawyerUid() == null ? null
                : users.findById(d.lawyerUid()).map(UserProfile::getDisplayName).orElse(null);
        Report report = buildReport(d, finance, officeName, lawyerName);
        return ReportExporter.export(report, format, "dashboard-" + d.from() + "-a-" + d.to());
    }

    static Report buildReport(DashboardResponse d, FinanceSummary finance, String officeName, String lawyerName) {
        DateTimeFormatter br = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        NumberFormat brl = NumberFormat.getCurrencyInstance(java.util.Locale.of("pt", "BR"));
        java.util.function.LongFunction<String> money = c -> brl.format(c / 100.0);
        String subtitle = "Período: " + d.from().format(br) + " a " + d.to().format(br)
                + (lawyerName == null ? "" : " · Advogado: " + lawyerName);
        List<Section> sections = new ArrayList<>();
        List<List<String>> kpis = new ArrayList<>(List.of(
                List.of("Clientes (total)", String.valueOf(d.clients().total())),
                List.of("Clientes ativos / inativos", d.clients().active() + " / " + d.clients().inactive()),
                List.of("Novos clientes no período", d.clients().newInPeriod() + " (período anterior: " + d.previous().newClients() + ")"),
                List.of("Clientes inadimplentes", String.valueOf(d.clients().defaulting())),
                List.of("Processos (total)", String.valueOf(d.cases().total())),
                List.of("Processos ativos", String.valueOf(d.cases().inProgress())),
                List.of("Processos suspensos", String.valueOf(d.caseExtra().suspended())),
                List.of("Processos encerrados / arquivados", d.cases().closed() + " / " + d.cases().archived()),
                List.of("Aguardando movimentação (" + d.caseExtra().staleDays() + "+ dias)", String.valueOf(d.caseExtra().awaitingMovement())),
                List.of("Novos processos no período", d.cases().newInPeriod() + " (período anterior: " + d.previous().newCases() + ")"),
                List.of("Prazos para hoje", String.valueOf(d.deadlines().dueToday())),
                List.of("Prazos em 3 / 7 / 15 dias", d.deadlineWindows().next3Days() + " / " + d.deadlineWindows().next7Days()
                        + " / " + d.deadlineWindows().next15Days()),
                List.of("Prazos vencidos", String.valueOf(d.deadlines().overdue())),
                List.of("Prazos cumpridos no período", d.deadlines().completedInPeriod() + " (período anterior: " + d.previous().deadlinesCompleted() + ")"),
                List.of("Audiências nos próximos 30 dias", String.valueOf(d.hearings().next30Days())),
                List.of("Compromissos nos próximos 7 dias", String.valueOf(d.agendaNext7Days())),
                List.of("Contratos ativos / vencendo em 30 dias", d.contracts().active() + " / " + d.contracts().expiring30Days())));
        sections.add(Section.fields("Indicadores", kpis));
        if (finance != null) {
            long margin = finance.receivedCents() == 0 ? 0 : Math.round(100.0 * finance.balanceCents() / finance.receivedCents());
            sections.add(Section.fields("Financeiro (escritório)", List.of(
                    List.of("Faturamento no período (recebido)", money.apply(finance.receivedCents())),
                    List.of("Honorários recebidos", money.apply(finance.feesReceivedCents())),
                    List.of("Honorários pendentes", money.apply(finance.feesPendingCents())),
                    List.of("Despesas pagas", money.apply(finance.paidCents())),
                    List.of("Resultado do período", money.apply(finance.balanceCents())),
                    List.of("Rentabilidade (resultado ÷ receita)", margin + "%"),
                    List.of("Inadimplência (receitas vencidas)", money.apply(finance.overdueReceivableCents())
                            + " em " + finance.overdueReceivableCount() + " título(s)"))));
            List<List<String>> flows = new ArrayList<>();
            for (MonthFlow m : finance.monthly()) {
                flows.add(List.of(m.month(), money.apply(m.incomeCents()), money.apply(m.expenseCents()), money.apply(m.netCents())));
            }
            sections.add(Section.table("Receitas x despesas", List.of("Mês", "Receitas", "Despesas", "Resultado"), flows));
        }
        sections.add(Section.table("Crescimento de clientes", List.of("Mês", "Novos", "Total acumulado"),
                d.clientGrowth().stream().map(p -> List.of(p.month(), String.valueOf(p.value()),
                        String.valueOf(p.cumulative() == null ? 0 : p.cumulative()))).toList()));
        sections.add(Section.table("Processos por situação", List.of("Situação", "Quantidade"),
                d.casesByStatus().stream().map(n -> List.of(n.label(), String.valueOf(n.value()))).toList()));
        sections.add(Section.table("Processos por área", List.of("Área", "Quantidade"),
                d.casesByArea().stream().map(n -> List.of(n.label(), String.valueOf(n.value()))).toList()));
        sections.add(Section.table("Processos por tribunal", List.of("Tribunal", "Processos"),
                d.casesByCourt().stream().map(n -> List.of(n.label(), String.valueOf(n.value()))).toList()));
        sections.add(Section.table("Prazos cumpridos x não cumpridos", List.of("Mês", "Cumpridos", "Não cumpridos"),
                d.deadlineTrend().stream().map(m -> List.of(m.month(), String.valueOf(m.completed()), String.valueOf(m.missed()))).toList()));
        sections.add(Section.table("Origem dos novos clientes", List.of("Origem", "Clientes"),
                d.clientsBySource().stream().map(n -> List.of(n.label(), String.valueOf(n.value()))).toList()));
        sections.add(Section.table("Desempenho da equipe", List.of("Advogado", "Processos ativos", "Prazos cumpridos", "Prazos vencidos"),
                d.team().stream().map(t -> List.of(t.name(), String.valueOf(t.activeCases()), String.valueOf(t.deadlinesCompleted()),
                        String.valueOf(t.deadlinesOverdue()))).toList()));
        return new Report("Dashboard executivo", officeName, subtitle, sections, false);
    }

    public DashboardResponse get(AuthenticatedUser user, LocalDate from, LocalDate to) {
        return get(user, from, to, null);
    }

    public DashboardResponse get(AuthenticatedUser user, LocalDate from, LocalDate to, String lawyerUid) {
        return get(user, from, to, lawyerUid, null);
    }

    /** Filtro por unidade/filial: considera os integrantes vinculados à unidade como responsáveis. */
    public DashboardResponse get(AuthenticatedUser user, LocalDate from, LocalDate to, String lawyerUid, String unitId) {
        String officeId = user.requireOffice();
        LocalDate today = LocalDate.now(ZONE);
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusMonths(11).withDayOfMonth(1) : from;
        if (start.isAfter(end)) {
            throw new BusinessException("INVALID_PERIOD", "Data inicial posterior à final.");
        }
        if (ChronoUnit.MONTHS.between(YearMonth.from(start), YearMonth.from(end)) >= MAX_MONTHS) {
            throw new BusinessException("INVALID_PERIOD", "Período máximo de " + MAX_MONTHS + " meses.");
        }
        String lawyer = lawyerUid == null || lawyerUid.isBlank() ? null : lawyerUid;
        if (lawyer != null && users.findInOffice(officeId, lawyer).isEmpty()) {
            throw new BusinessException("INVALID_LAWYER", "Advogado não encontrado no escritório.");
        }
        List<String> unitUids = null;
        if (unitId != null && !unitId.isBlank()) {
            units.get(officeId, unitId);
            unitUids = units.memberUids(officeId, unitId);
            if (unitUids.size() > 30) {
                unitUids = unitUids.subList(0, 30);
            }
        }
        String key = officeId + "|" + start + "|" + end + "|" + lawyer + "|" + unitId;
        CacheEntry cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.response();
        }
        DashboardResponse response = compute(officeId, start, end, lawyer, unitUids);
        if (cache.size() > 1000) {
            cache.clear();
        }
        cache.put(key, new CacheEntry(response, Instant.now().plus(CACHE_TTL)));
        return response;
    }

    private DashboardResponse compute(String officeId, LocalDate start, LocalDate end, String lawyer, List<String> unitUids) {
        List<String> scope = unitUids == null ? null : unitUids.isEmpty() ? List.of("__sem_integrantes__") : unitUids;
        CollectionReference clCol = clients.collection(officeId);
        CollectionReference csCol = cases.collection(officeId);
        // Filtro por advogado: clientes, processos, prazos, audiências e contratos sob sua responsabilidade.
        Query cl = lawyer != null ? clCol.whereEqualTo("responsibleUid", lawyer) : scope != null ? clCol.whereIn("responsibleUid", scope) : clCol;
        Query cs = lawyer != null ? csCol.whereEqualTo("responsibleUid", lawyer) : scope != null ? csCol.whereIn("responsibleUid", scope) : csCol;
        long periodStart = startOfDay(start);
        long periodEnd = startOfDay(end.plusDays(1));

        // Dispara todas as agregações em paralelo e só depois aguarda.
        Map<String, ApiFuture<AggregateQuerySnapshot>> f = new LinkedHashMap<>();
        f.put("clients.total", cl.count().get());
        f.put("clients.active", cl.whereEqualTo("status", "ACTIVE").count().get());
        f.put("clients.defaulting", cl.whereEqualTo("defaulting", true).count().get());
        f.put("clients.new", range(cl, periodStart, periodEnd).count().get());
        f.put("cases.total", cs.count().get());
        f.put("cases.archived", cs.whereEqualTo("archived", true).count().get());
        f.put("cases.new", range(cs, periodStart, periodEnd).count().get());
        for (CaseStatus status : CaseStatus.values()) {
            f.put("status." + status.name(), cs.whereEqualTo("status", status.name()).count().get());
        }
        for (LegalArea area : LegalArea.values()) {
            f.put("area." + area.name(), cs.whereEqualTo("area", area.name()).count().get());
        }
        List<YearMonth> months = months(start, end);
        f.put("clients.before", cl.whereLessThan("createdAt", startOfDay(months.get(0).atDay(1))).count().get());
        for (YearMonth m : months) {
            long a = startOfDay(m.atDay(1));
            long b = startOfDay(m.plusMonths(1).atDay(1));
            f.put("clients.m." + m, range(cl, a, b).count().get());
            f.put("cases.m." + m, range(cs, a, b).count().get());
        }
        // Prazos e audiências (Etapa 9)
        CollectionReference dlCol = deadlines.collection(officeId);
        CollectionReference apCol = appointments.collection(officeId);
        Query dl = lawyer != null ? dlCol.whereEqualTo("responsibleUid", lawyer) : scope != null ? dlCol.whereIn("responsibleUid", scope) : dlCol;
        Query ap = lawyer != null ? apCol.whereArrayContains("responsibleUids", lawyer)
                : scope != null ? apCol.whereArrayContainsAny("responsibleUids", scope) : apCol;
        LocalDate today = LocalDate.now(ZONE);
        String t = today.toString();
        String urgentLimit = today.plusDays(DeadlineDtos.URGENT_DAYS).toString();
        Query pending = dl.whereEqualTo("status", DeadlineStatus.PENDENTE.name());
        f.put("dl.today", pending.whereEqualTo("dueDate", t).count().get());
        f.put("dl.urgent", pending.whereGreaterThan("dueDate", t).whereLessThanOrEqualTo("dueDate", urgentLimit).count().get());
        f.put("dl.upcoming", pending.whereGreaterThan("dueDate", urgentLimit).count().get());
        f.put("dl.overdue", pending.whereLessThan("dueDate", t).count().get());
        f.put("dl.unvalidated", pending.whereEqualTo("validated", false).count().get());
        f.put("dl.completed", dl.whereEqualTo("status", DeadlineStatus.CONCLUIDO.name())
                .whereGreaterThanOrEqualTo("dueDate", start.toString())
                .whereLessThanOrEqualTo("dueDate", end.toString()).count().get());
        CollectionReference ctCol = csCol.getParent().collection("contracts");
        Query ct = lawyer != null ? ctCol.whereEqualTo("responsibleUid", lawyer) : scope != null ? ctCol.whereIn("responsibleUid", scope) : ctCol;
        f.put("ct.active", ct.whereEqualTo("status", "ATIVO").count().get());
        f.put("ct.draft", ct.whereEqualTo("status", "RASCUNHO").count().get());
        f.put("ct.expiring", ct.whereEqualTo("status", "ATIVO").whereGreaterThanOrEqualTo("endDate", t)
                .whereLessThanOrEqualTo("endDate", today.plusDays(30).toString()).count().get());
        Query hearings = ap.whereEqualTo("type", AppointmentType.AUDIENCIA.name());
        long nowMs = System.currentTimeMillis();
        f.put("hr.next30", hearings.whereGreaterThanOrEqualTo("start", nowMs)
                .whereLessThan("start", nowMs + 30L * 24 * 3600 * 1000).count().get());
        for (YearMonth m : months) {
            f.put("hr.m." + m, hearings.whereGreaterThanOrEqualTo("start", startOfDay(m.atDay(1)))
                    .whereLessThan("start", startOfDay(m.plusMonths(1).atDay(1))).count().get());
        }

        // Janelas de prazos, agenda e período anterior (comparação)
        for (int days : new int[]{3, 7, 15}) {
            f.put("dl.next" + days, pending.whereGreaterThan("dueDate", t)
                    .whereLessThanOrEqualTo("dueDate", today.plusDays(days).toString()).count().get());
        }
        f.put("ag.next7", ap.whereGreaterThanOrEqualTo("start", nowMs)
                .whereLessThan("start", nowMs + 7L * 24 * 3600 * 1000).count().get());
        long spanDays = ChronoUnit.DAYS.between(start, end) + 1;
        LocalDate prevEnd = start.minusDays(1);
        LocalDate prevStart = prevEnd.minusDays(spanDays - 1);
        f.put("prev.clients", range(cl, startOfDay(prevStart), startOfDay(prevEnd.plusDays(1))).count().get());
        f.put("prev.cases", range(cs, startOfDay(prevStart), startOfDay(prevEnd.plusDays(1))).count().get());
        f.put("prev.dl", dl.whereEqualTo("status", DeadlineStatus.CONCLUIDO.name())
                .whereGreaterThanOrEqualTo("dueDate", prevStart.toString())
                .whereLessThanOrEqualTo("dueDate", prevEnd.toString()).count().get());
        for (YearMonth m : months) {
            String a = m.atDay(1).toString();
            String b = m.atEndOfMonth().toString();
            f.put("dlm.done." + m, dl.whereEqualTo("status", DeadlineStatus.CONCLUIDO.name())
                    .whereGreaterThanOrEqualTo("dueDate", a).whereLessThanOrEqualTo("dueDate", b).count().get());
            String missedUntil = m.atEndOfMonth().isBefore(today) ? b : today.minusDays(1).toString();
            f.put("dlm.missed." + m, missedUntil.compareTo(a) < 0 ? null
                    : pending.whereGreaterThanOrEqualTo("dueDate", a).whereLessThanOrEqualTo("dueDate", missedUntil).count().get());
        }

        List<UserProfile> lawyers = users.activeStaff(officeId).stream().filter(u -> u.getRole().isLawyer())
                .filter(u -> lawyer == null || u.getId().equals(lawyer)).filter(u -> scope == null || scope.contains(u.getId())).toList();
        for (UserProfile member : lawyers) {
            f.put("lawyer." + member.getId(), csCol.whereEqualTo("responsibleUid", member.getId())
                    .whereEqualTo("archived", false).count().get());
            Query own = dlCol.whereEqualTo("responsibleUid", member.getId());
            f.put("team.done." + member.getId(), own.whereEqualTo("status", DeadlineStatus.CONCLUIDO.name())
                    .whereGreaterThanOrEqualTo("dueDate", start.toString())
                    .whereLessThanOrEqualTo("dueDate", end.toString()).count().get());
            f.put("team.overdue." + member.getId(), own.whereEqualTo("status", DeadlineStatus.PENDENTE.name())
                    .whereLessThan("dueDate", t).count().get());
        }
        f.values().removeIf(java.util.Objects::isNull);

        // Projeções leves (somente os campos necessários) para agrupamentos sem índice próprio.
        var courtsFuture = cs.select("court", "status", "archived", "lastMovementAt", "createdAt").limit(PROJECTION_LIMIT).get();
        var sourcesFuture = range(cl, periodStart, periodEnd).select("source").limit(PROJECTION_LIMIT).get();

        Map<String, Long> v = new HashMap<>();
        f.forEach((k, future) -> v.put(k, Futures.await(future).getCount()));
        List<QueryDocumentSnapshot> caseDocs = Futures.await(courtsFuture).getDocuments();
        List<QueryDocumentSnapshot> sourceDocs = Futures.await(sourcesFuture).getDocuments();

        long clientsTotal = v.get("clients.total");
        long clientsActive = v.get("clients.active");
        ClientStats clientStats = new ClientStats(clientsTotal, clientsActive, clientsTotal - clientsActive,
                v.get("clients.defaulting"), v.get("clients.new"));

        long inProgress = Arrays.stream(CaseStatus.values()).filter(CaseStatus::isActive)
                .mapToLong(s -> v.get("status." + s.name())).sum();
        CaseStats caseStats = new CaseStats(v.get("cases.total"), inProgress,
                v.get("status." + CaseStatus.ENCERRADO.name()), v.get("cases.archived"), v.get("cases.new"));

        List<MonthPoint> clientGrowth = new ArrayList<>();
        List<MonthPoint> newCases = new ArrayList<>();
        long cumulative = v.get("clients.before");
        for (YearMonth m : months) {
            long newClients = v.get("clients.m." + m);
            cumulative += newClients;
            clientGrowth.add(new MonthPoint(m.toString(), newClients, cumulative));
            newCases.add(new MonthPoint(m.toString(), v.get("cases.m." + m), null));
        }

        List<NamedCount> byArea = Arrays.stream(LegalArea.values())
                .map(a -> new NamedCount(a.name(), a.label(), v.get("area." + a.name())))
                .filter(n -> n.value() > 0)
                .sorted(Comparator.comparingLong(NamedCount::value).reversed())
                .toList();
        List<NamedCount> byStatus = Arrays.stream(CaseStatus.values())
                .map(s -> new NamedCount(s.name(), s.label(), v.get("status." + s.name())))
                .toList();
        List<NamedCount> productivity = lawyers.stream()
                .map(l -> new NamedCount(l.getId(), l.getDisplayName(), v.get("lawyer." + l.getId())))
                .sorted(Comparator.comparingLong(NamedCount::value).reversed())
                .toList();

        DeadlineStats deadlineStats = new DeadlineStats(v.get("dl.today"), v.get("dl.urgent"), v.get("dl.overdue"),
                v.get("dl.upcoming"), v.get("dl.completed"), v.get("dl.unvalidated"));
        HearingStats hearingStats = new HearingStats(v.get("hr.next30"),
                months.stream().map(m -> new MonthPoint(m.toString(), v.get("hr.m." + m), null)).toList());

        Map<String, String> pendingModules = new LinkedHashMap<>();

        long staleBefore = Instant.now().minus(STALE_DAYS, ChronoUnit.DAYS).toEpochMilli();
        long awaiting = caseDocs.stream().filter(d -> !Boolean.TRUE.equals(d.getBoolean("archived")))
                .filter(d -> isActiveStatus(d.getString("status")))
                .filter(d -> {
                    Long last = d.getLong("lastMovementAt");
                    Long created = d.getLong("createdAt");
                    long ref = last != null ? last : created != null ? created : Long.MAX_VALUE;
                    return ref < staleBefore;
                }).count();
        CaseExtra caseExtra = new CaseExtra(v.get("status." + CaseStatus.SUSPENSO.name()), awaiting, STALE_DAYS);
        List<NamedCount> byCourt = groupTop(caseDocs.stream()
                .filter(d -> !Boolean.TRUE.equals(d.getBoolean("archived")))
                .map(d -> normalizeCourt(d.getString("court"))).toList(), 10);
        List<NamedCount> bySource = groupTop(sourceDocs.stream().map(d -> sourceLabel(d.getString("source"))).toList(), 12);
        List<DeadlineMonth> trend = months.stream().map(m -> new DeadlineMonth(m.toString(),
                v.getOrDefault("dlm.done." + m, 0L), v.getOrDefault("dlm.missed." + m, 0L))).toList();
        List<TeamMember> team = lawyers.stream().map(l -> new TeamMember(l.getId(), l.getDisplayName(),
                        v.get("lawyer." + l.getId()), v.get("team.done." + l.getId()), v.get("team.overdue." + l.getId())))
                .sorted(Comparator.comparingLong(TeamMember::activeCases).reversed())
                .toList();
        Comparison previous = new Comparison(prevStart, prevEnd, v.get("prev.clients"), v.get("prev.cases"), v.get("prev.dl"));

        return new DashboardResponse(start, end, clientStats, caseStats, clientGrowth, newCases, byArea, byStatus,
                productivity, deadlineStats, hearingStats,
                new ContractStats(v.get("ct.active"), v.get("ct.expiring"), v.get("ct.draft")), pendingModules,
                lawyer, new DeadlineWindows(v.get("dl.next3"), v.get("dl.next7"), v.get("dl.next15")), caseExtra,
                v.get("ag.next7"), trend, byCourt, bySource, team, previous);
    }

    static boolean isActiveStatus(String status) {
        try {
            return status != null && CaseStatus.valueOf(status).isActive();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static String normalizeCourt(String court) {
        if (court == null || court.isBlank()) {
            return "Não informado";
        }
        return court.trim().replaceAll("\\s+", " ").toUpperCase(java.util.Locale.ROOT);
    }

    static String sourceLabel(String source) {
        if (source == null || source.isBlank()) {
            return "Não informada";
        }
        try {
            return LeadSource.valueOf(source).label();
        } catch (IllegalArgumentException e) {
            return "Outra";
        }
    }

    /** Agrupa e conta, mantendo os {@code top} maiores e somando o restante em "Outros". */
    static List<NamedCount> groupTop(List<String> values, int top) {
        Map<String, Long> counts = new HashMap<>();
        values.forEach(x -> counts.merge(x, 1L, Long::sum));
        List<NamedCount> sorted = counts.entrySet().stream()
                .map(e -> new NamedCount(e.getKey(), e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(NamedCount::value).reversed().thenComparing(NamedCount::label))
                .toList();
        if (sorted.size() <= top) {
            return sorted;
        }
        List<NamedCount> result = new ArrayList<>(sorted.subList(0, top));
        long others = sorted.subList(top, sorted.size()).stream().mapToLong(NamedCount::value).sum();
        result.add(new NamedCount("OUTROS", "Outros", others));
        return result;
    }

    private static Query range(Query col, long fromInclusive, long toExclusive) {
        return col.whereGreaterThanOrEqualTo("createdAt", fromInclusive).whereLessThan("createdAt", toExclusive);
    }

    private static List<YearMonth> months(LocalDate start, LocalDate end) {
        List<YearMonth> list = new ArrayList<>();
        for (YearMonth m = YearMonth.from(start); !m.isAfter(YearMonth.from(end)); m = m.plusMonths(1)) {
            list.add(m);
        }
        return list;
    }

    private static long startOfDay(LocalDate date) {
        return date.atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    @org.springframework.beans.factory.annotation.Autowired
    private br.com.erpadv.services.platform.UnitService units;
}
