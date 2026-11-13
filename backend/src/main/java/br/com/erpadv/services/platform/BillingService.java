package br.com.erpadv.services.platform;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.Office;
import br.com.erpadv.models.Plan;
import br.com.erpadv.models.SubscriptionInvoice;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.repositories.OfficeRepository;
import br.com.erpadv.repositories.UserRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.OfficeStatusCache;
import br.com.erpadv.security.Permission;
import br.com.erpadv.security.PermissionResolver;
import br.com.erpadv.services.CourtCalendarService;
import br.com.erpadv.services.NotificationService;
import br.com.erpadv.services.generation.HtmlDocument;
import br.com.erpadv.services.generation.ReportExporter;
import br.com.erpadv.services.generation.ValorExtenso;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Assinaturas com cobrança manual: a plataforma gera as faturas no vencimento, avisa o escritório, marca o pagamento quando
 * recebe (Pix/boleto por fora do sistema) e suspende quem passar da tolerância — reativando ao registrar o pagamento.
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final int DUE_IN_DAYS = 5;
    private static final Set<String> BILLABLE = Set.of("ATIVA", "EM_ATRASO");

    /** Dados de pagamento exibidos nas faturas ({@code platform_settings/billing}). */
    @Data
    public static class BillingSettings {
        private String issuerName;
        private String issuerDocument;
        private String pixKey;
        private String bankInfo;
        private String instructions;
        private String contactEmail;
    }

    public record SubscriptionRequest(String planCode, String billingCycle, Long priceOverrideCents, Integer graceDays, Integer maxUsers,
                                      Integer maxStorageMb, String billingEmail, String subscriptionStatus, LocalDate nextInvoiceDate,
                                      LocalDate trialEndsAt) {
    }

    public record PaymentRequest(LocalDate paidDate, Long amountCents, String method, String note) {
    }

    public record PlanRequest(String code, String name, String description, Long monthlyPriceCents, Long yearlyPriceCents, Integer maxUsers,
                              Integer maxStorageMb, List<String> modules, int trialDays, boolean active, boolean highlight, int sortOrder) {
    }

    public record SubscriptionView(String officeId, String officeName, String planCode, String planName, String status, String billingCycle,
                                   Long priceCents, LocalDate trialEndsAt, LocalDate nextInvoiceDate, Integer graceDays, Integer maxUsers,
                                   Integer maxStorageMb, long users, long storageBytes, List<String> modules, String billingEmail,
                                   boolean suspended, String suspendedReason) {
    }

    @Repository
    public static class Invoices {
        private final Firestore db;
        private final FirestoreMapper mapper;

        public Invoices(Firestore db, FirestoreMapper mapper) {
            this.db = db;
            this.mapper = mapper;
        }

        public List<SubscriptionInvoice> list(Query q, int max) {
            List<SubscriptionInvoice> out = new ArrayList<>();
            for (QueryDocumentSnapshot d : Futures.await(q.limit(max).get()).getDocuments()) {
                out.add(mapper.fromSnapshot(d, SubscriptionInvoice.class));
            }
            return out;
        }

        public com.google.cloud.firestore.CollectionReference col() {
            return db.collection("subscription_invoices");
        }

        public java.util.Optional<SubscriptionInvoice> find(String id) {
            DocumentSnapshot s = Futures.await(col().document(id).get());
            return s.exists() ? java.util.Optional.of(mapper.fromSnapshot(s, SubscriptionInvoice.class)) : java.util.Optional.empty();
        }

        public SubscriptionInvoice save(SubscriptionInvoice i) {
            Futures.await(col().document(i.getId()).set(mapper.toMap(i)));
            return i;
        }

        public String nextNumber(int year) {
            DocumentReference counter = db.collection("platform_counters").document("invoices-" + year);
            long next = Futures.await(db.runTransaction(tx -> {
                DocumentSnapshot snap = tx.get(counter).get();
                Long current = snap.exists() ? snap.getLong("value") : null;
                long value = current == null ? 1 : current + 1;
                tx.set(counter, Map.of("value", value));
                return value;
            }));
            return String.format("FAT-%d-%05d", year, next);
        }

        public BillingSettings settings() {
            DocumentSnapshot s = Futures.await(db.collection("platform_settings").document("billing").get());
            return s.exists() ? mapper.fromSnapshot(s, BillingSettings.class) : new BillingSettings();
        }

        public void saveSettings(BillingSettings s) {
            Futures.await(db.collection("platform_settings").document("billing").set(mapper.toMap(s)));
        }
    }

    private final Invoices invoices;
    private final OfficeRepository offices;
    private final UserRepository users;
    private final PermissionResolver resolver;
    private final LicenseService license;
    private final OfficeStatusCache officeStatus;
    private final NotificationService notifications;
    private final AuditService audit;

    public BillingService(Invoices invoices, OfficeRepository offices, UserRepository users, PermissionResolver resolver, LicenseService license,
                          OfficeStatusCache officeStatus, NotificationService notifications, AuditService audit) {
        this.invoices = invoices;
        this.offices = offices;
        this.users = users;
        this.resolver = resolver;
        this.license = license;
        this.officeStatus = officeStatus;
        this.notifications = notifications;
        this.audit = audit;
    }

    static LocalDate today() {
        return LocalDate.now(CourtCalendarService.ZONE);
    }

    private Office office(String officeId) {
        return offices.findById(officeId).orElseThrow(() -> new NotFoundException("Escritório"));
    }

    /** Preço da assinatura no ciclo atual (negociado ou do plano); nulo quando o plano ainda não tem preço. */
    Long price(Office o, Plan p) {
        if (o.getPriceOverrideCents() != null) {
            return o.getPriceOverrideCents();
        }
        if (p == null) {
            return null;
        }
        return "ANUAL".equals(o.getBillingCycle()) ? p.getYearlyPriceCents() : p.getMonthlyPriceCents();
    }

    public SubscriptionView view(String officeId) {
        Office o = office(officeId);
        Plan p = license.plan(o.getPlan()).orElse(null);
        LicenseService.License l = license.license(officeId);
        return new SubscriptionView(o.getId(), o.getName(), o.getPlan(), p == null ? null : p.getName(), o.getSubscriptionStatus(),
                o.getBillingCycle(), price(o, p), o.getTrialEndsAt(), o.getNextInvoiceDate(), o.getGraceDays(), l.maxUsers(), l.maxStorageMb(),
                users.countSeats(officeId), license.storageUsed(officeId), l.modules().stream().map(Enum::name).toList(), o.getBillingEmail(),
                OfficeStatusCache.SUSPENDED.equals(o.getStatus()), o.getSuspendedReason());
    }

    // ------------------------------------------------------------------ painel master

    public List<Plan> plans() {
        return license.plans();
    }

    public Plan savePlan(AuthenticatedUser master, PlanRequest req) {
        String code = req.code() == null ? "" : req.code().trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "");
        if (code.isEmpty() || code.length() > 30 || req.name() == null || req.name().isBlank()) {
            throw new BusinessException("INVALID_PLAN", "Informe o código (letras e números) e o nome do plano.");
        }
        for (Long v : new Long[]{req.monthlyPriceCents(), req.yearlyPriceCents()}) {
            if (v != null && (v < 0 || v > 100_000_000L)) {
                throw new BusinessException("INVALID_PRICE", "Preço inválido.");
            }
        }
        Plan p = license.plan(code).orElseGet(Plan::new);
        p.setCode(code);
        p.setName(req.name().trim());
        p.setDescription(req.description() == null ? null : req.description().trim());
        p.setMonthlyPriceCents(req.monthlyPriceCents());
        p.setYearlyPriceCents(req.yearlyPriceCents());
        p.setMaxUsers(req.maxUsers() == null || req.maxUsers() <= 0 ? null : req.maxUsers());
        p.setMaxStorageMb(req.maxStorageMb() == null || req.maxStorageMb() <= 0 ? null : req.maxStorageMb());
        List<String> modules = new ArrayList<>();
        if (req.modules() != null) {
            for (String m : req.modules()) {
                try {
                    modules.add(br.com.erpadv.models.AppModule.valueOf(m).name());
                } catch (IllegalArgumentException e) {
                    throw new BusinessException("INVALID_MODULE", "Módulo desconhecido: " + m);
                }
            }
        }
        p.setModules(modules);
        p.setTrialDays(Math.max(0, Math.min(90, req.trialDays())));
        p.setActive(req.active());
        p.setHighlight(req.highlight());
        p.setSortOrder(req.sortOrder());
        p.setUpdatedAt(Instant.now());
        if (p.getCreatedAt() == null) {
            p.setCreatedAt(p.getUpdatedAt());
        }
        license.savePlan(p);
        log.info("Plano {} salvo por {}", code, master.email());
        return p;
    }

    public SubscriptionView updateSubscription(AuthenticatedUser master, String officeId, SubscriptionRequest req) {
        Office o = office(officeId);
        Map<String, Object> before = audit.snapshot(o);
        if (req.planCode() != null) {
            Plan p = license.plan(req.planCode()).orElseThrow(() -> new BusinessException("INVALID_PLAN", "Plano não encontrado."));
            o.setPlan(p.getCode());
        }
        if (req.billingCycle() != null) {
            if (!Set.of("MENSAL", "ANUAL").contains(req.billingCycle())) {
                throw new BusinessException("INVALID_CYCLE", "Ciclo deve ser MENSAL ou ANUAL.");
            }
            o.setBillingCycle(req.billingCycle());
        }
        o.setPriceOverrideCents(req.priceOverrideCents() == null || req.priceOverrideCents() < 0 ? null : req.priceOverrideCents());
        o.setGraceDays(req.graceDays() == null ? 10 : Math.max(0, Math.min(60, req.graceDays())));
        o.setMaxUsers(req.maxUsers() == null || req.maxUsers() <= 0 ? null : req.maxUsers());
        o.setMaxStorageMb(req.maxStorageMb() == null || req.maxStorageMb() <= 0 ? null : req.maxStorageMb());
        o.setBillingEmail(req.billingEmail() == null || req.billingEmail().isBlank() ? null : req.billingEmail().trim());
        if (req.subscriptionStatus() != null) {
            if (!Set.of("TRIAL", "ATIVA", "EM_ATRASO", "SUSPENSA", "CANCELADA").contains(req.subscriptionStatus())) {
                throw new BusinessException("INVALID_STATUS", "Situação inválida.");
            }
            o.setSubscriptionStatus(req.subscriptionStatus());
        }
        if (req.trialEndsAt() != null) {
            o.setTrialEndsAt(req.trialEndsAt());
        }
        if (req.nextInvoiceDate() != null) {
            o.setNextInvoiceDate(req.nextInvoiceDate());
        } else if ("ATIVA".equals(o.getSubscriptionStatus()) && o.getNextInvoiceDate() == null) {
            o.setNextInvoiceDate(today());
        }
        if (o.getBillingCycle() == null) {
            o.setBillingCycle("MENSAL");
        }
        o.setUpdatedAt(Instant.now());
        o.setUpdatedBy(master.uid());
        offices.save(o);
        license.evict(officeId);
        audit.recordChange(master, officeId, "SUBSCRIPTION_UPDATED", "office", officeId, "Assinatura atualizada pela plataforma", Map.of(), before, o);
        return view(officeId);
    }

    public List<SubscriptionInvoice> list(String officeId, SubscriptionInvoice.Status status) {
        Query q = invoices.col();
        if (officeId != null && !officeId.isBlank()) {
            q = q.whereEqualTo("officeId", officeId);
        } else if (status != null) {
            q = q.whereEqualTo("status", status.name());
        }
        return invoices.list(q, 2000).stream()
                .filter(i -> status == null || i.getStatus() == status)
                .sorted(Comparator.comparing(SubscriptionInvoice::getDueDate, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
    }

    /** Gera a fatura do período corrente agora (mesmo antes da data prevista). */
    public SubscriptionInvoice issueNow(AuthenticatedUser master, String officeId) {
        Office o = office(officeId);
        SubscriptionInvoice i = issue(o, today());
        if (i == null) {
            throw new BusinessException("NO_PRICE", "Defina o preço do plano (ou um preço negociado) antes de faturar.");
        }
        audit.record(master, officeId, "INVOICE_ISSUED", "office", officeId, "Fatura " + i.getNumber() + " emitida", Map.of());
        return i;
    }

    public SubscriptionInvoice markPaid(AuthenticatedUser master, String id, PaymentRequest req) {
        SubscriptionInvoice i = invoices.find(id).orElseThrow(() -> new NotFoundException("Fatura"));
        if (i.getStatus() == SubscriptionInvoice.Status.PAGA || i.getStatus() == SubscriptionInvoice.Status.CANCELADA) {
            throw new BusinessException("INVOICE_CLOSED", "Fatura já está " + i.getStatus().name().toLowerCase(Locale.ROOT) + ".");
        }
        LocalDate paid = req.paidDate() == null ? today() : req.paidDate();
        if (paid.isAfter(today())) {
            throw new BusinessException("INVALID_DATE", "Data de pagamento futura.");
        }
        i.setStatus(SubscriptionInvoice.Status.PAGA);
        i.setPaidDate(paid);
        i.setPaidAmountCents(req.amountCents() == null ? i.getAmountCents() : req.amountCents());
        i.setPaymentMethod(req.method() == null || req.method().isBlank() ? null : req.method().trim());
        i.setNote(req.note() == null || req.note().isBlank() ? null : req.note().trim());
        i.setMarkedByName(master.displayName());
        i.setUpdatedAt(Instant.now());
        invoices.save(i);
        settle(i.getOfficeId());
        audit.record(master, i.getOfficeId(), "INVOICE_PAID", "office", i.getOfficeId(), "Fatura " + i.getNumber() + " paga ("
                + brl(i.getPaidAmountCents()) + ")", Map.of());
        notifyAdmins(i.getOfficeId(), "Pagamento confirmado", "Recebemos o pagamento da fatura " + i.getNumber() + ". Obrigado!");
        return i;
    }

    public SubscriptionInvoice cancel(AuthenticatedUser master, String id, String reason) {
        SubscriptionInvoice i = invoices.find(id).orElseThrow(() -> new NotFoundException("Fatura"));
        if (i.getStatus() == SubscriptionInvoice.Status.PAGA) {
            throw new BusinessException("INVOICE_CLOSED", "Fatura paga não pode ser cancelada.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("REASON_REQUIRED", "Informe o motivo do cancelamento.");
        }
        i.setStatus(SubscriptionInvoice.Status.CANCELADA);
        i.setCancelReason(reason.trim());
        i.setMarkedByName(master.displayName());
        i.setUpdatedAt(Instant.now());
        invoices.save(i);
        settle(i.getOfficeId());
        audit.record(master, i.getOfficeId(), "INVOICE_CANCELED", "office", i.getOfficeId(), "Fatura " + i.getNumber() + " cancelada: " + reason, Map.of());
        return i;
    }

    /** Sem faturas vencidas pendentes: assinatura volta a ativa e a suspensão por cobrança é desfeita. */
    private void settle(String officeId) {
        boolean overdue = list(officeId, null).stream().anyMatch(x -> x.getStatus() == SubscriptionInvoice.Status.VENCIDA);
        if (overdue) {
            return;
        }
        Office o = office(officeId);
        if ("EM_ATRASO".equals(o.getSubscriptionStatus()) || "SUSPENSA".equals(o.getSubscriptionStatus())) {
            o.setSubscriptionStatus("ATIVA");
        }
        if (o.isBillingSuspended()) {
            o.setBillingSuspended(false);
            o.setStatus("ACTIVE");
            o.setSuspendedAt(null);
            o.setSuspendedReason(null);
            officeStatus.evict(officeId);
            notifyAdmins(officeId, "Acesso reativado", "O acesso do escritório foi reativado após a confirmação do pagamento.");
        }
        o.setUpdatedAt(Instant.now());
        offices.save(o);
    }

    public BillingSettings settings() {
        return invoices.settings();
    }

    public BillingSettings saveSettings(BillingSettings s) {
        invoices.saveSettings(s);
        return s;
    }

    // ------------------------------------------------------------------ rotina diária

    /** Encerra avaliações, emite faturas no vencimento, marca atrasos e suspende após a tolerância. */
    public int runDaily(LocalDate today) {
        int actions = 0;
        for (Office o : offices.all(5000)) {
            try {
                actions += runOffice(o, today);
            } catch (RuntimeException e) {
                log.warn("Cobrança: falha no escritório {}", o.getId(), e);
            }
        }
        return actions;
    }

    int runOffice(Office o, LocalDate today) {
        int n = 0;
        if ("TRIAL".equals(o.getSubscriptionStatus()) && o.getTrialEndsAt() != null && o.getTrialEndsAt().isBefore(today)) {
            o.setSubscriptionStatus("ATIVA");
            if (o.getNextInvoiceDate() == null) {
                o.setNextInvoiceDate(today);
            }
            offices.save(o);
            license.evict(o.getId());
            notifyAdmins(o.getId(), "Período de avaliação encerrado", "A avaliação gratuita terminou e a assinatura do plano "
                    + o.getPlan() + " começou. Veja os detalhes em Plano e Assinatura.");
            n++;
        }
        if (BILLABLE.contains(o.getSubscriptionStatus()) && o.getNextInvoiceDate() != null && !o.getNextInvoiceDate().isAfter(today)) {
            if (issue(o, o.getNextInvoiceDate()) != null) {
                n++;
            }
        }
        int grace = o.getGraceDays() == null ? 10 : o.getGraceDays();
        for (SubscriptionInvoice i : list(o.getId(), null)) {
            if (i.getStatus() == SubscriptionInvoice.Status.ABERTA && i.getDueDate().isBefore(today)) {
                i.setStatus(SubscriptionInvoice.Status.VENCIDA);
                invoices.save(i);
                Office fresh = office(o.getId());
                if (!"SUSPENSA".equals(fresh.getSubscriptionStatus())) {
                    fresh.setSubscriptionStatus("EM_ATRASO");
                    offices.save(fresh);
                }
                notifyAdmins(o.getId(), "Fatura vencida", "A fatura " + i.getNumber() + " venceu em " + i.getDueDate().format(BR)
                        + ". O acesso será suspenso após " + grace + " dia(s) de tolerância.");
                n++;
            }
            if (i.getStatus() == SubscriptionInvoice.Status.VENCIDA && i.getDueDate().plusDays(grace).isBefore(today)) {
                Office fresh = office(o.getId());
                if (!OfficeStatusCache.SUSPENDED.equals(fresh.getStatus())) {
                    fresh.setStatus(OfficeStatusCache.SUSPENDED);
                    fresh.setBillingSuspended(true);
                    fresh.setSubscriptionStatus("SUSPENSA");
                    fresh.setSuspendedAt(Instant.now());
                    fresh.setSuspendedReason("Fatura " + i.getNumber() + " em atraso");
                    offices.save(fresh);
                    officeStatus.evict(o.getId());
                    audit.record(null, o.getId(), "OFFICE_SUSPENDED", "office", o.getId(), "Suspensão automática: fatura "
                            + i.getNumber() + " em atraso", Map.of());
                    n++;
                }
            }
        }
        return n;
    }

    /** Emite a fatura do período que começa em {@code start}; nulo quando não há preço definido. */
    SubscriptionInvoice issue(Office o, LocalDate start) {
        Plan p = license.plan(o.getPlan()).orElse(null);
        Long amount = price(o, p);
        if (amount == null || amount <= 0) {
            return null;
        }
        boolean yearly = "ANUAL".equals(o.getBillingCycle());
        LocalDate end = (yearly ? start.plusYears(1) : start.plusMonths(1)).minusDays(1);
        SubscriptionInvoice i = new SubscriptionInvoice();
        i.setId(UUID.randomUUID().toString());
        i.setOfficeId(o.getId());
        i.setNumber(invoices.nextNumber(start.getYear()));
        i.setOfficeName(o.getName());
        i.setPlanCode(o.getPlan());
        i.setPlanName(p == null ? o.getPlan() : p.getName());
        i.setCycle(yearly ? "ANUAL" : "MENSAL");
        i.setPeriodStart(start);
        i.setPeriodEnd(end);
        i.setAmountCents(amount);
        i.setDueDate(start.plusDays(DUE_IN_DAYS));
        i.setCreatedAt(Instant.now());
        invoices.save(i);
        Office fresh = office(o.getId());
        fresh.setNextInvoiceDate(end.plusDays(1));
        offices.save(fresh);
        notifyAdmins(o.getId(), "Nova fatura disponível", "Fatura " + i.getNumber() + " de " + brl(amount) + ", vencimento em "
                + i.getDueDate().format(BR) + ".");
        return i;
    }

    private void notifyAdmins(String officeId, String title, String body) {
        for (UserProfile u : users.activeStaff(officeId)) {
            if (resolver.resolve(u).contains(Permission.SETTINGS_MANAGE)) {
                notifications.notify(officeId, u.getId(), "BILLING", title, body, "/plano");
            }
        }
    }

    // ------------------------------------------------------------------ PDF

    public ReportExporter.Exported pdf(SubscriptionInvoice i) {
        BillingSettings s = settings();
        StringBuilder h = new StringBuilder();
        h.append("<h1>FATURA ").append(esc(i.getNumber())).append("</h1>");
        if (i.getStatus() == SubscriptionInvoice.Status.PAGA) {
            h.append("<p style=\"text-align: center;\"><strong>PAGA em ").append(i.getPaidDate().format(BR)).append("</strong></p>");
        } else if (i.getStatus() == SubscriptionInvoice.Status.CANCELADA) {
            h.append("<p style=\"text-align: center;\"><strong>CANCELADA</strong></p>");
        }
        h.append("<p><strong>Emitente:</strong> ").append(esc(s.getIssuerName() == null ? "ERPAdv" : s.getIssuerName()))
                .append(s.getIssuerDocument() == null ? "" : " — " + esc(s.getIssuerDocument())).append("</p>")
                .append("<p><strong>Cliente:</strong> ").append(esc(i.getOfficeName())).append("</p>")
                .append("<p><strong>Plano:</strong> ").append(esc(i.getPlanName())).append(" (").append(i.getCycle().toLowerCase(Locale.ROOT)).append(")</p>")
                .append("<p><strong>Período:</strong> ").append(i.getPeriodStart().format(BR)).append(" a ").append(i.getPeriodEnd().format(BR)).append("</p>")
                .append("<p><strong>Valor:</strong> ").append(esc(brl(i.getAmountCents()))).append(" (").append(esc(ValorExtenso.reais(i.getAmountCents())))
                .append(")</p><p><strong>Vencimento:</strong> ").append(i.getDueDate().format(BR)).append("</p><p><br></p>");
        if (i.getStatus() != SubscriptionInvoice.Status.PAGA && i.getStatus() != SubscriptionInvoice.Status.CANCELADA) {
            h.append("<h2>Como pagar</h2>");
            if (s.getPixKey() != null) {
                h.append("<p>Pix: <strong>").append(esc(s.getPixKey())).append("</strong></p>");
            }
            if (s.getBankInfo() != null) {
                h.append("<p>").append(esc(s.getBankInfo())).append("</p>");
            }
            if (s.getInstructions() != null) {
                h.append("<p>").append(esc(s.getInstructions())).append("</p>");
            }
            if (s.getContactEmail() != null) {
                h.append("<p>Envie o comprovante para ").append(esc(s.getContactEmail())).append(" informando o número da fatura.</p>");
            }
        }
        byte[] pdf = HtmlDocument.toPdf(h.toString(), new HtmlDocument.Layout("ERPAdv — Assinatura", i.getNumber(), false, null));
        return new ReportExporter.Exported(pdf, "fatura-" + i.getNumber() + ".pdf", ReportExporter.PDF);
    }

    public SubscriptionInvoice invoiceOf(AuthenticatedUser user, String id) {
        SubscriptionInvoice i = invoices.find(id).orElseThrow(() -> new NotFoundException("Fatura"));
        if (!user.has(Permission.PLATFORM_ADMIN) && !i.getOfficeId().equals(user.officeId())) {
            throw new NotFoundException("Fatura");
        }
        return i;
    }

    private static String brl(Long cents) {
        return cents == null ? "—" : NumberFormat.getCurrencyInstance(PT_BR).format(cents / 100.0).replace(' ', ' ');
    }

    private static String esc(String s) {
        return HtmlDocument.escape(s == null ? "" : s);
    }
}
