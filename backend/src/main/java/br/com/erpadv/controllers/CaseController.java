package br.com.erpadv.controllers;

import br.com.erpadv.dto.CaseDtos.CaseFilter;
import br.com.erpadv.dto.CaseDtos.CaseRequest;
import br.com.erpadv.dto.CaseDtos.CaseResponse;
import br.com.erpadv.dto.CaseDtos.MovementRequest;
import br.com.erpadv.dto.CursorPage;
import br.com.erpadv.models.CaseMovement;
import br.com.erpadv.models.CaseStatus;
import br.com.erpadv.models.LegalArea;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.CaseService;
import br.com.erpadv.audit.AuditLog;
import br.com.erpadv.audit.AuditService;
import br.com.erpadv.dto.CaseDtos.CaseRelated;
import br.com.erpadv.services.CaseInsightsService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cases")
public class CaseController {

    private final CaseService cases;
    private final CaseInsightsService insights;
    private final AuditService audit;

    public CaseController(CaseService cases, CaseInsightsService insights, AuditService audit) {
        this.cases = cases;
        this.insights = insights;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_CASES_READ')")
    public CursorPage<CaseResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                         @RequestParam(required = false) String search,
                                         @RequestParam(required = false) CaseStatus status,
                                         @RequestParam(required = false) LegalArea area,
                                         @RequestParam(required = false) String responsibleUid,
                                         @RequestParam(required = false) String court,
                                         @RequestParam(required = false) String unitId,
                                         @RequestParam(required = false) String clientId,
                                         @RequestParam(defaultValue = "false") boolean includeArchived,
                                         @RequestParam(required = false) String cursor,
                                         @RequestParam(defaultValue = "20") int size) {
        CaseFilter filter = new CaseFilter(search, status, area, responsibleUid, court, clientId, includeArchived, unitId);
        return cases.list(user, filter, cursor, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_CASES_READ')")
    public CaseResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return cases.get(user, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_CASES_WRITE')")
    public CaseResponse create(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody CaseRequest request) {
        return cases.create(user, request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_CASES_WRITE')")
    public CaseResponse update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                               @Valid @RequestBody CaseRequest request) {
        return cases.update(user, id, request);
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('PERM_CASES_ARCHIVE')")
    public CaseResponse archive(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return cases.archive(user, id);
    }

    @PostMapping("/{id}/unarchive")
    @PreAuthorize("hasAuthority('PERM_CASES_ARCHIVE')")
    public CaseResponse unarchive(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return cases.unarchive(user, id);
    }

    @GetMapping("/{id}/movements")
    @PreAuthorize("hasAuthority('PERM_CASES_READ')")
    public CursorPage<CaseMovement> movements(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                              @RequestParam(required = false) String cursor,
                                              @RequestParam(defaultValue = "30") int size) {
        return cases.movements(user, id, cursor, size);
    }

    @PostMapping("/{id}/movements")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_CASES_WRITE')")
    public CaseMovement addMovement(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                    @Valid @RequestBody MovementRequest request) {
        return cases.addMovement(user, id, request);
    }

    /** Exporta a lista filtrada ou a seleção (ids) em PDF ou Excel. */
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('PERM_CASES_READ') and hasAuthority('PERM_REPORTS_VIEW')")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal AuthenticatedUser user,
                                         @RequestParam(required = false) String search,
                                         @RequestParam(required = false) CaseStatus status,
                                         @RequestParam(required = false) LegalArea area,
                                         @RequestParam(required = false) String responsibleUid,
                                         @RequestParam(required = false) String court,
                                         @RequestParam(required = false) String unitId,
                                         @RequestParam(defaultValue = "false") boolean includeArchived,
                                         @RequestParam(required = false) List<String> ids,
                                         @RequestParam(defaultValue = "xlsx") String format) {
        CaseFilter filter = new CaseFilter(search, status, area, responsibleUid, court, null, includeArchived, unitId);
        return DashboardController.file(insights.export(user, filter, ids, format));
    }

    /** Dados das abas do processo: prazos, audiências, financeiro, contratos, tarefas e documentos. */
    @GetMapping("/{id}/related")
    @PreAuthorize("hasAuthority('PERM_CASES_READ')")
    public CaseRelated related(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return insights.related(user, id);
    }

    /** Ficha/relatório completo do processo (PDF para impressão ou Excel). */
    @GetMapping("/{id}/report")
    @PreAuthorize("hasAuthority('PERM_CASES_READ')")
    public ResponseEntity<byte[]> report(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                         @RequestParam(defaultValue = "pdf") String format) {
        return DashboardController.file(insights.report(user, id, format));
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAuthority('PERM_CASES_READ')")
    public CursorPage<AuditLog> history(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                        @RequestParam(required = false) String cursor,
                                        @RequestParam(defaultValue = "30") int size) {
        cases.load(user, id);
        return audit.list(user.officeId(), "case", id, null, cursor, size);
    }

    @GetMapping("/{id}/links")
    @PreAuthorize("hasAuthority('PERM_CASES_READ')")
    public Map<String, Long> links(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return cases.links(user, id);
    }

    @PostMapping("/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_CASES_WRITE')")
    public CaseResponse duplicate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return cases.duplicate(user, id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_CASES_DELETE')")
    public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                       @RequestParam(required = false) String reason) {
        cases.delete(user, id, reason);
    }
}
