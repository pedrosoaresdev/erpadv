package br.com.erpadv.services;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.dto.FeeDtos.CommissionLine;
import br.com.erpadv.dto.FeeDtos.CommissionStatement;
import br.com.erpadv.dto.FeeDtos.FeeGroup;
import br.com.erpadv.dto.FeeDtos.FeeItem;
import br.com.erpadv.dto.FeeDtos.FeeSummary;
import br.com.erpadv.dto.FeeDtos.FeeTotals;
import br.com.erpadv.dto.FeeDtos.GroupBy;
import br.com.erpadv.dto.FeeDtos.PayoutRequest;
import br.com.erpadv.dto.FeeDtos.PayoutResponse;
import br.com.erpadv.dto.FeeDtos.RuleRequest;
import br.com.erpadv.dto.FeeDtos.RuleResponse;
import br.com.erpadv.dto.FinanceDtos.TransactionRequest;
import br.com.erpadv.dto.FinanceDtos.TransactionResponse;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.models.CommissionPayout;
import br.com.erpadv.models.CommissionRule;
import br.com.erpadv.models.Contract;
import br.com.erpadv.models.FinanceCategory;
import br.com.erpadv.models.FinanceDirection;
import br.com.erpadv.models.FinanceScope;
import br.com.erpadv.models.FinancialTransaction;
import br.com.erpadv.models.LegalCase;
import br.com.erpadv.models.TransactionStatus;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.repositories.CaseRepository;
import br.com.erpadv.repositories.ContractRepositories;
import br.com.erpadv.repositories.FeeRepositories;
import br.com.erpadv.repositories.FinancialTransactionRepository;
import br.com.erpadv.repositories.UserRepository;
import br.com.erpadv.security.AuthenticatedUser;
import com.google.cloud.firestore.Query;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Honorários: visão por cliente, processo, contrato e categoria (contratado, recebido, pendente, em atraso),
 * histórico de pagamentos e comissões sobre honorários recebidos. Somente lançamentos do escritório.
 */
@Service
public class FeeService {

    /** Receitas tratadas como honorários (inclui o partido mensal dos contratos). */
    public static final Set<FinanceCategory> FEE_CATEGORIES = EnumSet.of(FinanceCategory.HONORARIOS, FinanceCategory.HONORARIOS_EXITO,
            FinanceCategory.HONORARIOS_SUCUMBENCIA, FinanceCategory.CONTRATO);

    private final FinancialTransactionRepository transactions;
    private final ContractRepositories.Contracts contracts;
    private final CaseRepository cases;
    private final UserRepository users;
    private final FeeRepositories.Rules rules;
    private final FeeRepositories.Payouts payouts;
    private final FinanceService finance;
    private final ClientService clients;
    private final CaseService caseService;
    private final AuditService audit;

