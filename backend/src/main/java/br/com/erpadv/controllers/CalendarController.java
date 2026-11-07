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

    public CalendarController(CalendarService calendar, NotificationService notifications) {
        this.calendar = calendar;
        this.notifications = notifications;
    }

    @GetMapping("/api/calendar/events")
    @PreAuthorize("hasAuthority('PERM_CALENDAR_READ') or hasAuthority('PERM_DEADLINES_READ')")
    public List<CalendarEvent> events(@AuthenticationPrincipal AuthenticatedUser user,
                                      @RequestParam Instant from,
                                      @RequestParam Instant to,
                                      @RequestParam(defaultValue = "false") boolean mine,
                                      @RequestParam(defaultValue = "true") boolean deadlines) {
        return calendar.events(user, from, to, mine, deadlines);
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
                                      @Valid @RequestBody AppointmentRequest req) {
        return calendar.update(user, id, req);
    }

    @DeleteMapping("/api/calendar/appointments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_CALENDAR_WRITE')")
    public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        calendar.delete(user, id);
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
