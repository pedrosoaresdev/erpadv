package br.com.erpadv.services;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.dto.CursorPage;
import br.com.erpadv.dto.DeadlineDtos;
import br.com.erpadv.dto.DeadlineDtos.DeadlineRequest;
import br.com.erpadv.dto.DeadlineDtos.DeadlineResponse;
import br.com.erpadv.dto.DeadlineDtos.DeadlineStats;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.ForbiddenException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.Client;
import br.com.erpadv.models.CountingMode;
import br.com.erpadv.models.Deadline;
import br.com.erpadv.models.DeadlineStatus;
import br.com.erpadv.models.LegalCase;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.models.UserStatus;
import br.com.erpadv.repositories.ClientRepository;
import br.com.erpadv.repositories.DeadlineRepository;
import br.com.erpadv.repositories.UserRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Permission;
import br.com.erpadv.security.Role;
import br.com.erpadv.services.deadlines.DeadlineCalculator;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.AggregateQuerySnapshot;
import com.google.cloud.firestore.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Controle de prazos: cadastro, contagem sugerida, validação pelo responsável, conclusão e indicadores. */
@Service
public class DeadlineService {

    public enum View { PENDENTES, HOJE, URGENTES, FUTUROS, VENCIDOS, CONCLUIDOS, CANCELADOS, TODOS }

    private final DeadlineRepository deadlines;
    private final CaseService cases;
    private final ClientRepository clients;
    private final UserRepository users;
    private final CourtCalendarService calendar;
    private final AuditService audit;
    private final NotificationService notifications;

    public DeadlineService(DeadlineRepository deadlines, CaseService cases, ClientRepository clients,
                           UserRepository users, CourtCalendarService calendar, AuditService audit,
                           NotificationService notifications) {
        this.deadlines = deadlines;
        this.cases = cases;
        this.clients = clients;
        this.users = users;
        this.calendar = calendar;
        this.audit = audit;
        this.notifications = notifications;
    }

    public static LocalDate today() {
        return LocalDate.now(CourtCalendarService.ZONE);
    }

    public CursorPage<DeadlineResponse> list(AuthenticatedUser user, View view, String responsibleUid, String caseId,
                                             String cursor, int size) {
        Query q = baseQuery(user, responsibleUid, caseId);
        LocalDate today = today();
        String t = today.toString();
        Query.Direction dir = Query.Direction.ASCENDING;
        switch (view == null ? View.PENDENTES : view) {
            case PENDENTES -> q = q.whereEqualTo("status", DeadlineStatus.PENDENTE.name());
            case HOJE -> q = q.whereEqualTo("status", DeadlineStatus.PENDENTE.name()).whereEqualTo("dueDate", t);
            case URGENTES -> q = q.whereEqualTo("status", DeadlineStatus.PENDENTE.name())
                    .whereGreaterThanOrEqualTo("dueDate", t)
                    .whereLessThanOrEqualTo("dueDate", today.plusDays(DeadlineDtos.URGENT_DAYS).toString());
            case FUTUROS -> q = q.whereEqualTo("status", DeadlineStatus.PENDENTE.name())
                    .whereGreaterThan("dueDate", today.plusDays(DeadlineDtos.URGENT_DAYS).toString());
            case VENCIDOS -> q = q.whereEqualTo("status", DeadlineStatus.PENDENTE.name()).whereLessThan("dueDate", t);
            case CONCLUIDOS -> {
                q = q.whereEqualTo("status", DeadlineStatus.CONCLUIDO.name());
                dir = Query.Direction.DESCENDING;
            }
            case CANCELADOS -> {
                q = q.whereEqualTo("status", DeadlineStatus.CANCELADO.name());
                dir = Query.Direction.DESCENDING;
            }
            case TODOS -> dir = Query.Direction.DESCENDING;
        }
        return deadlines.page(q, "dueDate", dir, cursor, size).map(d -> DeadlineResponse.of(d, today));
    }

