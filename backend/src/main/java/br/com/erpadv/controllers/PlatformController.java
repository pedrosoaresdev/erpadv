package br.com.erpadv.controllers;

import br.com.erpadv.dto.PlatformDtos.AdminUserRequest;
import br.com.erpadv.dto.PlatformDtos.CreateOfficeRequest;
import br.com.erpadv.dto.PlatformDtos.LinkResponse;
import br.com.erpadv.dto.PlatformDtos.OfficeCreated;
import br.com.erpadv.dto.PlatformDtos.OfficeDetail;
import br.com.erpadv.dto.PlatformDtos.OfficeSummary;
import br.com.erpadv.dto.PlatformDtos.OfficeUser;
import br.com.erpadv.dto.PlatformDtos.PasswordRequest;
import br.com.erpadv.dto.PlatformDtos.PlatformOverview;
import br.com.erpadv.dto.PlatformDtos.SuspendRequest;
import br.com.erpadv.dto.PlatformDtos.UpdateOfficeRequest;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.PlatformService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Painel master do desenvolvedor da plataforma (somente PLATFORM_ADMIN). */
@RestController
@RequestMapping("/api/platform")
@PreAuthorize("hasAuthority('PERM_PLATFORM_ADMIN')")
public class PlatformController {

    private final PlatformService platform;
    @org.springframework.beans.factory.annotation.Autowired
    private br.com.erpadv.services.platform.BillingService billing;
    @org.springframework.beans.factory.annotation.Autowired
    private br.com.erpadv.services.platform.SupportService support;
    @org.springframework.beans.factory.annotation.Autowired
    private br.com.erpadv.services.platform.MonitoringService monitoring;

    public record ReasonRequest(String reason) {
    }

    public PlatformController(PlatformService platform) {
        this.platform = platform;
    }

    // ------------------------------------------------------------------ planos, assinaturas e faturas (cobrança manual)

    @GetMapping("/plans")
    public java.util.List<br.com.erpadv.models.Plan> plans() {
        return billing.plans();
    }

    @org.springframework.web.bind.annotation.PutMapping("/plans")
    public br.com.erpadv.models.Plan savePlan(@AuthenticationPrincipal AuthenticatedUser master, @RequestBody br.com.erpadv.services.platform.BillingService.PlanRequest req) {
        return billing.savePlan(master, req);
    }

    @GetMapping("/offices/{officeId}/subscription")
    public br.com.erpadv.services.platform.BillingService.SubscriptionView subscription(@PathVariable String officeId) {
        return billing.view(officeId);
    }

    @org.springframework.web.bind.annotation.PutMapping("/offices/{officeId}/subscription")
    public br.com.erpadv.services.platform.BillingService.SubscriptionView updateSubscription(@AuthenticationPrincipal AuthenticatedUser master, @PathVariable String officeId,
                                                               @RequestBody br.com.erpadv.services.platform.BillingService.SubscriptionRequest req) {
        return billing.updateSubscription(master, officeId, req);
    }

    @PostMapping("/offices/{officeId}/invoices")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public br.com.erpadv.models.SubscriptionInvoice issueInvoice(@AuthenticationPrincipal AuthenticatedUser master, @PathVariable String officeId) {
        return billing.issueNow(master, officeId);
    }

