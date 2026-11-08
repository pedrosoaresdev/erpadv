package br.com.erpadv.controllers;

import br.com.erpadv.models.Publication;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.integrations.PublicationService;
import br.com.erpadv.services.integrations.PublicationService.PublicationResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Publicações do DJEN vinculadas aos processos e prazos do escritório. */
@RestController
@RequestMapping("/api/publications")
public class PublicationController {

    public record StatusRequest(Publication.Status status) {
    }

    public record LinkRequest(String id) {
    }

    private final PublicationService publications;

    public PublicationController(PublicationService publications) {
        this.publications = publications;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_DEADLINES_READ')")
    public List<PublicationResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                          @RequestParam(required = false) Publication.Status status,
                                          @RequestParam(required = false) String lawyerUid,
                                          @RequestParam(required = false) String caseId,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return publications.list(user, status, lawyerUid, caseId, from, to);
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('PERM_DEADLINES_READ')")
    public PublicationResponse status(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id, @RequestBody StatusRequest req) {
        return publications.setStatus(user, id, req.status() == null ? Publication.Status.LIDA : req.status());
    }

    @PostMapping("/{id}/deadline")
    @PreAuthorize("hasAuthority('PERM_DEADLINES_WRITE')")
    public PublicationResponse deadline(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id, @RequestBody LinkRequest req) {
        return publications.linkDeadline(user, id, req.id());
    }

    @PostMapping("/{id}/case")
    @PreAuthorize("hasAuthority('PERM_DEADLINES_WRITE')")
    public PublicationResponse linkCase(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id, @RequestBody LinkRequest req) {
        return publications.linkCase(user, id, req.id());
    }
}
