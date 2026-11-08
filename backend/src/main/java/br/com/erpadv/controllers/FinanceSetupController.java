package br.com.erpadv.controllers;

import br.com.erpadv.dto.FinanceDtos.BankAccountRequest;
import br.com.erpadv.dto.FinanceDtos.BankAccountResponse;
import br.com.erpadv.dto.FinanceDtos.CollectionDraft;
import br.com.erpadv.dto.FinanceDtos.CollectionRequest;
import br.com.erpadv.dto.FinanceDtos.CollectionResponse;
import br.com.erpadv.dto.FinanceDtos.CostCenterRequest;
import br.com.erpadv.dto.FinanceDtos.CostCenterResponse;
import br.com.erpadv.dto.FinanceDtos.StatementResponse;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.FinanceSetupService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.LocalDate;
import java.util.List;

/** Centros de custo, contas bancárias (saldo e extrato), recibos e cobranças. */
@RestController
@RequestMapping("/api/finance")
public class FinanceSetupController {

    private final FinanceSetupService setup;

    public FinanceSetupController(FinanceSetupService setup) {
        this.setup = setup;
    }

    @GetMapping("/cost-centers")
    @PreAuthorize("hasAuthority('PERM_FINANCE_READ')")
    public List<CostCenterResponse> costCenters(@AuthenticationPrincipal AuthenticatedUser user) {
        return setup.costCenters(user);
    }

    @PostMapping("/cost-centers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_FINANCE_WRITE')")
    public CostCenterResponse createCostCenter(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody CostCenterRequest req) {
        return setup.saveCostCenter(user, null, req);
    }

    @PutMapping("/cost-centers/{id}")
    @PreAuthorize("hasAuthority('PERM_FINANCE_WRITE')")
    public CostCenterResponse updateCostCenter(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                               @Valid @RequestBody CostCenterRequest req) {
        return setup.saveCostCenter(user, id, req);
    }

    @DeleteMapping("/cost-centers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_FINANCE_WRITE')")
    public void deleteCostCenter(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        setup.deleteCostCenter(user, id);
    }

    @GetMapping("/bank-accounts")
    @PreAuthorize("hasAuthority('PERM_FINANCE_READ')")
    public List<BankAccountResponse> accounts(@AuthenticationPrincipal AuthenticatedUser user) {
        return setup.accounts(user);
    }

    @PostMapping("/bank-accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_FINANCE_WRITE')")
    public BankAccountResponse createAccount(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody BankAccountRequest req) {
        return setup.saveAccount(user, null, req);
    }

    @PutMapping("/bank-accounts/{id}")
    @PreAuthorize("hasAuthority('PERM_FINANCE_WRITE')")
    public BankAccountResponse updateAccount(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                             @Valid @RequestBody BankAccountRequest req) {
        return setup.saveAccount(user, id, req);
    }

    @DeleteMapping("/bank-accounts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_FINANCE_WRITE')")
    public void deleteAccount(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        setup.deleteAccount(user, id);
    }

    @GetMapping("/bank-accounts/{id}/statement")
    @PreAuthorize("hasAuthority('PERM_FINANCE_READ')")
    public StatementResponse statement(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return setup.statement(user, id, from, to);
    }

    @GetMapping("/transactions/{id}/recibo")
    @PreAuthorize("hasAuthority('PERM_FINANCE_READ')")
    public ResponseEntity<byte[]> receipt(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return DashboardController.file(setup.receipt(user, id));
    }

    @GetMapping("/collections")
    @PreAuthorize("hasAuthority('PERM_FINANCE_READ')")
    public List<CollectionResponse> collections(@AuthenticationPrincipal AuthenticatedUser user,
                                                @RequestParam(required = false) String clientId) {
        return setup.collections(user, clientId);
    }

    @GetMapping("/collections/draft")
    @PreAuthorize("hasAuthority('PERM_FINANCE_READ')")
    public CollectionDraft draft(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam String clientId) {
        return setup.draft(user, clientId);
    }

    @PostMapping("/collections")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_FINANCE_WRITE')")
    public CollectionResponse registerCollection(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody CollectionRequest req) {
        return setup.registerCollection(user, req);
    }
}
