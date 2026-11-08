package br.com.erpadv.controllers;

import br.com.erpadv.dto.CursorPage;
import br.com.erpadv.dto.DeadlineDtos.CalculateRequest;
import br.com.erpadv.dto.DeadlineDtos.CalculationResponse;
import br.com.erpadv.dto.DeadlineDtos.CompleteRequest;
import br.com.erpadv.dto.DeadlineDtos.CourtCalendarRequest;
import br.com.erpadv.dto.DeadlineDtos.CourtCalendarResponse;
import br.com.erpadv.dto.DeadlineDtos.DeadlineRequest;
import br.com.erpadv.dto.DeadlineDtos.DeadlineResponse;
import br.com.erpadv.dto.DeadlineDtos.DeadlineStats;
import br.com.erpadv.models.CourtCalendarEntry;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.CourtCalendarService;
import br.com.erpadv.services.DeadlineService;
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

import java.time.LocalDate;

@RestController
public class DeadlineController {

    private final DeadlineService deadlines;
    private final CourtCalendarService courtCalendar;

    public DeadlineController(DeadlineService deadlines, CourtCalendarService courtCalendar) {
        this.deadlines = deadlines;
        this.courtCalendar = courtCalendar;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private br.com.erpadv.repositories.OfficeRepository offices;

    @org.springframework.beans.factory.annotation.Autowired
    private br.com.erpadv.audit.AuditService audit;

    @GetMapping("/api/deadlines")
    @PreAuthorize("hasAuthority('PERM_DEADLINES_READ')")
    public CursorPage<DeadlineResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                             @RequestParam(defaultValue = "PENDENTES") DeadlineService.View view,
                                             @RequestParam(required = false) String responsibleUid,
                                             @RequestParam(required = false) String caseId,
                                             @RequestParam(required = false) String clientId,
                                             @RequestParam(required = false) String cursor,
                                             @RequestParam(defaultValue = "30") int size) {
        return deadlines.list(user, view, responsibleUid, caseId, clientId, cursor, size);
    }

    /** Sugestão de data de publicação (1º dia útil após a disponibilização no DJe). */
    @GetMapping("/api/deadlines/publication-date")
    @PreAuthorize("hasAuthority('PERM_DEADLINES_READ')")
    public br.com.erpadv.dto.DeadlineDtos.PublicationDateResponse publicationDate(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate availability,
            @RequestParam(required = false) String court) {
        return deadlines.publicationDate(user, availability, court);
    }

    /** Relatório de prazos por responsável (PDF/Excel). */
    @GetMapping("/api/deadlines/report")
    @PreAuthorize("hasAuthority('PERM_DEADLINES_READ') and hasAuthority('PERM_REPORTS_VIEW')")
    public org.springframework.http.ResponseEntity<byte[]> report(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to,
            @RequestParam(required = false) String responsibleUid,
            @RequestParam(defaultValue = "pdf") String format) {
        String officeName = offices.findById(user.requireOffice()).map(br.com.erpadv.models.Office::getName).orElse("Escritório");
        return DashboardController.file(deadlines.report(user, from, to, responsibleUid, format, officeName));
    }

    @GetMapping("/api/deadlines/{id}/history")
    @PreAuthorize("hasAuthority('PERM_DEADLINES_READ')")
    public br.com.erpadv.dto.CursorPage<br.com.erpadv.audit.AuditLog> history(@AuthenticationPrincipal AuthenticatedUser user,
                                                                            @PathVariable String id,
                                                                            @RequestParam(required = false) String cursor) {
        deadlines.load(user, id);
        return audit.list(user.officeId(), "deadline", id, null, cursor, 30);
    }