    public DeadlineStats stats(AuthenticatedUser user, String responsibleUid) {
        Query base = baseQuery(user, responsibleUid, null);
        LocalDate today = today();
        String t = today.toString();
        String urgentLimit = today.plusDays(DeadlineDtos.URGENT_DAYS).toString();
        Query pending = base.whereEqualTo("status", DeadlineStatus.PENDENTE.name());
        List<ApiFuture<AggregateQuerySnapshot>> f = new ArrayList<>();
        f.add(pending.whereEqualTo("dueDate", t).count().get());
        f.add(pending.whereGreaterThan("dueDate", t).whereLessThanOrEqualTo("dueDate", urgentLimit).count().get());
        f.add(pending.whereGreaterThan("dueDate", urgentLimit).count().get());
        f.add(pending.whereLessThan("dueDate", t).count().get());
        f.add(base.whereEqualTo("status", DeadlineStatus.CONCLUIDO.name()).count().get());
        f.add(pending.whereEqualTo("validated", false).count().get());
        long[] v = f.stream().mapToLong(x -> Futures.await(x).getCount()).toArray();
        return new DeadlineStats(v[0], v[1], v[2], v[3], v[4], v[5]);
    }

    public DeadlineResponse get(AuthenticatedUser user, String id) {
        return DeadlineResponse.of(load(user, id), today());
    }

    public DeadlineResponse create(AuthenticatedUser user, DeadlineRequest req) {
        String officeId = user.requireOffice();
        Deadline d = new Deadline();
        apply(user, d, req);
        d.setStatus(DeadlineStatus.PENDENTE);
        Instant now = Instant.now();
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        d.setCreatedBy(user.uid());
        d.setUpdatedBy(user.uid());
        deadlines.save(officeId, d);
        audit.record(user, "DEADLINE_CREATED", "deadline", d.getId(),
                "Prazo cadastrado: " + d.getDescription() + " — vence em " + d.getDueDate(),
                Map.of("caseId", nullToEmpty(d.getCaseId()), "dueDate", d.getDueDate().toString()));
        notifyResponsible(user, d, "Novo prazo sob sua responsabilidade");
        return DeadlineResponse.of(d, today());
    }

    public DeadlineResponse update(AuthenticatedUser user, String id, DeadlineRequest req) {
        Deadline d = load(user, id);
        if (d.getStatus() != DeadlineStatus.PENDENTE) {
            throw new BusinessException("DEADLINE_CLOSED", "Reabra o prazo para editá-lo.");
        }
        LocalDate previousDue = d.getDueDate();
        String previousResponsible = d.getResponsibleUid();
        apply(user, d, req);
        if (!Objects.equals(previousDue, d.getDueDate())) {
            // Nova data exige nova conferência e reabre os alertas.
            d.setValidated(false);
            d.setValidatedAt(null);
            d.setValidatedBy(null);
            d.setValidatedByName(null);
            d.setAlertsSent(new ArrayList<>());
        }
        touch(user, d);
        deadlines.save(user.officeId(), d);
        audit.record(user, "DEADLINE_UPDATED", "deadline", id, "Prazo atualizado: " + d.getDescription(),
                Map.of("dueDate", previousDue + "->" + d.getDueDate()));
        if (!d.getResponsibleUid().equals(previousResponsible)) {
            notifyResponsible(user, d, "Prazo transferido para você");
        }
        return DeadlineResponse.of(d, today());
    }

