package br.com.erpadv.services;

import br.com.erpadv.dto.FinanceDtos.AgingBucket;
import br.com.erpadv.dto.FinanceDtos.CategoryAmount;
import br.com.erpadv.dto.FinanceDtos.DelinquencyReport;
import br.com.erpadv.dto.FinanceDtos.DelinquentClient;
import br.com.erpadv.dto.FinanceDtos.Dre;
import br.com.erpadv.dto.FinanceDtos.DreLine;
import br.com.erpadv.dto.FinanceDtos.FinanceSummary;
import br.com.erpadv.dto.FinanceDtos.MonthFlow;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.FinanceCategory;
import br.com.erpadv.models.FinanceDirection;
import br.com.erpadv.models.FinanceScope;
import br.com.erpadv.models.FinancialTransaction;
import br.com.erpadv.models.TransactionStatus;
import br.com.erpadv.repositories.FinancialTransactionRepository;
import br.com.erpadv.security.AuthenticatedUser;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.AggregateField;
import com.google.cloud.firestore.AggregateQuerySnapshot;
import com.google.cloud.firestore.Query;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Relatórios financeiros calculados com agregações {@code sum()/count()} do Firestore (sem ler os
 * documentos), disparadas em paralelo e mantidas em cache por 60 s. Regime de caixa para realizado
 * (data de pagamento) e de vencimento para previsto.
 */
@Service
public class FinanceReportService {

    private static final int MAX_MONTHS = 24;
    private static final int PROJECTION_MONTHS = 6;
    private static final int DELINQUENCY_LIMIT = 2000;
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    /** Ordem e composição da DRE gerencial. */
    private static final List<Map.Entry<String, List<FinanceCategory>>> DRE_EXPENSES = List.of(
            Map.entry("Despesas com pessoal", List.of(FinanceCategory.PESSOAL, FinanceCategory.PRO_LABORE)),
            Map.entry("Despesas de ocupação", List.of(FinanceCategory.ALUGUEL, FinanceCategory.ENERGIA, FinanceCategory.INTERNET)),
            Map.entry("Despesas administrativas", List.of(FinanceCategory.SISTEMAS, FinanceCategory.FORNECEDORES)),
            Map.entry("Despesas comerciais", List.of(FinanceCategory.MARKETING)),
            Map.entry("Despesas operacionais", List.of(FinanceCategory.CUSTAS, FinanceCategory.OPERACIONAL, FinanceCategory.OUTRA_DESPESA)));

    private record CacheEntry(FinanceSummary summary, Instant expiresAt) {
    }

    private final FinancialTransactionRepository transactions;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public FinanceReportService(FinancialTransactionRepository transactions) {
        this.transactions = transactions;
    }