    public FeeService(FinancialTransactionRepository transactions, ContractRepositories.Contracts contracts, CaseRepository cases,
                      UserRepository users, FeeRepositories.Rules rules, FeeRepositories.Payouts payouts, FinanceService finance,
                      ClientService clients, CaseService caseService, AuditService audit) {
        this.transactions = transactions;
        this.contracts = contracts;
        this.cases = cases;
        this.users = users;
        this.rules = rules;
        this.payouts = payouts;
        this.finance = finance;
        this.clients = clients;
        this.caseService = caseService;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ visão de honorários

    public FeeSummary summary(AuthenticatedUser user, LocalDate from, LocalDate to, GroupBy groupBy) {
        String officeId = user.requireOffice();
        checkRange(from, to);
        LocalDate today = FinanceService.today();
        List<FinancialTransaction> fees = fees(officeId, from, to);
        Map<String, List<FinancialTransaction>> grouped = new LinkedHashMap<>();
        for (FinancialTransaction t : fees) {
            String key = switch (groupBy == null ? GroupBy.CLIENT : groupBy) {
                case CASE -> t.getCaseId() == null ? "-" : t.getCaseId();
                case CONTRACT -> t.getContractId() == null ? "-" : t.getContractId();
                case CATEGORY -> t.getCategory().name();
                default -> t.getClientId() == null ? "-" : t.getClientId();
            };
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }
        Map<String, Contract> contractMap = new HashMap<>();
        if (groupBy == GroupBy.CONTRACT) {
            for (String id : grouped.keySet()) {
                if (!"-".equals(id)) {
                    contracts.findById(officeId, id).ifPresent(c -> contractMap.put(id, c));
                }
            }
        }
        List<FeeGroup> groups = new ArrayList<>();
        for (var e : grouped.entrySet()) {
            FinancialTransaction first = e.getValue().get(0);
            String label;
            String detail = null;
            Integer success = null;
            String feeModel = null;
            switch (groupBy == null ? GroupBy.CLIENT : groupBy) {
                case CASE -> {
                    label = "-".equals(e.getKey()) ? "Sem processo vinculado" : first.getCaseNumber() == null ? "Processo sem número CNJ" : first.getCaseNumber();
                    detail = first.getClientName();
                }
                case CONTRACT -> {
                    Contract c = contractMap.get(e.getKey());
                    label = c == null ? "Sem contrato" : c.getNumber() + " — " + c.getTitle();
                    detail = first.getClientName();
                    if (c != null) {
                        success = c.getSuccessPercentBp();
                        feeModel = c.getFeeModel() == null ? null : c.getFeeModel().label();
                    }
                }
                case CATEGORY -> label = first.getCategory().label();
                default -> label = "-".equals(e.getKey()) ? "Sem cliente vinculado" : first.getClientName() == null ? first.getCounterparty() : first.getClientName();
            }
            groups.add(new FeeGroup(e.getKey(), label, detail, totals(e.getValue(), today), success, feeModel));
        }
        groups.sort(Comparator.comparingLong((FeeGroup g) -> g.totals().contractedCents()).reversed());
        Map<FinanceCategory, List<FinancialTransaction>> byCat = new LinkedHashMap<>();
        fees.forEach(t -> byCat.computeIfAbsent(t.getCategory(), k -> new ArrayList<>()).add(t));
        List<FeeGroup> categories = byCat.entrySet().stream()
                .map(e -> new FeeGroup(e.getKey().name(), e.getKey().label(), null, totals(e.getValue(), today), null, null)).toList();
        return new FeeSummary(from, to, totals(fees, today), groups, categories);
    }

    /** Parcelas e pagamentos de honorários de um cliente, processo ou contrato (todas as datas). */
    public List<FeeItem> items(AuthenticatedUser user, String clientId, String caseId, String contractId) {
        String officeId = user.requireOffice();
        Query q = transactions.collection(officeId);
        if (notBlank(contractId)) {
            q = q.whereEqualTo("contractId", contractId);
        } else if (notBlank(caseId)) {
            caseService.load(user, caseId);
            q = q.whereEqualTo("caseId", caseId);
        } else if (notBlank(clientId)) {
            q = q.whereEqualTo("clientId", clientId);
        } else {
            throw new BusinessException("FILTER_REQUIRED", "Informe o cliente, o processo ou o contrato.");
        }
        LocalDate today = FinanceService.today();
        return transactions.list(q, 2000).stream()
                .filter(t -> t.getScope() == FinanceScope.ESCRITORIO && FEE_CATEGORIES.contains(t.getCategory()))
                .sorted(Comparator.comparing(FinancialTransaction::getDueDate))
                .map(t -> {
                    TransactionResponse r = TransactionResponse.of(t, today);
                    return new FeeItem(t.getId(), t.getCategory(), t.getCategory().label(), t.getDescription(), t.getClientId(),
                            t.getClientName(), t.getCaseId(), t.getCaseNumber(), t.getContractId(), t.getDueDate(), t.getAmountCents(),
                            r.situation().name(), t.getPaidDate(), t.getPaidAmountCents(),
                            t.getPaymentMethod() == null ? null : t.getPaymentMethod().name(), t.getInstallmentNumber(),
                            t.getInstallmentCount(), t.getReceiptNumber());
                }).toList();
    }

    private List<FinancialTransaction> fees(String officeId, LocalDate from, LocalDate to) {
        Query q = transactions.collection(officeId).whereEqualTo("scope", FinanceScope.ESCRITORIO.name())
                .whereEqualTo("direction", FinanceDirection.RECEITA.name())
                .whereGreaterThanOrEqualTo("dueDate", from.toString()).whereLessThanOrEqualTo("dueDate", to.toString());
        return transactions.list(q, 5000).stream().filter(t -> FEE_CATEGORIES.contains(t.getCategory())).toList();
    }

    static FeeTotals totals(List<FinancialTransaction> items, LocalDate today) {
        long contracted = 0;
        long received = 0;
        long pending = 0;
        long overdue = 0;
        int titles = 0;
        for (FinancialTransaction t : items) {
            if (t.getStatus() == TransactionStatus.CANCELADO) {
                continue;
            }
            titles++;
            contracted += t.getAmountCents();
            if (t.getStatus() == TransactionStatus.PAGO) {
                received += t.getPaidAmountCents() == null ? t.getAmountCents() : t.getPaidAmountCents();
            } else {
                pending += t.getAmountCents();
                if (t.getDueDate().isBefore(today)) {
                    overdue += t.getAmountCents();
                }
            }
        }
        return new FeeTotals(contracted, received, pending, overdue, titles);
    }

    // ------------------------------------------------------------------ comissões: regras

    public List<RuleResponse> rules(AuthenticatedUser user) {
        return rules.list(rules.collection(user.requireOffice()).orderBy("beneficiaryName"), 300).stream().map(RuleResponse::of).toList();
    }

    public RuleResponse saveRule(AuthenticatedUser user, String id, RuleRequest req) {
        String officeId = user.requireOffice();
        CommissionRule r = id == null ? new CommissionRule() : rules.findById(officeId, id).orElseThrow(() -> new NotFoundException("Regra"));
        UserProfile beneficiary = users.findInOffice(officeId, req.beneficiaryUid())
                .filter(u -> u.getRole() != null && u.getRole().isStaff())
                .orElseThrow(() -> new BusinessException("INVALID_BENEFICIARY", "Integrante não encontrado."));
        List<FinanceCategory> cats = req.categories() == null || req.categories().isEmpty() ? new ArrayList<>(FEE_CATEGORIES)
                : req.categories().stream().distinct().toList();
        if (cats.stream().anyMatch(c -> !FEE_CATEGORIES.contains(c))) {
            throw new BusinessException("INVALID_CATEGORY", "Comissões incidem apenas sobre honorários.");
        }
        r.setBeneficiaryUid(beneficiary.getId());
        r.setBeneficiaryName(beneficiary.getDisplayName());
        r.setDescription(notBlank(req.description()) ? req.description().trim() : null);
        r.setPercentBp(req.percentBp());
        r.setCategories(new ArrayList<>(cats));
        r.setCaseId(null);
        r.setCaseNumber(null);
        r.setClientId(null);
        r.setClientName(null);
        if (notBlank(req.caseId())) {
            LegalCase c = caseService.load(user, req.caseId());
            r.setCaseId(c.getId());
            r.setCaseNumber(c.getCnjNumber());
            r.setClientId(c.getClientId());
            r.setClientName(c.getClientName());
        } else if (notBlank(req.clientId())) {
            var c = clients.load(user, req.clientId());
            r.setClientId(c.getId());
            r.setClientName(c.getName());
        }
        r.setResponsibleOnly(req.responsibleOnly());
        r.setActive(req.active());
        Instant now = Instant.now();
        if (id == null) {
            r.setId(UUID.randomUUID().toString());
            r.setCreatedAt(now);
            r.setCreatedBy(user.uid());
        }
        r.setUpdatedAt(now);
        r.setUpdatedBy(user.uid());
        rules.save(officeId, r);
        audit.record(user, id == null ? "COMMISSION_RULE_CREATED" : "COMMISSION_RULE_UPDATED", "commission_rule", r.getId(),
                "Comissão de " + r.getBeneficiaryName() + ": " + percent(r.getPercentBp()));
        return RuleResponse.of(r);
    }

    public void deleteRule(AuthenticatedUser user, String id) {
        CommissionRule r = rules.findById(user.requireOffice(), id).orElseThrow(() -> new NotFoundException("Regra"));
        rules.delete(user.officeId(), id);
        audit.record(user, "COMMISSION_RULE_DELETED", "commission_rule", id, "Regra de comissão excluída: " + r.getBeneficiaryName());
    }

    // ------------------------------------------------------------------ comissões: apuração

    /** Apuração do período (data do recebimento). {@code onlyUid} limita a um beneficiário (ex.: "minhas comissões"). */
    public List<CommissionStatement> commissions(AuthenticatedUser user, LocalDate from, LocalDate to, String onlyUid) {
        String officeId = user.requireOffice();
        checkRange(from, to);
        List<CommissionRule> active = rules.list(rules.collection(officeId).whereEqualTo("active", true), 300).stream()
                .filter(r -> onlyUid == null || onlyUid.equals(r.getBeneficiaryUid())).toList();
        if (active.isEmpty()) {
            return List.of();
        }
        List<FinancialTransaction> received = transactions.list(transactions.collection(officeId)
                        .whereEqualTo("scope", FinanceScope.ESCRITORIO.name()).whereEqualTo("direction", FinanceDirection.RECEITA.name())
                        .whereEqualTo("status", TransactionStatus.PAGO.name())
                        .whereGreaterThanOrEqualTo("paidDate", from.toString()).whereLessThanOrEqualTo("paidDate", to.toString()), 5000)
                .stream().filter(t -> FEE_CATEGORIES.contains(t.getCategory())).toList();
        Map<String, String> responsible = new HashMap<>();
        for (FinancialTransaction t : received) {
            if (t.getCaseId() != null && !responsible.containsKey(t.getCaseId())) {
                responsible.put(t.getCaseId(), cases.findById(officeId, t.getCaseId()).map(LegalCase::getResponsibleUid).orElse(""));
            }
        }
        Map<String, Set<String>> paidOut = new HashMap<>();
        for (CommissionPayout p : payouts.list(payouts.collection(officeId), 2000)) {
            if (onlyUid == null || onlyUid.equals(p.getBeneficiaryUid())) {
                paidOut.computeIfAbsent(p.getBeneficiaryUid(), k -> new HashSet<>()).addAll(p.getReceiptIds());
            }
        }
        Map<String, List<CommissionLine>> lines = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        for (CommissionRule r : active) {
            names.put(r.getBeneficiaryUid(), r.getBeneficiaryName());
            for (FinancialTransaction t : received) {
                if (!r.getCategories().contains(t.getCategory())) {
                    continue;
                }
                if (r.getCaseId() != null && !r.getCaseId().equals(t.getCaseId())) {
                    continue;
                }
                if (r.getCaseId() == null && r.getClientId() != null && !r.getClientId().equals(t.getClientId())) {
                    continue;
                }
                if (r.isResponsibleOnly() && (t.getCaseId() == null || !r.getBeneficiaryUid().equals(responsible.get(t.getCaseId())))) {
                    continue;
                }
                long base = t.getPaidAmountCents() == null ? t.getAmountCents() : t.getPaidAmountCents();
                long commission = Math.round(base * r.getPercentBp() / 10000.0);
                boolean done = paidOut.getOrDefault(r.getBeneficiaryUid(), Set.of()).contains(t.getId());
                lines.computeIfAbsent(r.getBeneficiaryUid(), k -> new ArrayList<>()).add(new CommissionLine(t.getId(), t.getPaidDate(),
                        t.getDescription(), t.getClientName(), t.getCaseNumber(), base, r.getPercentBp(), commission, r.getId(), done));
            }
        }
        List<CommissionStatement> out = new ArrayList<>();
        for (var e : lines.entrySet()) {
            List<CommissionLine> l = e.getValue().stream().sorted(Comparator.comparing(CommissionLine::paidDate)).toList();
            long base = l.stream().mapToLong(CommissionLine::baseCents).sum();
            long total = l.stream().mapToLong(CommissionLine::commissionCents).sum();
            long done = l.stream().filter(CommissionLine::paidOut).mapToLong(CommissionLine::commissionCents).sum();
            out.add(new CommissionStatement(e.getKey(), names.get(e.getKey()), base, total, total - done, done, l));
        }
        out.sort(Comparator.comparing(CommissionStatement::beneficiaryName, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    /** Lança no contas a pagar as comissões pendentes do período para o beneficiário. */
    public PayoutResponse payout(AuthenticatedUser user, PayoutRequest req) {
        String officeId = user.requireOffice();
        CommissionStatement st = commissions(user, req.from(), req.to(), req.beneficiaryUid()).stream().findFirst()
                .orElseThrow(() -> new BusinessException("NOTHING_TO_PAY", "Não há comissões apuradas no período para este integrante."));
        List<CommissionLine> pending = st.lines().stream().filter(l -> !l.paidOut()).toList();
        long amount = pending.stream().mapToLong(CommissionLine::commissionCents).sum();
        if (amount <= 0) {
            throw new BusinessException("NOTHING_TO_PAY", "As comissões do período já foram lançadas.");
        }
        List<TransactionResponse> created = finance.create(user, new TransactionRequest(FinanceDirection.DESPESA, FinanceCategory.COMISSOES,
                "Comissões de " + st.beneficiaryName() + " — " + req.from() + " a " + req.to(), amount, req.dueDate(), null, null,
                st.beneficiaryName(), null, null, pending.size() + " recebimento(s) de honorários", FinanceScope.ESCRITORIO, null, null));
        CommissionPayout p = new CommissionPayout();
        p.setId(UUID.randomUUID().toString());
        p.setBeneficiaryUid(st.beneficiaryUid());
        p.setBeneficiaryName(st.beneficiaryName());
        p.setPeriodFrom(req.from());
        p.setPeriodTo(req.to());
        p.setAmountCents(amount);
        p.setReceiptIds(pending.stream().map(CommissionLine::receiptId).distinct().toList());
        p.setExpenseId(created.get(0).id());
        p.setCreatedByName(user.displayName());
        p.setCreatedAt(Instant.now());
        p.setCreatedBy(user.uid());
        payouts.save(officeId, p);
        audit.record(user, "COMMISSION_PAYOUT", "commission_payout", p.getId(), "Comissões lançadas para " + st.beneficiaryName()
                + ": " + FinanceSetupService.brl(amount));
        return PayoutResponse.of(p);
    }

    public List<PayoutResponse> payouts(AuthenticatedUser user, String onlyUid) {
        String officeId = user.requireOffice();
        return payouts.list(payouts.collection(officeId), 1000).stream()
                .filter(p -> onlyUid == null || onlyUid.equals(p.getBeneficiaryUid()))
                .sorted(Comparator.comparing(CommissionPayout::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(PayoutResponse::of).toList();
    }

    // ------------------------------------------------------------------ helpers

    private static void checkRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || Duration.between(from.atStartOfDay(), to.atStartOfDay()).toDays() > 1100) {
            throw new BusinessException("INVALID_RANGE", "Período inválido (máximo de 3 anos).");
        }
    }

    static String percent(int bp) {
        return (bp % 100 == 0 ? String.valueOf(bp / 100) : String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.2f", bp / 100.0)) + "%";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    @SuppressWarnings("unused")
    private static boolean same(Object a, Object b) {
        return Objects.equals(a, b);
    }
}