    public DeadlineResponse validate(AuthenticatedUser user, String id) {
        Deadline d = load(user, id);
        boolean privileged = user.role() == Role.SOCIO_ADMIN || user.role() == Role.MASTER;
        if (!user.uid().equals(d.getResponsibleUid()) && !privileged) {
            throw new ForbiddenException("VALIDATION_DENIED", "Somente o advogado responsável valida a contagem do prazo.");
        }
        if (d.getStatus() != DeadlineStatus.PENDENTE) {
            throw new BusinessException("DEADLINE_CLOSED", "Somente prazos pendentes podem ser validados.");
        }
        d.setValidated(true);
        d.setValidatedAt(Instant.now());
        d.setValidatedBy(user.uid());
        d.setValidatedByName(user.displayName());
        touch(user, d);
        deadlines.save(user.officeId(), d);
        audit.record(user, "DEADLINE_VALIDATED", "deadline", id, "Contagem validada — vencimento " + d.getDueDate());
        return DeadlineResponse.of(d, today());
    }

    public DeadlineResponse complete(AuthenticatedUser user, String id, String note) {
        Deadline d = load(user, id);
        if (d.getStatus() != DeadlineStatus.PENDENTE) {
            throw new BusinessException("DEADLINE_CLOSED", "Prazo já encerrado.");
        }
        d.setStatus(DeadlineStatus.CONCLUIDO);
        d.setCompletedAt(Instant.now());
        d.setCompletedBy(user.uid());
        d.setCompletedByName(user.displayName());
        if (note != null && !note.isBlank()) {
            d.setNotes((d.getNotes() == null ? "" : d.getNotes() + "\n") + "Cumprimento: " + note.trim());
        }
        touch(user, d);
        deadlines.save(user.officeId(), d);
        audit.record(user, "DEADLINE_COMPLETED", "deadline", id, "Prazo cumprido: " + d.getDescription());
        return DeadlineResponse.of(d, today());
    }

