package br.com.erpadv.controllers;

import br.com.erpadv.dto.CalendarDtos.AppointmentRequest;
import br.com.erpadv.dto.CalendarDtos.AppointmentResponse;
import br.com.erpadv.dto.CalendarDtos.CalendarEvent;
import br.com.erpadv.dto.CalendarDtos.DeviceTokenRequest;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.CalendarService;
import br.com.erpadv.services.NotificationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
public class CalendarController {

    private final CalendarService calendar;
    private final NotificationService notifications;

    private final br.com.erpadv.repositories.OfficeRepository offices;
    private final br.com.erpadv.audit.AuditService audit;

    public CalendarController(CalendarService calendar, NotificationService notifications,
                              br.com.erpadv.repositories.OfficeRepository offices, br.com.erpadv.audit.AuditService audit) {
        this.calendar = calendar;
        this.notifications = notifications;
        this.offices = offices;
        this.audit = audit;
    }

    @GetMapping("/api/calendar/events")
    @PreAuthorize("hasAuthority('PERM_CALENDAR_READ') or hasAuthority('PERM_DEADLINES_READ')")
    public List<CalendarEvent> events(@AuthenticationPrincipal AuthenticatedUser user,
                                      @RequestParam Instant from,
                                      @RequestParam Instant to,
                                      @RequestParam(defaultValue = "false") boolean mine,
                                      @RequestParam(required = false) String responsibleUid,
                                      @RequestParam(defaultValue = "true") boolean deadlines) {
        String who = responsibleUid != null && !responsibleUid.isBlank() ? responsibleUid : mine ? user.uid() : null;
        return calendar.events(user, from, to, who, deadlines);
    }

    /** Audiências, sessões de julgamento, sustentações orais e perícias (módulo Audiências). */
    @GetMapping("/api/calendar/hearings")
    @PreAuthorize("hasAuthority('PERM_CALENDAR_READ')")
    public List<AppointmentResponse> hearings(@AuthenticationPrincipal AuthenticatedUser user,
                                              @RequestParam Instant from, @RequestParam Instant to,
                                              @RequestParam(required = false) String responsibleUid) {
        return calendar.hearings(user, from, to, responsibleUid);
    }

    /** Relatório de compromissos ou de audiências (PDF/Excel). */
    @GetMapping("/api/calendar/report")
    @PreAuthorize("hasAuthority('PERM_CALENDAR_READ') and hasAuthority('PERM_REPORTS_VIEW')")
    public org.springframework.http.ResponseEntity<byte[]> report(@AuthenticationPrincipal AuthenticatedUser user,
                                                                  @RequestParam Instant from, @RequestParam Instant to,
                                                                  @RequestParam(required = false) String responsibleUid,
                                                                  @RequestParam(defaultValue = "false") boolean hearings,
                                                                  @RequestParam(defaultValue = "pdf") String format) {
        String officeName = offices.findById(user.requireOffice()).map(br.com.erpadv.models.Office::getName).orElse("Escritório");
        return DashboardController.file(calendar.report(user, from, to, responsibleUid, hearings, format, officeName));
    }

    @PostMapping("/api/calendar/appointments/{id}/rsvp")
    @PreAuthorize("hasAuthority('PERM_CALENDAR_READ')")
    public AppointmentResponse rsvp(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                    @Valid @RequestBody br.com.erpadv.dto.CalendarDtos.RsvpRequest req) {
        return calendar.rsvp(user, id, req.status());
    }

    @PostMapping("/api/calendar/appointments/{id}/status")
    @PreAuthorize("hasAuthority('PERM_CALENDAR_WRITE')")
    public AppointmentResponse status(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                      @Valid @RequestBody br.com.erpadv.dto.CalendarDtos.StatusRequest req) {
        return calendar.setStatus(user, id, req.status(), req.outcome());
    }

    /** Exporta o compromisso em iCalendar (.ics) para Google Agenda, Outlook ou Apple Calendar. */
    @GetMapping("/api/calendar/appointments/{id}/ics")
    @PreAuthorize("hasAuthority('PERM_CALENDAR_READ')")
    public org.springframework.http.ResponseEntity<byte[]> ics(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        byte[] bytes = calendar.ics(user, id).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return DashboardController.file(new br.com.erpadv.services.generation.ReportExporter.Exported(bytes,
                "compromisso.ics", "text/calendar; charset=utf-8"));
    }

    @GetMapping("/api/calendar/appointments/{id}/history")
    @PreAuthorize("hasAuthority('PERM_CALENDAR_READ')")
    public br.com.erpadv.dto.CursorPage<br.com.erpadv.audit.AuditLog> history(@AuthenticationPrincipal AuthenticatedUser user,
                                                                            @PathVariable String id) {
        calendar.load(user, id);
        return audit.list(user.officeId(), "appointment", id, null, null, 30);
    }

    @GetMapping("/api/calendar/appointments/{id}")
    @PreAuthorize("hasAuthority('PERM_CALENDAR_READ')")
    public AppointmentResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return calendar.get(user, id);
    }

    @PostMapping("/api/calendar/appointments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_CALENDAR_WRITE')")
    public AppointmentResponse create(@AuthenticationPrincipal AuthenticatedUser user,
                                      @Valid @RequestBody AppointmentRequest req) {
        return calendar.create(user, req);
    }

    @PutMapping("/api/calendar/appointments/{id}")
    @PreAuthorize("hasAuthority('PERM_CALENDAR_WRITE')")
    public AppointmentResponse update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                      @Valid @RequestBody AppointmentRequest req,
                                      @RequestParam(defaultValue = "false") boolean series) {
        return calendar.update(user, id, req, series);
    }

    @DeleteMapping("/api/calendar/appointments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_CALENDAR_WRITE')")
    public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                       @RequestParam(defaultValue = "false") boolean series) {
        calendar.delete(user, id, series);
    }

    /** Registro do token FCM do navegador/dispositivo para notificações push. */
    @PostMapping("/api/notifications/devices")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void registerDevice(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody DeviceTokenRequest req) {
        notifications.registerDevice(user, req.token(), req.platform());
    }

    @PostMapping("/api/notifications/devices/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregisterDevice(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody DeviceTokenRequest req) {
        notifications.unregisterDevice(user, req.token());
    }
}
