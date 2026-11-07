package br.com.erpadv.services;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.dto.CalendarDtos.AppointmentRequest;
import br.com.erpadv.dto.CalendarDtos.AppointmentResponse;
import br.com.erpadv.dto.CalendarDtos.CalendarEvent;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.ForbiddenException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.models.Appointment;
import br.com.erpadv.models.Client;
import br.com.erpadv.models.Deadline;
import br.com.erpadv.models.DeadlineStatus;
import br.com.erpadv.models.LegalCase;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.models.UserStatus;
import br.com.erpadv.repositories.AppointmentRepository;
import br.com.erpadv.repositories.ClientRepository;
import br.com.erpadv.repositories.DeadlineRepository;
import br.com.erpadv.repositories.UserRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Permission;
import com.google.cloud.firestore.Query;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Agenda jurídica: audiências, reuniões, julgamentos etc., e visão unificada com os prazos. */
@Service
public class CalendarService {

    private static final Duration MAX_RANGE = Duration.ofDays(62);
    private static final Duration MAX_EVENT = Duration.ofDays(31);
    private static final int MAX_ITEMS = 500;

    private final AppointmentRepository appointments;
    private final DeadlineRepository deadlines;
    private final CaseService cases;
    private final ClientRepository clients;
    private final UserRepository users;
    private final AuditService audit;
    private final NotificationService notifications;

    public CalendarService(AppointmentRepository appointments, DeadlineRepository deadlines, CaseService cases,
                           ClientRepository clients, UserRepository users, AuditService audit,
                           NotificationService notifications) {
        this.appointments = appointments;
        this.deadlines = deadlines;
        this.cases = cases;
        this.clients = clients;
        this.users = users;
        this.audit = audit;
        this.notifications = notifications;
    }

    /** Eventos de [from, to): compromissos que começam no intervalo e prazos pendentes/concluídos que vencem nele. */
    public List<CalendarEvent> events(AuthenticatedUser user, Instant from, Instant to, boolean mineOnly,
                                      boolean includeDeadlines) {
        String officeId = user.requireOffice();
        if (from == null || to == null || !to.isAfter(from) || Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new BusinessException("INVALID_RANGE", "Intervalo inválido (máximo de 62 dias).");
        }
        boolean seeRestricted = user.has(Permission.CRIMINAL_READ);
        List<CalendarEvent> result = new ArrayList<>();

        if (user.has(Permission.CALENDAR_READ)) {
            Query q = appointments.collection(officeId);
            if (!seeRestricted) {
                q = q.whereEqualTo("restricted", false);
            }
            if (mineOnly) {
                q = q.whereArrayContains("responsibleUids", user.uid());
            }
            q = q.whereGreaterThanOrEqualTo("start", from.toEpochMilli()).whereLessThan("start", to.toEpochMilli())
                    .orderBy("start");
            for (Appointment a : appointments.list(q, MAX_ITEMS)) {
                result.add(new CalendarEvent(a.getId(), "APPOINTMENT", a.getType().name(), a.getType().label(),
                        a.getTitle(), a.getStart(), a.getEnd(), a.isAllDay(), null, a.getLocation(), a.getCaseId(),
                        a.getCaseNumber(), a.getClientName(), a.getResponsibleNames(), null, null));
            }
        }

        if (includeDeadlines && user.has(Permission.DEADLINES_READ)) {
            LocalDate fromDay = from.atZone(CourtCalendarService.ZONE).toLocalDate();
            LocalDate toDay = to.atZone(CourtCalendarService.ZONE).toLocalDate();
            Query q = deadlines.collection(officeId);
            if (!seeRestricted) {
                q = q.whereEqualTo("restricted", false);
            }
            if (mineOnly) {
                q = q.whereEqualTo("responsibleUid", user.uid());
            }
            q = q.whereIn("status", List.of(DeadlineStatus.PENDENTE.name(), DeadlineStatus.CONCLUIDO.name()))
                    .whereGreaterThanOrEqualTo("dueDate", fromDay.toString())
                    .whereLessThanOrEqualTo("dueDate", toDay.toString())
                    .orderBy("dueDate");
            for (Deadline d : deadlines.list(q, MAX_ITEMS)) {
                Instant start = d.getDueDate().atStartOfDay(CourtCalendarService.ZONE).toInstant();
                result.add(new CalendarEvent(d.getId(), "DEADLINE", d.getType().name(), "Prazo · " + d.getType().label(),
                        d.getDescription(), start, start.plus(Duration.ofDays(1)), true, d.getStatus().name(), null,
                        d.getCaseId(), d.getCaseNumber(), d.getClientName(), List.of(d.getResponsibleName()),
                        d.getPriority().name(), d.isValidated()));
            }
        }
        result.sort(Comparator.comparing(CalendarEvent::start));
        return result;
    }

    public AppointmentResponse get(AuthenticatedUser user, String id) {
        return AppointmentResponse.of(load(user, id));
    }

    public AppointmentResponse create(AuthenticatedUser user, AppointmentRequest req) {
        String officeId = user.requireOffice();
        Appointment a = new Appointment();
        apply(user, a, req);
        Instant now = Instant.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        a.setCreatedBy(user.uid());
        a.setUpdatedBy(user.uid());
        appointments.save(officeId, a);
        audit.record(user, "APPOINTMENT_CREATED", "appointment", a.getId(),
                a.getType().label() + ": " + a.getTitle(), Map.of("start", a.getStart().toString()));
        notifyResponsibles(user, a, a.getResponsibleUids(), "Novo compromisso na sua agenda");
        return AppointmentResponse.of(a);
    }

