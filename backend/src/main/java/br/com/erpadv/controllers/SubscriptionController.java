package br.com.erpadv.controllers;

import br.com.erpadv.models.Plan;
import br.com.erpadv.models.SubscriptionInvoice;
import br.com.erpadv.models.SupportTicket;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.platform.BillingService;
import br.com.erpadv.services.platform.SupportService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/** Plano e Assinatura (administração do escritório) e Suporte (toda a equipe). */
@RestController
@RequestMapping("/api")
public class SubscriptionController {

    public record ChangeRequest(String planCode, String billingCycle, String message) {
    }

    public record Overview(BillingService.SubscriptionView subscription, List<SubscriptionInvoice> invoices, List<Plan> plans,
                           BillingService.BillingSettings payment) {
    }

    private final BillingService billing;
    private final SupportService support;

    public SubscriptionController(BillingService billing, SupportService support) {
        this.billing = billing;
        this.support = support;
    }

    @GetMapping("/subscription")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public Overview subscription(@AuthenticationPrincipal AuthenticatedUser user) {
        String officeId = user.requireOffice();
        return new Overview(billing.view(officeId), billing.list(officeId, null),
                billing.plans().stream().filter(Plan::isActive).toList(), billing.settings());
    }

    @GetMapping("/subscription/invoices/{id}/pdf")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public ResponseEntity<byte[]> invoicePdf(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return DashboardController.file(billing.pdf(billing.invoiceOf(user, id)));
    }

    /** Pedido de mudança de plano: vira um chamado para a plataforma (a troca é aplicada pelo painel master). */
    @PostMapping("/subscription/change-request")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    @ResponseStatus(HttpStatus.CREATED)
    public SupportTicket changeRequest(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody ChangeRequest req) {
        String cycle = req.billingCycle() == null ? "" : " (" + req.billingCycle().toLowerCase(Locale.ROOT) + ")";
        String extra = req.message() == null || req.message().isBlank() ? "" : "\n\n" + req.message().trim();
        String text = "Solicito a mudança para o plano " + req.planCode() + cycle + "." + extra;
        return support.open(user, new SupportService.OpenRequest("Mudança de plano: " + req.planCode(), "PLANO", "NORMAL", text));
    }

    @GetMapping("/support/tickets")
    public List<SupportTicket> tickets(@AuthenticationPrincipal AuthenticatedUser user) {
        return support.mine(user);
    }

    @PostMapping("/support/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public SupportTicket open(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody SupportService.OpenRequest req) {
        return support.open(user, req);
    }

    @GetMapping("/support/tickets/{id}")
    public SupportTicket ticket(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return support.get(user, id);
    }

    @PostMapping("/support/tickets/{id}/reply")
    public SupportTicket reply(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id, @RequestBody SupportService.ReplyRequest req) {
        return support.reply(user, id, req);
    }
}