    @PostMapping(value = "/api/deadlines/{id}/receipt", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('PERM_DEADLINES_WRITE')")
    public DeadlineResponse receipt(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                    @org.springframework.web.bind.annotation.RequestPart("file") org.springframework.web.multipart.MultipartFile file) {
        return deadlines.attachReceipt(user, id, DocumentController.bytes(file), file.getOriginalFilename());
    }

    @GetMapping("/api/deadlines/{id}/receipt")
    @PreAuthorize("hasAuthority('PERM_DEADLINES_READ')")
    public org.springframework.http.ResponseEntity<byte[]> downloadReceipt(@AuthenticationPrincipal AuthenticatedUser user,
                                                                          @PathVariable String id) {
        var r = deadlines.downloadReceipt(user, id);
        return DashboardController.file(new br.com.erpadv.services.generation.ReportExporter.Exported(r.bytes(), r.name(), r.contentType()));
    }

    @GetMapping("/api/deadlines/stats")
    @PreAuthorize("hasAuthority('PERM_DEADLINES_READ')")
    public DeadlineStats stats(@AuthenticationPrincipal AuthenticatedUser user,
                               @RequestParam(required = false) String responsibleUid) {
        return deadlines.stats(user, responsibleUid);
    }

    @GetMapping("/api/deadlines/{id}")
    @PreAuthorize("hasAuthority('PERM_DEADLINES_READ')")
    public DeadlineResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return deadlines.get(user, id);
    }

    @PostMapping("/api/deadlines")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_DEADLINES_WRITE')")
    public DeadlineResponse create(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody DeadlineRequest req) {
        return deadlines.create(user, req);
    }

    @PutMapping("/api/deadlines/{id}")
    @PreAuthorize("hasAuthority('PERM_DEADLINES_WRITE')")
    public DeadlineResponse update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                   @Valid @RequestBody DeadlineRequest req) {
        return deadlines.update(user, id, req);
    }

    @PostMapping("/api/deadlines/{id}/validate")
    @PreAuthorize("hasAuthority('PERM_DEADLINES_WRITE')")
    public DeadlineResponse validate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return deadlines.validate(user, id);
    }

    @PostMapping("/api/deadlines/{id}/complete")
    @PreAuthorize("hasAuthority('PERM_DEADLINES_WRITE')")
    public DeadlineResponse complete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                     @Valid @RequestBody(required = false) CompleteRequest req) {
        return deadlines.complete(user, id, req == null ? null : req.note());
    }

    @PostMapping("/api/deadlines/{id}/cancel")
    @PreAuthorize("hasAuthority('PERM_DEADLINES_WRITE')")
    public DeadlineResponse cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                   @Valid @RequestBody CompleteRequest req) {
        return deadlines.cancel(user, id, req.note());
    }

    @PostMapping("/api/deadlines/{id}/reopen")
    @PreAuthorize("hasAuthority('PERM_DEADLINES_WRITE')")
    public DeadlineResponse reopen(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return deadlines.reopen(user, id);
    }

    /** Simulação de contagem (não grava nada). */
    @PostMapping("/api/deadlines/calculate")
    @PreAuthorize("hasAuthority('PERM_DEADLINES_READ')")
    public CalculationResponse calculate(@AuthenticationPrincipal AuthenticatedUser user,
                                         @Valid @RequestBody CalculateRequest req) {
        return CalculationResponse.of(courtCalendar.calculate(user, req));
    }

    // ------------------------------------------------------------------ calendário forense

    @GetMapping("/api/court-calendar")
    @PreAuthorize("hasAuthority('PERM_DEADLINES_READ')")
    public CourtCalendarResponse courtCalendar(@AuthenticationPrincipal AuthenticatedUser user,
                                               @RequestParam(required = false) Integer year) {
        return courtCalendar.year(user, year == null ? LocalDate.now(CourtCalendarService.ZONE).getYear() : year);
    }

    @PostMapping("/api/court-calendar")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_DEADLINES_WRITE')")
    public CourtCalendarEntry addCourtCalendar(@AuthenticationPrincipal AuthenticatedUser user,
                                               @Valid @RequestBody CourtCalendarRequest req) {
        return courtCalendar.create(user, req);
    }

    @DeleteMapping("/api/court-calendar/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_DEADLINES_WRITE')")
    public void deleteCourtCalendar(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        courtCalendar.delete(user, id);
    }
}