    public AppointmentResponse update(AuthenticatedUser user, String id, AppointmentRequest req) {
        Appointment a = load(user, id);
        List<String> previous = new ArrayList<>(a.getResponsibleUids());
        Instant previousStart = a.getStart();
        apply(user, a, req);
        if (!previousStart.equals(a.getStart())) {
            a.setRemindersSent(new ArrayList<>());
        }
        a.setUpdatedAt(Instant.now());
        a.setUpdatedBy(user.uid());
        appointments.save(user.officeId(), a);
        audit.record(user, "APPOINTMENT_UPDATED", "appointment", id, a.getType().label() + ": " + a.getTitle());
        List<String> added = a.getResponsibleUids().stream().filter(uid -> !previous.contains(uid)).toList();
        notifyResponsibles(user, a, added, "Você foi incluído em um compromisso");
        if (!previousStart.equals(a.getStart())) {
            notifyResponsibles(user, a, previous.stream().filter(a.getResponsibleUids()::contains).toList(),
                    "Compromisso remarcado");
        }
        return AppointmentResponse.of(a);
    }

    public void delete(AuthenticatedUser user, String id) {
        Appointment a = load(user, id);
        appointments.delete(user.officeId(), id);
        audit.record(user, "APPOINTMENT_DELETED", "appointment", id, "Excluído: " + a.getType().label() + " — "
                + a.getTitle(), Map.of("start", a.getStart().toString()));
        notifyResponsibles(user, a, a.getResponsibleUids(), "Compromisso cancelado");
    }

    public Appointment load(AuthenticatedUser user, String id) {
        Appointment a = appointments.findById(user.requireOffice(), id)
                .orElseThrow(() -> new NotFoundException("Compromisso"));
        if (a.isRestricted() && !user.has(Permission.CRIMINAL_READ)) {
            throw new NotFoundException("Compromisso");
        }
        return a;
    }

    // ------------------------------------------------------------------ helpers

    private void apply(AuthenticatedUser user, Appointment a, AppointmentRequest req) {
        String officeId = user.officeId();
        if (!req.end().isAfter(req.start()) && !(req.allDay() && !req.end().isBefore(req.start()))) {
            throw new BusinessException("INVALID_PERIOD", "O término deve ser posterior ao início.");
        }
        if (Duration.between(req.start(), req.end()).compareTo(MAX_EVENT) > 0) {
            throw new BusinessException("INVALID_PERIOD", "Duração máxima de 31 dias.");
        }
        boolean restricted = false;
        if (req.caseId() != null && !req.caseId().isBlank()) {
            LegalCase c = cases.load(user, req.caseId());
            if (c.isRestricted() && !user.has(Permission.CRIMINAL_WRITE)) {
                throw new ForbiddenException("CRIMINAL_ACCESS_DENIED", "Sem permissão para eventos de processo restrito.");
            }
            a.setCaseId(c.getId());
            a.setCaseNumber(c.getCnjNumber());
            a.setClientId(c.getClientId());
            a.setClientName(c.getClientName());
            restricted = c.isRestricted();
        } else if (req.clientId() != null && !req.clientId().isBlank()) {
            Client client = clients.findById(officeId, req.clientId())
                    .orElseThrow(() -> new BusinessException("INVALID_CLIENT", "Cliente não encontrado."));
            a.setCaseId(null);
            a.setCaseNumber(null);
            a.setClientId(client.getId());
            a.setClientName(client.getName());
        } else {
            a.setCaseId(null);
            a.setCaseNumber(null);
            a.setClientId(null);
            a.setClientName(null);
        }
        List<String> uids = new ArrayList<>(new LinkedHashSet<>(req.responsibleUids()));
        List<String> names = new ArrayList<>();
        for (String uid : uids) {
            UserProfile p = users.findInOffice(officeId, uid)
                    .filter(u -> u.getRole() != null && u.getRole().isStaff() && u.getStatus() != UserStatus.BLOCKED)
                    .orElseThrow(() -> new BusinessException("INVALID_RESPONSIBLE", "Responsável inválido."));
            names.add(p.getDisplayName());
        }
        a.setType(req.type());
        a.setTitle(req.title().trim());
        a.setStart(req.start());
        a.setEnd(req.end());
        a.setAllDay(req.allDay());
        a.setLocation(trim(req.location()));
        a.setMeetingUrl(trim(req.meetingUrl()));
        a.setDescription(trim(req.description()));
        a.setResponsibleUids(uids);
        a.setResponsibleNames(names);
        a.setReminders(req.reminders() == null ? new ArrayList<>()
                : new ArrayList<>(new LinkedHashSet<>(req.reminders())));
        a.setRestricted(restricted);
    }

    private void notifyResponsibles(AuthenticatedUser actor, Appointment a, List<String> uids, String title) {
        String when = NotificationService.format(a.getStart());
        for (String uid : uids) {
            if (!uid.equals(actor.uid())) {
                notifications.notify(actor.officeId(), uid, "APPOINTMENT", title,
                        a.getType().label() + ": " + a.getTitle() + " — " + when, "/agenda?evento=" + a.getId());
            }
        }
    }

    private static String trim(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
