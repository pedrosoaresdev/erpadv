package br.com.erpadv.services;

import br.com.erpadv.dto.DashboardDtos.CaseStats;
import br.com.erpadv.dto.DashboardDtos.ClientStats;
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

    private record CacheEntry(DashboardResponse response, Instant expiresAt) {
    }

    private final ClientRepository clients;
    private final CaseRepository cases;
    private final UserRepository users;
    private final DeadlineRepository deadlines;
    private final AppointmentRepository appointments;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public DashboardService(ClientRepository clients, CaseRepository cases, UserRepository users,
                            DeadlineRepository deadlines, AppointmentRepository appointments) {
        this.clients = clients;
        this.cases = cases;
        this.users = users;
        this.deadlines = deadlines;
        this.appointments = appointments;
    }

    public DashboardResponse get(AuthenticatedUser user, LocalDate from, LocalDate to) {
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
        String key = officeId + "|" + start + "|" + end;
        CacheEntry cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.response();
        }
        DashboardResponse response = compute(officeId, start, end);
        if (cache.size() > 1000) {
            cache.clear();
        }
        cache.put(key, new CacheEntry(response, Instant.now().plus(CACHE_TTL)));
        return response;
    }

    private DashboardResponse compute(String officeId, LocalDate start, LocalDate end) {
        CollectionReference cl = clients.collection(officeId);
        CollectionReference cs = cases.collection(officeId);
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
        CollectionReference dl = deadlines.collection(officeId);
        CollectionReference ap = appointments.collection(officeId);
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
        Query hearings = ap.whereEqualTo("type", AppointmentType.AUDIENCIA.name());
        long nowMs = System.currentTimeMillis();
        f.put("hr.next30", hearings.whereGreaterThanOrEqualTo("start", nowMs)
                .whereLessThan("start", nowMs + 30L * 24 * 3600 * 1000).count().get());
        for (YearMonth m : months) {
            f.put("hr.m." + m, hearings.whereGreaterThanOrEqualTo("start", startOfDay(m.atDay(1)))
                    .whereLessThan("start", startOfDay(m.plusMonths(1).atDay(1))).count().get());
        }

        List<UserProfile> lawyers = users.activeStaff(officeId).stream().filter(u -> u.getRole().isLawyer()).toList();
        for (UserProfile lawyer : lawyers) {
            f.put("lawyer." + lawyer.getId(), cs.whereEqualTo("responsibleUid", lawyer.getId())
                    .whereEqualTo("archived", false).count().get());
        }

        Map<String, Long> v = new LinkedHashMap<>();
        f.forEach((k, future) -> v.put(k, Futures.await(future).getCount()));

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
        pendingModules.put("contracts", "Contratos — Etapa 11");

        return new DashboardResponse(start, end, clientStats, caseStats, clientGrowth, newCases, byArea, byStatus,
                productivity, deadlineStats, hearingStats, pendingModules);
    }

    private static Query range(CollectionReference col, long fromInclusive, long toExclusive) {
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
}
