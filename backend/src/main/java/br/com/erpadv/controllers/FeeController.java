package br.com.erpadv.controllers;

import br.com.erpadv.dto.FeeDtos.CommissionStatement;
import br.com.erpadv.dto.FeeDtos.FeeItem;
import br.com.erpadv.dto.FeeDtos.FeeSummary;
import br.com.erpadv.dto.FeeDtos.GroupBy;
import br.com.erpadv.dto.FeeDtos.PayoutRequest;
import br.com.erpadv.dto.FeeDtos.PayoutResponse;
import br.com.erpadv.dto.FeeDtos.RuleRequest;
import br.com.erpadv.dto.FeeDtos.RuleResponse;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.FeeService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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

import java.time.LocalDate;
import java.util.List;

/**
 * Honorários e comissões. Visão geral e regras: FINANCE_READ/FINANCE_WRITE. "Minhas comissões": qualquer integrante
 * da equipe, somente as próprias.
 */
@RestController
@RequestMapping("/api/fees")
public class FeeController {

    private final FeeService fees;

    public FeeController(FeeService fees) {
        this.fees = fees;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('PERM_FINANCE_READ')")
    public FeeSummary summary(@AuthenticationPrincipal AuthenticatedUser user,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                              @RequestParam(defaultValue = "CLIENT") GroupBy groupBy) {
        return fees.summary(user, from, to, groupBy);
    }

    @GetMapping("/items")
    @PreAuthorize("hasAuthority('PERM_FINANCE_READ')")
    public List<FeeItem> items(@AuthenticationPrincipal AuthenticatedUser user,
                               @RequestParam(required = false) String clientId,
                               @RequestParam(required = false) String caseId,
                               @RequestParam(required = false) String contractId) {
        return fees.items(user, clientId, caseId, contractId);
    }

    @GetMapping("/commission-rules")
    @PreAuthorize("hasAuthority('PERM_FINANCE_READ')")
    public List<RuleResponse> rules(@AuthenticationPrincipal AuthenticatedUser user) {
        return fees.rules(user);
    }

    @PostMapping("/commission-rules")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_FINANCE_WRITE')")
    public RuleResponse createRule(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody RuleRequest req) {
        return fees.saveRule(user, null, req);
    }

    @PutMapping("/commission-rules/{id}")
    @PreAuthorize("hasAuthority('PERM_FINANCE_WRITE')")
    public RuleResponse updateRule(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id, @Valid @RequestBody RuleRequest req) {
        return fees.saveRule(user, id, req);
    }

    @DeleteMapping("/commission-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_FINANCE_WRITE')")
    public void deleteRule(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        fees.deleteRule(user, id);
    }

    @GetMapping("/commissions")
    @PreAuthorize("hasAuthority('PERM_FINANCE_READ')")
    public List<CommissionStatement> commissions(@AuthenticationPrincipal AuthenticatedUser user,
                                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return fees.commissions(user, from, to, null);
    }

    @GetMapping("/commissions/mine")
    @PreAuthorize("hasAuthority('PERM_DASHBOARD_VIEW')")
    public List<CommissionStatement> mine(@AuthenticationPrincipal AuthenticatedUser user,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return fees.commissions(user, from, to, user.uid());
    }

    @GetMapping("/commissions/payouts")
    @PreAuthorize("hasAuthority('PERM_FINANCE_READ')")
    public List<PayoutResponse> payouts(@AuthenticationPrincipal AuthenticatedUser user) {
        return fees.payouts(user, null);
    }

    @PostMapping("/commissions/payouts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_FINANCE_WRITE')")
    public PayoutResponse payout(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody PayoutRequest req) {
        return fees.payout(user, req);
    }
}