    @GetMapping("/invoices")
    public java.util.List<br.com.erpadv.models.SubscriptionInvoice> invoices(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String officeId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) br.com.erpadv.models.SubscriptionInvoice.Status status) {
        return billing.list(officeId, status);
    }

    @PostMapping("/invoices/{id}/pay")
    public br.com.erpadv.models.SubscriptionInvoice pay(@AuthenticationPrincipal AuthenticatedUser master, @PathVariable String id,
                                                        @RequestBody br.com.erpadv.services.platform.BillingService.PaymentRequest req) {
        return billing.markPaid(master, id, req);
    }

    @PostMapping("/invoices/{id}/cancel")
    public br.com.erpadv.models.SubscriptionInvoice cancelInvoice(@AuthenticationPrincipal AuthenticatedUser master, @PathVariable String id,
                                                                  @RequestBody ReasonRequest req) {
        return billing.cancel(master, id, req.reason());
    }

    @GetMapping("/invoices/{id}/pdf")
    public org.springframework.http.ResponseEntity<byte[]> invoicePdf(@AuthenticationPrincipal AuthenticatedUser master, @PathVariable String id) {
        return DashboardController.file(billing.pdf(billing.invoiceOf(master, id)));
    }

    @PostMapping("/billing/run")
    public java.util.Map<String, Integer> runBilling() {
        return java.util.Map.of("actions", billing.runDaily(java.time.LocalDate.now(br.com.erpadv.services.CourtCalendarService.ZONE)));
    }

    @GetMapping("/billing/settings")
    public br.com.erpadv.services.platform.BillingService.BillingSettings billingSettings() {
        return billing.settings();
    }

    @org.springframework.web.bind.annotation.PutMapping("/billing/settings")
    public br.com.erpadv.services.platform.BillingService.BillingSettings saveBillingSettings(@RequestBody br.com.erpadv.services.platform.BillingService.BillingSettings s) {
        return billing.saveSettings(s);
    }

    // ------------------------------------------------------------------ suporte e monitoramento

    @GetMapping("/support/tickets")
    public java.util.List<br.com.erpadv.models.SupportTicket> supportTickets(
            @org.springframework.web.bind.annotation.RequestParam(required = false) br.com.erpadv.models.SupportTicket.Status status) {
        return support.all(status);
    }

    @PostMapping("/support/tickets/{id}/reply")
    public br.com.erpadv.models.SupportTicket supportReply(@AuthenticationPrincipal AuthenticatedUser master, @PathVariable String id,
                                                           @RequestBody br.com.erpadv.services.platform.SupportService.ReplyRequest req) {
        return support.platformReply(master, id, req);
    }

    @GetMapping("/monitoring")
    public br.com.erpadv.services.platform.MonitoringService.Metrics monitoring() {
        return monitoring.metrics();
    }

    @GetMapping("/offices")
    public PlatformOverview overview() {
        return platform.overview();
    }

    @GetMapping("/offices/{officeId}")
    public OfficeDetail detail(@PathVariable String officeId) {
        return platform.detail(officeId);
    }

    @PostMapping("/offices")
    @ResponseStatus(HttpStatus.CREATED)
    public OfficeCreated create(@AuthenticationPrincipal AuthenticatedUser user,
                                @Valid @RequestBody CreateOfficeRequest request) {
        return platform.createOffice(user, request);
    }

    @PutMapping("/offices/{officeId}")
    public OfficeSummary update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String officeId,
                                @Valid @RequestBody UpdateOfficeRequest request) {
        return platform.update(user, officeId, request);
    }

    @PostMapping("/offices/{officeId}/suspend")
    public OfficeSummary suspend(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String officeId,
                                 @Valid @RequestBody(required = false) SuspendRequest request) {
        return platform.suspend(user, officeId, request == null ? null : request.reason());
    }

    @PostMapping("/offices/{officeId}/reactivate")
    public OfficeSummary reactivate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String officeId) {
        return platform.reactivate(user, officeId);
    }

    @PostMapping("/offices/{officeId}/admins")
    @ResponseStatus(HttpStatus.CREATED)
    public OfficeUser addAdmin(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String officeId,
                               @Valid @RequestBody AdminUserRequest request) {
        return platform.addAdmin(user, officeId, request);
    }

    @PostMapping("/offices/{officeId}/users/{uid}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setPassword(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String officeId,
                            @PathVariable String uid, @Valid @RequestBody PasswordRequest request) {
        platform.setPassword(user, officeId, uid, request.password());
    }

    @PostMapping("/offices/{officeId}/users/{uid}/reset-link")
    public LinkResponse resetLink(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String officeId,
                                  @PathVariable String uid) {
        return new LinkResponse(platform.resetLink(user, officeId, uid));
    }
}
