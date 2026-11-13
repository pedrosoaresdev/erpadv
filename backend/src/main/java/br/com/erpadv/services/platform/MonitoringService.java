package br.com.erpadv.services.platform;

import br.com.erpadv.config.FirestoreHealthIndicator;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.Office;
import br.com.erpadv.models.Plan;
import br.com.erpadv.models.SubscriptionInvoice;
import br.com.erpadv.models.SupportTicket;
import br.com.erpadv.models.UserStatus;
import br.com.erpadv.repositories.OfficeRepository;
import br.com.erpadv.repositories.UserRepository;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Monitoramento da plataforma (painel master): escritórios, receita recorrente, faturas, uso, suporte e saúde do sistema. */
@Service
public class MonitoringService {

    public record Alert(String officeId, String officeName, String kind, String message) {
    }

    public record Metrics(long offices, Map<String, Long> byStatus, Map<String, Long> bySubscription, Map<String, Long> byPlan,
                          long mrrCents, long openInvoicesCents, long overdueInvoices, long overdueInvoicesCents, long users,
                          long activeUsers7d, long storageBytes, long ticketsAwaiting, List<Alert> alerts, String health,
                          long uptimeMinutes, Instant generatedAt) {
    }

    private final OfficeRepository offices;
    private final UserRepository users;
    private final BillingService billing;
    private final SupportService support;
    private final LicenseService license;
    private final FirestoreHealthIndicator firestoreHealth;
    private final Firestore db;

    public MonitoringService(OfficeRepository offices, UserRepository users, BillingService billing, SupportService support,
                             LicenseService license, FirestoreHealthIndicator firestoreHealth, Firestore db) {
        this.offices = offices;
        this.users = users;
        this.billing = billing;
        this.support = support;
        this.license = license;
        this.firestoreHealth = firestoreHealth;
        this.db = db;
    }

    public Metrics metrics() {
        List<Office> all = offices.all(5000);
        Map<String, Plan> plans = new TreeMap<>();
        license.plans().forEach(p -> plans.put(p.getCode(), p));
        Map<String, Long> byStatus = new TreeMap<>();
        Map<String, Long> bySub = new TreeMap<>();
        Map<String, Long> byPlan = new TreeMap<>();
        long mrr = 0;
        long storage = 0;
        List<Alert> alerts = new ArrayList<>();
        LocalDate today = BillingService.today();
        for (Office o : all) {
            byStatus.merge(o.getStatus() == null ? "ACTIVE" : o.getStatus(), 1L, Long::sum);
            bySub.merge(o.getSubscriptionStatus() == null ? "SEM_ASSINATURA" : o.getSubscriptionStatus(), 1L, Long::sum);
            byPlan.merge(o.getPlan() == null ? "—" : o.getPlan(), 1L, Long::sum);
            storage += o.getStorageBytes() == null ? 0 : o.getStorageBytes();
            Plan p = plans.get(o.getPlan());
            if ("ATIVA".equals(o.getSubscriptionStatus()) || "EM_ATRASO".equals(o.getSubscriptionStatus())) {
                Long price = billing.price(o, p);
                if (price != null) {
                    mrr += "ANUAL".equals(o.getBillingCycle()) ? price / 12 : price;
                }
            }
            if ("TRIAL".equals(o.getSubscriptionStatus()) && o.getTrialEndsAt() != null && !o.getTrialEndsAt().isAfter(today.plusDays(7))) {
                alerts.add(new Alert(o.getId(), o.getName(), "TRIAL", "Avaliação termina em " + o.getTrialEndsAt()));
            }
            LicenseService.License l = license.license(o.getId());
            if (l.maxStorageMb() != null && o.getStorageBytes() != null && o.getStorageBytes() >= 0.9 * l.maxStorageMb() * 1024 * 1024) {
                alerts.add(new Alert(o.getId(), o.getName(), "STORAGE", "Armazenamento acima de 90% do plano"));
            }
            if (p != null && o.getSubscriptionStatus() != null && ("ATIVA".equals(o.getSubscriptionStatus()) || "EM_ATRASO".equals(o.getSubscriptionStatus()))
                    && billing.price(o, p) == null) {
                alerts.add(new Alert(o.getId(), o.getName(), "NO_PRICE", "Plano " + p.getCode() + " sem preço definido: nenhuma fatura será emitida"));
            }
        }
        long open = 0;
        long overdue = 0;
        long overdueCents = 0;
        for (SubscriptionInvoice i : billing.list(null, null)) {
            if (i.getStatus() == SubscriptionInvoice.Status.ABERTA) {
                open += i.getAmountCents();
            } else if (i.getStatus() == SubscriptionInvoice.Status.VENCIDA) {
                overdue++;
                overdueCents += i.getAmountCents();
            }
        }
        long active7 = Futures.await(db.collection("users")
                .whereGreaterThanOrEqualTo("lastLoginAt", Instant.now().minus(Duration.ofDays(7)).toEpochMilli()).count().get()).getCount();
        long awaiting = support.all(null).stream().filter(t -> t.isAwaitingPlatform() && t.getStatus() != SupportTicket.Status.RESOLVIDO).count();
        String health;
        try {
            health = firestoreHealth.health().getStatus().getCode();
        } catch (RuntimeException e) {
            health = "DOWN";
        }
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime() / 60_000;
        return new Metrics(all.size(), byStatus, bySub, byPlan, mrr, open, overdue, overdueCents, users.countAll(UserStatus.ACTIVE), active7,
                storage, awaiting, alerts, health, uptime, Instant.now());
    }
}
