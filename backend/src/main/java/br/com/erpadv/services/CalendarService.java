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
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private br.com.erpadv.services.integrations.GoogleCalendarService google;
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
        return events(user, from, to, mineOnly ? user.uid() : null, includeDeadlines);
    }

    /** Agenda por advogado: {@code responsibleUid} filtra compromissos e prazos de um integrante. */
    public List<CalendarEvent> events(AuthenticatedUser user, Instant from, Instant to, String responsibleUid,
                                      boolean includeDeadlines) {
        boolean mineOnly = responsibleUid != null && !responsibleUid.isBlank();
        String who = mineOnly ? responsibleUid : null;
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
                q = q.whereArrayContains("responsibleUids", who);
            }
            q = q.whereGreaterThanOrEqualTo("start", from.toEpochMilli()).whereLessThan("start", to.toEpochMilli())
                    .orderBy("start");
            for (Appointment a : appointments.list(q, MAX_ITEMS)) {
                result.add(new CalendarEvent(a.getId(), "APPOINTMENT", a.getType().name(), a.getType().label(),
                        a.getTitle(), a.getStart(), a.getEnd(), a.isAllDay(),
                        a.getStatus() == null ? null : a.getStatus().name(), a.getLocation(), a.getCaseId(),
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
                q = q.whereEqualTo("responsibleUid", who);
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
        int total = req.recurrence() == null || req.recurrence().isBlank() ? 1
                : Math.max(1, Math.min(52, req.occurrences() == null ? 1 : req.occurrences()));
        String seriesId = total > 1 ? java.util.UUID.randomUUID().toString() : null;
        Appointment first = null;
        for (int i = 0; i < total; i++) {
            Appointment a = new Appointment();
            apply(user, a, req);
            if (i > 0) {
                a.setStart(shift(req.start(), req.recurrence(), i));
                a.setEnd(shift(req.end(), req.recurrence(), i));
            }
            a.setSeriesId(seriesId);
            a.setSeriesIndex(seriesId == null ? null : i + 1);
            a.setRecurrence(seriesId == null ? null : req.recurrence());
            Instant now = Instant.now();
            a.setCreatedAt(now);
            a.setUpdatedAt(now);
            a.setCreatedBy(user.uid());
            a.setUpdatedBy(user.uid());
            appointments.save(officeId, a);
            if (first == null) {
                first = a;
            }
        }
        audit.record(user, "APPOINTMENT_CREATED", "appointment", first.getId(),
                first.getType().label() + ": " + first.getTitle() + (total > 1 ? " (" + total + " ocorrências)" : ""),
                Map.of("start", first.getStart().toString(), "recurrence", req.recurrence() == null ? "" : req.recurrence()));
        notifyResponsibles(user, first, first.getResponsibleUids(),
                total > 1 ? "Nova série de " + total + " compromissos na sua agenda" : "Novo compromisso na sua agenda");
        syncGoogle(officeId, first, seriesId);
        return AppointmentResponse.of(appointments.findById(officeId, first.getId()).orElse(first));
    }

    static Instant shift(Instant base, String recurrence, int n) {
        java.time.ZonedDateTime z = base.atZone(CourtCalendarService.ZONE);
        return switch (recurrence) {
            case "DIARIA" -> z.plusDays(n).toInstant();
            case "SEMANAL" -> z.plusWeeks(n).toInstant();
            case "QUINZENAL" -> z.plusWeeks(2L * n).toInstant();
            case "MENSAL" -> z.plusMonths(n).toInstant();
            default -> base;
        };
    }

    /** Atualiza a ocorrência e, com {@code series}, aplica os dados (exceto datas) às próximas ocorrências da série. */
    public AppointmentResponse update(AuthenticatedUser user, String id, AppointmentRequest req, boolean series) {
        AppointmentResponse updated = update(user, id, req);
        Appointment a = load(user, id);
        if (series && a.getSeriesId() != null) {
            for (Appointment o : appointments.list(appointments.collection(user.officeId())
                    .whereEqualTo("seriesId", a.getSeriesId()), 60)) {
                if (o.getId().equals(id) || o.getStart().isBefore(a.getStart())) {
                    continue;
                }
                Instant start = o.getStart();
                Instant end = o.getEnd();
                apply(user, o, req);
                o.setStart(start);
                o.setEnd(end);
                o.setUpdatedAt(Instant.now());
                o.setUpdatedBy(user.uid());
                appointments.save(user.officeId(), o);
                pushGoogle(user.officeId(), o);
            }
        }
        return updated;
    }

    public void delete(AuthenticatedUser user, String id, boolean series) {
        Appointment a = load(user, id);
        if (series && a.getSeriesId() != null) {
            int removed = 0;
            for (Appointment o : appointments.list(appointments.collection(user.officeId())
                    .whereEqualTo("seriesId", a.getSeriesId()), 60)) {
                if (!o.getStart().isBefore(a.getStart())) {
                    appointments.delete(user.officeId(), o.getId());
                    removeGoogle(user.officeId(), o);
                    removed++;
                }
            }
            audit.record(user, "APPOINTMENT_SERIES_DELETED", "appointment", id, "Série excluída a partir desta ocorrência: "
                    + a.getTitle() + " (" + removed + ")", Map.of("start", a.getStart().toString()));
            notifyResponsibles(user, a, a.getResponsibleUids(), "Série de compromissos cancelada");
            return;
        }
        delete(user, id);
    }

    // ------------------------------------------------------------------ Google Agenda

    /** Envia a série para o Google; a sala do Meet criada na primeira ocorrência vale para a série inteira. */
    private void syncGoogle(String officeId, Appointment first, String seriesId) {
        if (google == null) {
            return;
        }
        pushGoogle(officeId, first);
        String meet = appointments.findById(officeId, first.getId()).map(Appointment::getMeetingUrl).orElse(null);
        if (seriesId != null) {
            for (Appointment o : appointments.list(appointments.collection(officeId).whereEqualTo("seriesId", seriesId), 60)) {
                if (o.getId().equals(first.getId())) {
                    continue;
                }
                if (meet != null && o.getMeetingUrl() == null) {
                    o.setMeetingUrl(meet);
                    appointments.save(officeId, o);
                }
                pushGoogle(officeId, o);
            }
        }
    }

    private void pushGoogle(String officeId, Appointment a) {
        if (google == null) {
            return;
        }
        try {
            google.push(officeId, a);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(CalendarService.class).warn("Google Agenda: {}", e.getMessage());
        }
    }

    private void removeGoogle(String officeId, Appointment a) {
        if (google == null) {
            return;
        }
        try {
            google.remove(officeId, a);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(CalendarService.class).warn("Google Agenda: {}", e.getMessage());
        }
    }

    /** Confirmação de presença do integrante convidado. */
    public AppointmentResponse rsvp(AuthenticatedUser user, String id, String status) {
        Appointment a = load(user, id);
        if (!a.getResponsibleUids().contains(user.uid())) {
            throw new ForbiddenException("NOT_INVITED", "Você não está entre os participantes deste compromisso.");
        }
        java.util.Map<String, String> attendance = new java.util.HashMap<>(a.getAttendance() == null ? Map.of() : a.getAttendance());
        attendance.put(user.uid(), status);
        a.setAttendance(attendance);
        a.setUpdatedAt(Instant.now());
        appointments.save(user.officeId(), a);
        if (a.getCreatedBy() != null && !a.getCreatedBy().equals(user.uid())) {
            String label = switch (status) { case "CONFIRMADO" -> "confirmou presença"; case "RECUSADO" -> "recusou"; default -> "talvez participe"; };
            notifications.notify(user.officeId(), a.getCreatedBy(), "APPOINTMENT", user.displayName() + " " + label,
                    a.getType().label() + ": " + a.getTitle() + " — " + NotificationService.format(a.getStart()), "/agenda?evento=" + a.getId());
        }
        return AppointmentResponse.of(a);
    }

    /** Registra a situação (realizado, redesignado, adiado, cancelado) e o resultado — ex.: ata da audiência. */
    public AppointmentResponse setStatus(AuthenticatedUser user, String id, br.com.erpadv.models.AppointmentStatus status, String outcome) {
        Appointment a = load(user, id);
        br.com.erpadv.models.AppointmentStatus previous = a.getStatus() == null ? br.com.erpadv.models.AppointmentStatus.AGENDADO : a.getStatus();
        a.setStatus(status);
        a.setOutcome(trim(outcome));
        a.setStatusChangedAt(Instant.now());
        a.setUpdatedAt(Instant.now());
        a.setUpdatedBy(user.uid());
        appointments.save(user.officeId(), a);
        audit.record(user, "APPOINTMENT_STATUS_CHANGED", "appointment", id, a.getType().label() + " " + status.label().toLowerCase()
                + ": " + a.getTitle(), Map.of("status", previous.name() + "->" + status.name()));
        return AppointmentResponse.of(a);
    }

    /** Arquivo iCalendar (.ics) do compromisso — importável no Google Agenda, Outlook e Apple Calendar. */
    public String ics(AuthenticatedUser user, String id) {
        Appointment a = load(user, id);
        java.time.format.DateTimeFormatter utc = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(java.time.ZoneOffset.UTC);
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//ERPAdv//Agenda//PT-BR\r\nCALSCALE:GREGORIAN\r\nMETHOD:PUBLISH\r\n");
        sb.append("BEGIN:VEVENT\r\n");
        sb.append("UID:").append(a.getId()).append("@erpadv\r\n");
        sb.append("DTSTAMP:").append(utc.format(Instant.now())).append("\r\n");
        if (a.isAllDay()) {
            java.time.format.DateTimeFormatter day = java.time.format.DateTimeFormatter.BASIC_ISO_DATE;
            sb.append("DTSTART;VALUE=DATE:").append(a.getStart().atZone(CourtCalendarService.ZONE).toLocalDate().format(day)).append("\r\n");
            sb.append("DTEND;VALUE=DATE:").append(a.getEnd().atZone(CourtCalendarService.ZONE).toLocalDate().plusDays(1).format(day)).append("\r\n");
        } else {
            sb.append("DTSTART:").append(utc.format(a.getStart())).append("\r\n");
            sb.append("DTEND:").append(utc.format(a.getEnd())).append("\r\n");
        }
        sb.append("SUMMARY:").append(icsText(a.getType().label() + ": " + a.getTitle())).append("\r\n");
        String description = (a.getCaseNumber() == null ? "" : "Processo " + a.getCaseNumber() + "\n")
                + (a.getClientName() == null ? "" : "Cliente: " + a.getClientName() + "\n")
                + (a.getMeetingUrl() == null ? "" : "Videoconferência: " + a.getMeetingUrl() + "\n")
                + (a.getDescription() == null ? "" : a.getDescription());
        if (!description.isBlank()) {
            sb.append("DESCRIPTION:").append(icsText(description)).append("\r\n");
        }
        if (a.getLocation() != null) {
            sb.append("LOCATION:").append(icsText(a.getLocation())).append("\r\n");
        }
        if (a.getMeetingUrl() != null) {
            sb.append("URL:").append(a.getMeetingUrl()).append("\r\n");
        }
        sb.append("END:VEVENT\r\nEND:VCALENDAR\r\n");
        audit.record(user, "APPOINTMENT_ICS_EXPORTED", "appointment", id, "Compromisso exportado (.ics): " + a.getTitle());
        return sb.toString();
    }

    static String icsText(String v) {
        return v.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n");
    }

    /** Audiências, sessões, sustentações e perícias no período (módulo Audiências). */
    public List<AppointmentResponse> hearings(AuthenticatedUser user, Instant from, Instant to, String responsibleUid) {
        String officeId = user.requireOffice();
        if (from == null || to == null || !to.isAfter(from) || Duration.between(from, to).compareTo(Duration.ofDays(400)) > 0) {
            throw new BusinessException("INVALID_RANGE", "Intervalo inválido (máximo de 400 dias).");
        }
        Query q = appointments.collection(officeId).whereIn("type", List.of(br.com.erpadv.models.AppointmentType.AUDIENCIA.name(),
                br.com.erpadv.models.AppointmentType.JULGAMENTO.name(), br.com.erpadv.models.AppointmentType.SUSTENTACAO_ORAL.name(),
                br.com.erpadv.models.AppointmentType.PERICIA.name()));
        if (!user.has(Permission.CRIMINAL_READ)) {
            q = q.whereEqualTo("restricted", false);
        }
        q = q.whereGreaterThanOrEqualTo("start", from.toEpochMilli()).whereLessThan("start", to.toEpochMilli()).orderBy("start");
        return appointments.list(q, 500).stream()
                .filter(a -> responsibleUid == null || responsibleUid.isBlank() || a.getResponsibleUids().contains(responsibleUid))
                .map(AppointmentResponse::of).toList();
    }

    /** Reuniões e atendimentos (presenciais ou por videoconferência) do período. */
    public List<AppointmentResponse> meetings(AuthenticatedUser user, Instant from, Instant to, String responsibleUid) {
        String officeId = user.requireOffice();
        if (from == null || to == null || !to.isAfter(from) || Duration.between(from, to).compareTo(Duration.ofDays(400)) > 0) {
            throw new BusinessException("INVALID_RANGE", "Intervalo inválido (máximo de 400 dias).");
        }
        Query q = appointments.collection(officeId).whereIn("type", List.of(br.com.erpadv.models.AppointmentType.REUNIAO.name(),
                br.com.erpadv.models.AppointmentType.CONSULTA.name()));
        if (!user.has(Permission.CRIMINAL_READ)) {
            q = q.whereEqualTo("restricted", false);
        }
        q = q.whereGreaterThanOrEqualTo("start", from.toEpochMilli()).whereLessThan("start", to.toEpochMilli()).orderBy("start");
        return appointments.list(q, 500).stream()
                .filter(a -> responsibleUid == null || responsibleUid.isBlank() || a.getResponsibleUids().contains(responsibleUid))
                .map(AppointmentResponse::of).toList();
    }

    /** Ata da reunião (texto livre), registrada na auditoria. */
    public AppointmentResponse saveMinutes(AuthenticatedUser user, String id, String minutes) {
        Appointment a = load(user, id);
        String previous = a.getMinutes();
        a.setMinutes(minutes == null || minutes.isBlank() ? null : minutes.trim());
        a.setMinutesUpdatedAt(Instant.now());
        a.setMinutesByName(user.displayName());
        a.setUpdatedAt(Instant.now());
        a.setUpdatedBy(user.uid());
        appointments.save(user.officeId(), a);
        audit.record(user, previous == null ? "MEETING_MINUTES_CREATED" : "MEETING_MINUTES_UPDATED", "appointment", id,
                "Ata registrada: " + a.getTitle());
        return AppointmentResponse.of(a);
    }

    /** Relatório de compromissos do período (PDF/Excel). */
    public br.com.erpadv.services.generation.ReportExporter.Exported report(AuthenticatedUser user, Instant from, Instant to,
                                                                          String responsibleUid, boolean hearingsOnly, String format,
                                                                          String officeName) {
        List<AppointmentResponse> rows;
        if (hearingsOnly) {
            rows = hearings(user, from, to, responsibleUid);
        } else {
            if (from == null || to == null || !to.isAfter(from) || Duration.between(from, to).compareTo(Duration.ofDays(400)) > 0) {
                throw new BusinessException("INVALID_RANGE", "Intervalo inválido (máximo de 400 dias).");
            }
            Query q = appointments.collection(user.requireOffice());
            if (!user.has(Permission.CRIMINAL_READ)) {
                q = q.whereEqualTo("restricted", false);
            }
            if (responsibleUid != null && !responsibleUid.isBlank()) {
                q = q.whereArrayContains("responsibleUids", responsibleUid);
            }
            q = q.whereGreaterThanOrEqualTo("start", from.toEpochMilli()).whereLessThan("start", to.toEpochMilli()).orderBy("start");
            rows = appointments.list(q, 500).stream().map(AppointmentResponse::of).toList();
        }
        java.time.format.DateTimeFormatter dt = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(CourtCalendarService.ZONE);
        java.time.format.DateTimeFormatter d = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(CourtCalendarService.ZONE);
        List<List<String>> table = rows.stream().map(a -> List.of(dt.format(a.start()), a.typeLabel(), a.title(),
                a.caseNumber() == null ? "" : a.caseNumber(), a.clientName() == null ? "" : a.clientName(),
                String.join(", ", a.responsibleNames()), a.location() == null ? "" : a.location(), a.statusLabel(),
                a.outcome() == null ? "" : a.outcome())).toList();
        long done = rows.stream().filter(a -> a.status() == br.com.erpadv.models.AppointmentStatus.REALIZADO).count();
        audit.record(user, "APPOINTMENTS_REPORT_EXPORTED", "appointment", "*", "Relatório de " + (hearingsOnly ? "audiências" : "compromissos")
                + " (" + rows.size() + ")", Map.of("format", format == null ? "pdf" : format));
        return br.com.erpadv.services.generation.ReportExporter.export(new br.com.erpadv.services.generation.ReportExporter.Report(
                hearingsOnly ? "Relatório de audiências" : "Relatório de compromissos", officeName,
                "Período: " + d.format(from) + " a " + d.format(to.minusSeconds(1)) + " · " + rows.size() + " registro(s), " + done + " realizado(s)",
                List.of(br.com.erpadv.services.generation.ReportExporter.Section.table(hearingsOnly ? "Audiências" : "Compromissos",
                        List.of("Data", "Tipo", "Título", "Processo", "Cliente", "Responsáveis", "Local", "Situação", "Resultado"), table)), true),
                format, hearingsOnly ? "audiencias" : "compromissos");
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
        pushGoogle(user.officeId(), a);
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
        removeGoogle(user.officeId(), a);
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
        br.com.erpadv.models.MeetingPlatform platform = req.meetingPlatform() != null ? req.meetingPlatform()
                : br.com.erpadv.models.MeetingPlatform.fromUrl(a.getMeetingUrl());
        if (platform != null && a.getMeetingUrl() != null && !platform.accepts(a.getMeetingUrl())) {
            throw new BusinessException("MEETING_URL_PLATFORM", "O link não é do " + platform.label()
                    + ". Cole o endereço gerado pela própria plataforma.");
        }
        a.setMeetingPlatform(platform);
        a.setDescription(trim(req.description()));
        a.setResponsibleUids(uids);
        a.setResponsibleNames(names);
        a.setReminders(req.reminders() == null ? new ArrayList<>()
                : new ArrayList<>(new LinkedHashSet<>(req.reminders())));
        a.setRestricted(restricted);
        List<br.com.erpadv.models.AppointmentGuest> guests = new ArrayList<>();
        if (req.guests() != null) {
            for (var g : req.guests()) {
                var guest = new br.com.erpadv.models.AppointmentGuest();
                guest.setName(g.name().trim());
                guest.setEmail(g.email() == null || g.email().isBlank() ? null : g.email().trim().toLowerCase());
                guests.add(guest);
            }
        }
        a.setGuests(guests);
        java.util.Map<String, String> attendance = new java.util.HashMap<>();
        for (String uid : uids) {
            attendance.put(uid, a.getAttendance() == null ? "PENDENTE" : a.getAttendance().getOrDefault(uid,
                    uid.equals(user.uid()) ? "CONFIRMADO" : "PENDENTE"));
        }
        a.setAttendance(attendance);
        if (a.getStatus() == null) {
            a.setStatus(br.com.erpadv.models.AppointmentStatus.AGENDADO);
        }
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