    public FinanceSummary summary(AuthenticatedUser user, FinanceScope requestedScope, LocalDate from, LocalDate to) {
        String officeId = user.requireOffice();
        FinanceScope scope = FinanceService.resolveScope(user, requestedScope);
        LocalDate today = FinanceService.today();
        LocalDate end = to == null ? today.withDayOfMonth(today.lengthOfMonth()) : to;
        LocalDate start = from == null ? end.minusMonths(11).withDayOfMonth(1) : from;
        if (start.isAfter(end) || ChronoUnit.MONTHS.between(YearMonth.from(start), YearMonth.from(end)) >= MAX_MONTHS) {
            throw new BusinessException("INVALID_PERIOD", "Período inválido (máximo de " + MAX_MONTHS + " meses).");
        }
        String owner = scope == FinanceScope.PESSOAL ? user.uid() : null;
        String key = officeId + "|" + scope + "|" + owner + "|" + start + "|" + end + "|" + today;
        CacheEntry cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.summary();
        }
        FinanceSummary summary = compute(officeId, scope, owner, start, end, today);
        if (cache.size() > 1000) {
            cache.clear();
        }
        cache.put(key, new CacheEntry(summary, Instant.now().plus(CACHE_TTL)));
        return summary;
    }

    public void invalidate(String officeId) {
        cache.keySet().removeIf(k -> k.startsWith(officeId + "|"));
    }

    private FinanceSummary compute(String officeId, FinanceScope scope, String owner, LocalDate start, LocalDate end,
                                   LocalDate today) {
        Map<String, ApiFuture<AggregateQuerySnapshot>> f = new LinkedHashMap<>();
        AggregateField paidSum = AggregateField.sum("paidAmountCents");
        AggregateField amountSum = AggregateField.sum("amountCents");
        AggregateField count = AggregateField.count();

        List<YearMonth> months = months(YearMonth.from(start), YearMonth.from(end));
        List<YearMonth> projection = months(YearMonth.from(today).plusMonths(1), YearMonth.from(today).plusMonths(PROJECTION_MONTHS));
        for (FinanceDirection d : FinanceDirection.values()) {
            Query paid = base(officeId, scope, owner).whereEqualTo("direction", d.name()).whereEqualTo("status", TransactionStatus.PAGO.name());
            Query open = base(officeId, scope, owner).whereEqualTo("direction", d.name()).whereEqualTo("status", TransactionStatus.ABERTO.name());
            for (YearMonth m : months) {
                f.put("real." + d + "." + m, range(paid, "paidDate", m.atDay(1), m.atEndOfMonth()).aggregate(paidSum).get());
                f.put("prev." + d + "." + m, range(open, "dueDate", m.atDay(1), m.atEndOfMonth()).aggregate(amountSum).get());
            }
            for (YearMonth m : projection) {
                f.put("proj." + d + "." + m, range(open, "dueDate", m.atDay(1), m.atEndOfMonth()).aggregate(amountSum).get());
            }
            f.put("overdue." + d, open.whereLessThan("dueDate", today.toString()).aggregate(amountSum, count).get());
            f.put("open." + d, range(open, "dueDate", start, end).aggregate(amountSum).get());
            f.put("paidPeriod." + d, range(paid, "paidDate", start, end).aggregate(paidSum).get());
        }
        for (FinanceCategory c : FinanceCategory.values()) {
            Query byCat = base(officeId, scope, owner).whereEqualTo("category", c.name());
            f.put("cat." + c, range(byCat.whereEqualTo("status", TransactionStatus.PAGO.name()), "paidDate", start, end)
                    .aggregate(paidSum).get());
            if (c.isFee()) {
                f.put("feePending." + c, byCat.whereEqualTo("status", TransactionStatus.ABERTO.name()).aggregate(amountSum).get());
            }
        }
        YearMonth current = YearMonth.from(today);
        f.put("current.income", range(base(officeId, scope, owner).whereEqualTo("direction", FinanceDirection.RECEITA.name())
                .whereEqualTo("status", TransactionStatus.PAGO.name()), "paidDate", current.atDay(1), current.atEndOfMonth())
                .aggregate(paidSum).get());

        Map<String, AggregateQuerySnapshot> r = new LinkedHashMap<>();
        f.forEach((k, v) -> r.put(k, Futures.await(v)));

        List<MonthFlow> monthly = new ArrayList<>();
        long cumulative = 0;
        for (YearMonth m : months) {
            long in = sum(r.get("real.RECEITA." + m), paidSum);
            long out = sum(r.get("real.DESPESA." + m), paidSum);
            cumulative += in - out;
            monthly.add(new MonthFlow(m.toString(), in, out, in - out, cumulative,
                    sum(r.get("prev.RECEITA." + m), amountSum), sum(r.get("prev.DESPESA." + m), amountSum)));
        }
        List<MonthFlow> projected = new ArrayList<>();
        long projectedCumulative = 0;
        for (YearMonth m : projection) {
            long in = sum(r.get("proj.RECEITA." + m), amountSum);
            long out = sum(r.get("proj.DESPESA." + m), amountSum);
            projectedCumulative += in - out;
            projected.add(new MonthFlow(m.toString(), 0, 0, in - out, projectedCumulative, in, out));
        }

        List<CategoryAmount> income = new ArrayList<>();
        List<CategoryAmount> expense = new ArrayList<>();
        Map<FinanceCategory, Long> byCategory = new LinkedHashMap<>();
        for (FinanceCategory c : FinanceCategory.values()) {
            long v = sum(r.get("cat." + c), paidSum);
            byCategory.put(c, v);
            if (v > 0) {
                (c.direction() == FinanceDirection.RECEITA ? income : expense)
                        .add(new CategoryAmount(c, c.label(), c.group(), v));
            }
        }
        income.sort(Comparator.comparingLong(CategoryAmount::amountCents).reversed());
        expense.sort(Comparator.comparingLong(CategoryAmount::amountCents).reversed());

        long received = sum(r.get("paidPeriod.RECEITA"), paidSum);
        long paid = sum(r.get("paidPeriod.DESPESA"), paidSum);
        long feesReceived = byCategory.get(FinanceCategory.HONORARIOS) + byCategory.get(FinanceCategory.HONORARIOS_EXITO);
        long feesPending = sum(r.get("feePending." + FinanceCategory.HONORARIOS), amountSum)
                + sum(r.get("feePending." + FinanceCategory.HONORARIOS_EXITO), amountSum);

        return new FinanceSummary(start, end, scope, received, paid, received - paid,
                sum(r.get("open.RECEITA"), amountSum), sum(r.get("open.DESPESA"), amountSum),
                sum(r.get("overdue.RECEITA"), amountSum), sum(r.get("overdue.RECEITA"), count),
                sum(r.get("overdue.DESPESA"), amountSum), sum(r.get("overdue.DESPESA"), count),
                feesReceived, feesPending, sum(r.get("current.income"), paidSum),
                monthly, projected, income, expense, dre(byCategory));
    }

    /** DRE gerencial em regime de caixa. */
    static Dre dre(Map<FinanceCategory, Long> byCategory) {
        List<DreLine> lines = new ArrayList<>();
        long services = Arrays.stream(FinanceCategory.values())
                .filter(c -> c.direction() == FinanceDirection.RECEITA && c.group().equals("Receita de serviços jurídicos"))
                .mapToLong(c -> byCategory.getOrDefault(c, 0L)).sum();
        long other = byCategory.getOrDefault(FinanceCategory.REEMBOLSO, 0L) + byCategory.getOrDefault(FinanceCategory.OUTRA_RECEITA, 0L);
        long gross = services + other;
        lines.add(new DreLine("Receita bruta", gross, 0, "total"));
        lines.add(new DreLine("Receita de serviços jurídicos", services, 1, "income"));
        for (FinanceCategory c : FinanceCategory.values()) {
            if (c.direction() == FinanceDirection.RECEITA && byCategory.getOrDefault(c, 0L) > 0) {
                lines.add(new DreLine(c.label(), byCategory.get(c), 2, "income"));
            }
        }
        if (other > 0) {
            lines.add(new DreLine("Outras receitas", other, 1, "income"));
        }
        long deductions = byCategory.getOrDefault(FinanceCategory.IMPOSTOS, 0L);
        lines.add(new DreLine("(−) Impostos sobre a receita", -deductions, 1, "expense"));
        long net = gross - deductions;
        lines.add(new DreLine("Receita líquida", net, 0, "total"));
        long expenses = 0;
        for (Map.Entry<String, List<FinanceCategory>> group : DRE_EXPENSES) {
            long g = group.getValue().stream().mapToLong(c -> byCategory.getOrDefault(c, 0L)).sum();
            expenses += g;
            lines.add(new DreLine("(−) " + group.getKey(), -g, 1, "expense"));
            for (FinanceCategory c : group.getValue()) {
                if (byCategory.getOrDefault(c, 0L) > 0) {
                    lines.add(new DreLine(c.label(), -byCategory.get(c), 2, "expense"));
                }
            }
        }
        long result = net - expenses;
        lines.add(new DreLine("Resultado do período", result, 0, "result"));
        Double margin = net == 0 ? null : Math.round(result * 1000.0 / net) / 10.0;
        return new Dre(lines, gross, deductions, net, expenses, result, margin);
    }

    public DelinquencyReport delinquency(AuthenticatedUser user) {
        String officeId = user.requireOffice();
        LocalDate today = FinanceService.today();
        List<FinancialTransaction> overdue = transactions.list(transactions.collection(officeId)
                .whereEqualTo("scope", FinanceScope.ESCRITORIO.name())
                .whereEqualTo("direction", FinanceDirection.RECEITA.name())
                .whereEqualTo("status", TransactionStatus.ABERTO.name())
                .whereLessThan("dueDate", today.toString())
                .orderBy("dueDate"), DELINQUENCY_LIMIT);
        long[] bucketAmount = new long[4];
        long[] bucketCount = new long[4];
        Map<String, long[]> perClient = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, LocalDate> oldest = new LinkedHashMap<>();
        long total = 0;
        for (FinancialTransaction t : overdue) {
            long days = ChronoUnit.DAYS.between(t.getDueDate(), today);
            int b = days <= 30 ? 0 : days <= 60 ? 1 : days <= 90 ? 2 : 3;
            bucketAmount[b] += t.getAmountCents();
            bucketCount[b]++;
            total += t.getAmountCents();
            String key = t.getClientId() != null ? t.getClientId() : "~" + (t.getCounterparty() == null ? "Sem cliente" : t.getCounterparty());
            long[] acc = perClient.computeIfAbsent(key, k -> new long[3]);
            acc[0] += t.getAmountCents();
            acc[1]++;
            acc[2] = Math.max(acc[2], days);
            names.putIfAbsent(key, t.getClientName() != null ? t.getClientName() : key.substring(1));
            oldest.merge(key, t.getDueDate(), (a, c) -> a.isBefore(c) ? a : c);
        }
        List<AgingBucket> aging = List.of(
                new AgingBucket("1 a 30 dias", bucketAmount[0], bucketCount[0]),
                new AgingBucket("31 a 60 dias", bucketAmount[1], bucketCount[1]),
                new AgingBucket("61 a 90 dias", bucketAmount[2], bucketCount[2]),
                new AgingBucket("Mais de 90 dias", bucketAmount[3], bucketCount[3]));
        List<DelinquentClient> clients = perClient.entrySet().stream()
                .map(e -> new DelinquentClient(e.getKey().startsWith("~") ? null : e.getKey(), names.get(e.getKey()),
                        e.getValue()[0], e.getValue()[1], oldest.get(e.getKey()), e.getValue()[2]))
                .sorted(Comparator.comparingLong(DelinquentClient::totalCents).reversed())
                .toList();
        return new DelinquencyReport(total, overdue.size(), aging, clients, overdue.size() >= DELINQUENCY_LIMIT);
    }

    // ------------------------------------------------------------------ helpers

    private Query base(String officeId, FinanceScope scope, String owner) {
        Query q = transactions.collection(officeId).whereEqualTo("scope", scope.name());
        return owner == null ? q : q.whereEqualTo("ownerUid", owner);
    }

    private static Query range(Query q, String field, LocalDate from, LocalDate to) {
        return q.whereGreaterThanOrEqualTo(field, from.toString()).whereLessThanOrEqualTo(field, to.toString());
    }

    private static long sum(AggregateQuerySnapshot snapshot, AggregateField field) {
        Object v = snapshot.get(field);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static List<YearMonth> months(YearMonth from, YearMonth to) {
        List<YearMonth> list = new ArrayList<>();
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
            list.add(m);
        }
        return list;
    }
}