    public DeadlineResponse cancel(AuthenticatedUser user, String id, String reason) {
        Deadline d = load(user, id);
        if (d.getStatus() != DeadlineStatus.PENDENTE) {
            throw new BusinessException("DEADLINE_CLOSED", "Prazo já encerrado.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("REASON_REQUIRED", "Informe o motivo do cancelamento.");
        }
        d.setStatus(DeadlineStatus.CANCELADO);
        d.setNotes((d.getNotes() == null ? "" : d.getNotes() + "\n") + "Cancelamento: " + reason.trim());
        touch(user, d);
        deadlines.save(user.officeId(), d);
        audit.record(user, "DEADLINE_CANCELED", "deadline", id, "Prazo cancelado: " + d.getDescription(),
                Map.of("reason", reason.trim()));
        return DeadlineResponse.of(d, today());
    }

    public DeadlineResponse reopen(AuthenticatedUser user, String id) {
        Deadline d = load(user, id);
        if (d.getStatus() == DeadlineStatus.PENDENTE) {
            throw new BusinessException("DEADLINE_OPEN", "Prazo já está pendente.");
        }
        d.setStatus(DeadlineStatus.PENDENTE);
        d.setCompletedAt(null);
        d.setCompletedBy(null);
        d.setCompletedByName(null);
        touch(user, d);
        deadlines.save(user.officeId(), d);
        audit.record(user, "DEADLINE_REOPENED", "deadline", id, "Prazo reaberto: " + d.getDescription());
        return DeadlineResponse.of(d, today());
    }

    public Deadline load(AuthenticatedUser user, String id) {
        Deadline d = deadlines.findById(user.requireOffice(), id).orElseThrow(() -> new NotFoundException("Prazo"));
        if (d.isRestricted() && !user.has(Permission.CRIMINAL_READ)) {
            throw new NotFoundException("Prazo");
        }
        return d;
    }

    // ------------------------------------------------------------------ helpers

    private Query baseQuery(AuthenticatedUser user, String responsibleUid, String caseId) {
        Query q = deadlines.collection(user.requireOffice());
        if (!user.has(Permission.CRIMINAL_READ)) {
            q = q.whereEqualTo("restricted", false);
        }
        if (responsibleUid != null && !responsibleUid.isBlank()) {
            q = q.whereEqualTo("responsibleUid", responsibleUid);
        }
        if (caseId != null && !caseId.isBlank()) {
            q = q.whereEqualTo("caseId", caseId);
        }
        return q;
    }

    private void apply(AuthenticatedUser user, Deadline d, DeadlineRequest req) {
        String officeId = user.officeId();
        String court = trim(req.court());
        boolean restricted = false;
        if (req.caseId() != null && !req.caseId().isBlank()) {
            LegalCase c = cases.load(user, req.caseId());
            if (c.isRestricted() && !user.has(Permission.CRIMINAL_WRITE)) {
                throw new ForbiddenException("CRIMINAL_ACCESS_DENIED", "Sem permissão para prazos de processo restrito.");
            }
            d.setCaseId(c.getId());
            d.setCaseNumber(c.getCnjNumber());
            d.setClientId(c.getClientId());
            d.setClientName(c.getClientName());
            restricted = c.isRestricted();
            if (court == null) {
                court = c.getCourt();
            }
        } else if (req.clientId() != null && !req.clientId().isBlank()) {
            Client client = clients.findById(officeId, req.clientId())
                    .orElseThrow(() -> new BusinessException("INVALID_CLIENT", "Cliente não encontrado."));
            d.setCaseId(null);
            d.setCaseNumber(null);
            d.setClientId(client.getId());
            d.setClientName(client.getName());
        } else {
            d.setCaseId(null);
            d.setCaseNumber(null);
            d.setClientId(null);
            d.setClientName(null);
        }
        UserProfile responsible = users.findInOffice(officeId, req.responsibleUid())
                .filter(u -> u.getRole() != null && u.getRole().isStaff() && u.getStatus() != UserStatus.BLOCKED)
                .orElseThrow(() -> new BusinessException("INVALID_RESPONSIBLE", "Responsável inválido."));

        LocalDate due;
        if (req.countingMode() == CountingMode.MANUAL) {
            if (req.dueDate() == null) {
                throw new BusinessException("DUE_DATE_REQUIRED", "Informe a data de vencimento.");
            }
            due = req.dueDate();
        } else {
            if (req.days() == null) {
                throw new BusinessException("DAYS_REQUIRED", "Informe a quantidade de dias do prazo.");
            }
            DeadlineCalculator.Result result = calendar.calculator(officeId, court)
                    .calculate(req.startDate(), req.days(), req.countingMode());
            due = result.dueDate();
        }
        if (due.isBefore(req.startDate())) {
            throw new BusinessException("INVALID_DUE_DATE", "O vencimento não pode ser anterior à data inicial.");
        }
        d.setDescription(req.description().trim());
        d.setType(req.type());
        d.setStartDate(req.startDate());
        d.setDueDate(due);
        d.setDays(req.countingMode() == CountingMode.MANUAL ? null : req.days());
        d.setCountingMode(req.countingMode());
        d.setCourt(court == null ? null : court.toUpperCase());
        d.setResponsibleUid(responsible.getId());
        d.setResponsibleName(responsible.getDisplayName());
        d.setPriority(req.priority());
        d.setNotes(trim(req.notes()));
        d.setRestricted(restricted);
    }

    private void notifyResponsible(AuthenticatedUser actor, Deadline d, String title) {
        if (d.getResponsibleUid() == null || d.getResponsibleUid().equals(actor.uid())) {
            return;
        }
        notifications.notify(actor.officeId(), d.getResponsibleUid(), "DEADLINE_ASSIGNED", title,
                d.getDescription() + " — vence em " + br(d.getDueDate()) + ". Valide a contagem.",
                "/prazos?id=" + d.getId());
    }

    static String br(LocalDate date) {
        return String.format("%02d/%02d/%d", date.getDayOfMonth(), date.getMonthValue(), date.getYear());
    }

    private void touch(AuthenticatedUser user, Deadline d) {
        d.setUpdatedAt(Instant.now());
        d.setUpdatedBy(user.uid());
    }

    private static String trim(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }
}
