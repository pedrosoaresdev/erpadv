package br.com.erpadv.controllers;

import br.com.erpadv.models.DocumentCategory;
import br.com.erpadv.models.Lgpd;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.lgpd.LgpdService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Módulo LGPD. Toda a gestão exige a permissão LGPD_MANAGE (atribuível ao encarregado por cargo personalizado). */
@RestController
@RequestMapping("/api/lgpd")
@PreAuthorize("hasAuthority('PERM_LGPD_MANAGE')")
public class LgpdController {

    public record NoteRequest(String note) {
    }

    public record IdsRequest(List<String> ids) {
    }

    public record Option(String value, String label) {
    }

    private final LgpdService lgpd;

    public LgpdController(LgpdService lgpd) {
        this.lgpd = lgpd;
    }

    @GetMapping("/overview")
    public LgpdService.Overview overview(@AuthenticationPrincipal AuthenticatedUser user) {
        return lgpd.overview(user);
    }

    @GetMapping("/options")
    public Map<String, List<Option>> options() {
        return Map.of(
                "legalBases", Arrays.stream(Lgpd.LegalBasis.values()).map(b -> new Option(b.name(), b.label())).toList(),
                "requestTypes", Arrays.stream(Lgpd.RequestType.values()).map(t -> new Option(t.name(), t.label())).toList(),
                "documentCategories", Arrays.stream(DocumentCategory.values()).map(c -> new Option(c.name(), c.label())).toList());
    }

    // ------------------------------------------------------------------ titulares

    @GetMapping("/requests")
    public List<Lgpd.DataRequest> requests(@AuthenticationPrincipal AuthenticatedUser user,
                                           @RequestParam(required = false) Lgpd.DataRequest.Status status,
                                           @RequestParam(required = false) String clientId) {
        return lgpd.requests(user, status, clientId);
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public Lgpd.DataRequest createRequest(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody LgpdService.RequestInput in) {
        return lgpd.createRequest(user, in);
    }

    @PutMapping("/requests/{id}")
    public Lgpd.DataRequest updateRequest(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                          @RequestBody LgpdService.RequestUpdate in) {
        return lgpd.updateRequest(user, id, in);
    }

    @GetMapping("/clients/{clientId}/export")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String clientId,
                                         @RequestParam(defaultValue = "pdf") String format) {
        return DashboardController.file(lgpd.exportClient(user, clientId, format));
    }

    @PostMapping("/clients/{clientId}/anonymize")
    public LgpdService.AnonymizeResult anonymize(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String clientId,
                                                 @RequestParam(required = false) String requestId) {
        return lgpd.anonymize(user, clientId, requestId);
    }

    // ------------------------------------------------------------------ consentimentos

    @GetMapping("/consents")
    public List<Lgpd.Consent> consents(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam(required = false) String clientId) {
        return lgpd.consents(user, clientId);
    }

    @PostMapping("/consents")
    @ResponseStatus(HttpStatus.CREATED)
    public Lgpd.Consent createConsent(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody LgpdService.ConsentInput in) {
        return lgpd.createConsent(user, in);
    }

    @PostMapping("/consents/{id}/revoke")
    public Lgpd.Consent revoke(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id, @RequestBody(required = false) NoteRequest req) {
        return lgpd.revokeConsent(user, id, req == null ? null : req.note());
    }

    // ------------------------------------------------------------------ registro das operações

    @GetMapping("/activities")
    public List<Lgpd.ProcessingActivity> activities(@AuthenticationPrincipal AuthenticatedUser user) {
        return lgpd.activities(user);
    }

    @PostMapping("/activities")
    @ResponseStatus(HttpStatus.CREATED)
    public Lgpd.ProcessingActivity createActivity(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody LgpdService.ActivityInput in) {
        return lgpd.saveActivity(user, null, in);
    }

    @PutMapping("/activities/{id}")
    public Lgpd.ProcessingActivity updateActivity(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                                  @RequestBody LgpdService.ActivityInput in) {
        return lgpd.saveActivity(user, id, in);
    }

    @DeleteMapping("/activities/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteActivity(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        lgpd.deleteActivity(user, id);
    }

    @PostMapping("/activities/suggestions")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Lgpd.ProcessingActivity> seed(@AuthenticationPrincipal AuthenticatedUser user) {
        return lgpd.seedSuggestions(user);
    }

    // ------------------------------------------------------------------ incidentes

    @GetMapping("/incidents")
    public List<Lgpd.Incident> incidents(@AuthenticationPrincipal AuthenticatedUser user) {
        return lgpd.incidents(user);
    }

    @PostMapping("/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    public Lgpd.Incident createIncident(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody LgpdService.IncidentInput in) {
        return lgpd.createIncident(user, in);
    }

    @PutMapping("/incidents/{id}")
    public Lgpd.Incident updateIncident(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                        @RequestBody LgpdService.IncidentUpdate in) {
        return lgpd.updateIncident(user, id, in);
    }

    // ------------------------------------------------------------------ compartilhamentos

    @GetMapping("/shares")
    public List<Lgpd.DataShare> shares(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam(required = false) String clientId) {
        return lgpd.shares(user, clientId);
    }

    @PostMapping("/shares")
    @ResponseStatus(HttpStatus.CREATED)
    public Lgpd.DataShare createShare(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody LgpdService.ShareInput in) {
        return lgpd.createShare(user, in);
    }

    // ------------------------------------------------------------------ política de privacidade

    @GetMapping("/policies")
    public List<Lgpd.PrivacyPolicy> policies(@AuthenticationPrincipal AuthenticatedUser user) {
        return lgpd.policyVersions(user);
    }

    @GetMapping("/policies/template")
    public LgpdService.PolicyInput template(@AuthenticationPrincipal AuthenticatedUser user) {
        return lgpd.template(user);
    }

    @PostMapping("/policies")
    @ResponseStatus(HttpStatus.CREATED)
    public Lgpd.PrivacyPolicy createPolicy(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody LgpdService.PolicyInput in) {
        return lgpd.saveDraft(user, null, in);
    }

    @PutMapping("/policies/{id}")
    public Lgpd.PrivacyPolicy updatePolicy(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                           @RequestBody LgpdService.PolicyInput in) {
        return lgpd.saveDraft(user, id, in);
    }

    @PostMapping("/policies/{id}/publish")
    public Lgpd.PrivacyPolicy publish(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return lgpd.publish(user, id);
    }

    // ------------------------------------------------------------------ retenção

    @GetMapping("/retention/rules")
    public List<Lgpd.RetentionRule> rules(@AuthenticationPrincipal AuthenticatedUser user) {
        return lgpd.retentionRules(user);
    }

    @PutMapping("/retention/rules")
    public Lgpd.RetentionRule saveRule(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody LgpdService.RetentionInput in) {
        return lgpd.saveRetention(user, in);
    }

    @DeleteMapping("/retention/rules/{category}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRule(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable DocumentCategory category) {
        lgpd.deleteRetention(user, category);
    }

    @GetMapping("/retention/report")
    public List<LgpdService.RetentionItem> report(@AuthenticationPrincipal AuthenticatedUser user) {
        return lgpd.retentionReport(user);
    }

    @PostMapping("/retention/trash")
    public Map<String, Integer> trash(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody IdsRequest req) {
        return Map.of("trashed", lgpd.trashExpired(user, req.ids()));
    }
}
