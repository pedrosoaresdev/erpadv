package br.com.erpadv.services;

import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.Appointment;
import br.com.erpadv.models.Deadline;
import br.com.erpadv.models.DeadlineStatus;
import br.com.erpadv.services.deadlines.AlertPolicy;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/**
 * Jobs agendados (todas as instâncias do escritório, via collection group queries):
 * <ul>
 *   <li>Alertas de prazos (10/5/3/1/0 dias) — a cada hora; idempotente por {@code alertsSent}.</li>
 *   <li>Lembretes de compromissos — a cada 5 minutos; idempotente por {@code remindersSent}.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "app.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReminderJobs {

    private static final Logger log = LoggerFactory.getLogger(ReminderJobs.class);
    private static final int BATCH = 500;

    private final Firestore db;
    private final FirestoreMapper mapper;
    private final NotificationService notifications;
    private final JobLock lock;

    public ReminderJobs(Firestore db, FirestoreMapper mapper, NotificationService notifications, JobLock lock) {
        this.db = db;
        this.mapper = mapper;
        this.notifications = notifications;
        this.lock = lock;
    }

    @Scheduled(cron = "${app.jobs.deadline-alerts-cron:0 5 * * * *}", zone = "America/Sao_Paulo")
    public void deadlineAlertsJob() {
        if (!lock.tryAcquire("deadline-alerts", Duration.ofMinutes(10))) {
            return;
        }
        try {
            int sent = sendDeadlineAlerts(DeadlineService.today());
            if (sent > 0) {
                log.info("Alertas de prazo enviados: {}", sent);
            }
        } catch (RuntimeException e) {
            log.error("Falha no job de alertas de prazo", e);
        } finally {
            lock.release("deadline-alerts");
        }
    }

    @Scheduled(fixedDelayString = "${app.jobs.appointment-reminders-ms:300000}", initialDelay = 60_000)
    public void appointmentRemindersJob() {
        if (!lock.tryAcquire("appointment-reminders", Duration.ofMinutes(4))) {
            return;
        }
        try {
            int sent = sendAppointmentReminders(Instant.now());
            if (sent > 0) {
                log.info("Lembretes de agenda enviados: {}", sent);
            }
        } catch (RuntimeException e) {
            log.error("Falha no job de lembretes de agenda", e);
        } finally {
            lock.release("appointment-reminders");
        }
    }

    /** Envia os alertas devidos em {@code today}. Retorna a quantidade de notificações. */
    public int sendDeadlineAlerts(LocalDate today) {
        List<QueryDocumentSnapshot> docs = Futures.await(db.collectionGroup("deadlines")
                .whereEqualTo("status", DeadlineStatus.PENDENTE.name())
                .whereGreaterThanOrEqualTo("dueDate", today.toString())
                .whereLessThanOrEqualTo("dueDate", today.plusDays(AlertPolicy.THRESHOLDS.get(0)).toString())
                .limit(BATCH)
                .get()).getDocuments();
        int count = 0;
        for (QueryDocumentSnapshot doc : docs) {
            Deadline d = mapper.fromSnapshot(doc, Deadline.class);
            if (d.getOfficeId() == null || d.getResponsibleUid() == null || d.getDueDate() == null) {
                continue;
            }
            long daysLeft = ChronoUnit.DAYS.between(today, d.getDueDate());
            var decision = AlertPolicy.evaluate(daysLeft, d.getAlertsSent());
            if (decision.isEmpty()) {
                continue;
            }
            String body = d.getDescription()
                    + (d.getCaseNumber() != null ? " · " + d.getCaseNumber() : "")
                    + (d.getClientName() != null ? " · " + d.getClientName() : "")
                    + (d.isValidated() ? "" : " · contagem ainda NÃO validada");
            notifications.notify(d.getOfficeId(), d.getResponsibleUid(), "DEADLINE_ALERT",
                    "Prazo " + AlertPolicy.describe(decision.get().daysLeft()), body, "/prazos?id=" + d.getId());
            Futures.await(doc.getReference().update("alertsSent",
                    FieldValue.arrayUnion(decision.get().markAsSent().toArray())));
            count++;
        }
        return count;
    }

    @Scheduled(cron = "${app.jobs.contract-expiry-cron:0 15 7 * * *}", zone = "America/Sao_Paulo")
    public void contractExpiryJob() {
        if (!lock.tryAcquire("contract-expiry", Duration.ofMinutes(10))) {
            return;
        }
        try {
            sendContractExpiryAlerts(DeadlineService.today());
        } catch (RuntimeException e) {
            log.error("Falha no job de vencimento de contratos", e);
        } finally {
            lock.release("contract-expiry");
        }
    }

    /** Avisa o advogado responsável 30, 7 e 1 dia antes e no dia do término do contrato. */
    public int sendContractExpiryAlerts(LocalDate today) {
        List<Integer> thresholds = List.of(30, 7, 1, 0);
        List<QueryDocumentSnapshot> docs = Futures.await(db.collectionGroup("contracts")
                .whereEqualTo("status", "ATIVO")
                .whereGreaterThanOrEqualTo("endDate", today.toString())
                .whereLessThanOrEqualTo("endDate", today.plusDays(30).toString())
                .limit(BATCH).get()).getDocuments();
        int count = 0;
        for (QueryDocumentSnapshot doc : docs) {
            br.com.erpadv.models.Contract c = mapper.fromSnapshot(doc, br.com.erpadv.models.Contract.class);
            if (c.getOfficeId() == null || c.getResponsibleUid() == null || c.getEndDate() == null) {
                continue;
            }
            long daysLeft = ChronoUnit.DAYS.between(today, c.getEndDate());
            var decision = AlertPolicy.evaluate(daysLeft, c.getExpiryAlertsSent(), thresholds);
            if (decision.isEmpty()) {
                continue;
            }
            notifications.notify(c.getOfficeId(), c.getResponsibleUid(), "CONTRACT_EXPIRY",
                    "Contrato " + c.getNumber() + " " + AlertPolicy.describe(decision.get().daysLeft()).replace("vence", "termina"),
                    c.getTitle() + " · " + c.getClientName() + " — avalie renovação ou encerramento", "/contratos/" + c.getId());
            Futures.await(doc.getReference().update("expiryAlertsSent",
                    FieldValue.arrayUnion(decision.get().markAsSent().toArray())));
            count++;
        }
        return count;
    }

    /** Envia lembretes de compromissos que começam nas próximas 7 dias. */
    public int sendAppointmentReminders(Instant now) {
        List<QueryDocumentSnapshot> docs = Futures.await(db.collectionGroup("appointments")
                .whereGreaterThanOrEqualTo("start", now.toEpochMilli())
                .whereLessThanOrEqualTo("start", now.plus(Duration.ofDays(7)).toEpochMilli())
                .limit(BATCH)
                .get()).getDocuments();
        int count = 0;
        for (QueryDocumentSnapshot doc : docs) {
            Appointment a = mapper.fromSnapshot(doc, Appointment.class);
            if (a.getOfficeId() == null || a.getReminders() == null || a.getReminders().isEmpty()
                    || a.getStatus() == br.com.erpadv.models.AppointmentStatus.CANCELADO
                    || a.getStatus() == br.com.erpadv.models.AppointmentStatus.ADIADO) {
                continue;
            }
            long minutes = Duration.between(now, a.getStart()).toMinutes();
            Set<Integer> due = AlertPolicy.dueReminders(minutes, a.getReminders(), a.getRemindersSent());
            if (due.isEmpty()) {
                continue;
            }
            for (String uid : a.getResponsibleUids()) {
                notifications.notify(a.getOfficeId(), uid, "APPOINTMENT_REMINDER",
                        "Lembrete: " + a.getType().label(),
                        a.getTitle() + " — " + NotificationService.format(a.getStart())
                                + (a.getLocation() != null ? " · " + a.getLocation() : ""),
                        "/agenda?evento=" + a.getId());
                count++;
            }
            Futures.await(doc.getReference().update("remindersSent", FieldValue.arrayUnion(due.toArray())));
        }
        return count;
    }
}
