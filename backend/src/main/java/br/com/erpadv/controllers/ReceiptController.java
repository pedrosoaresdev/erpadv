package br.com.erpadv.controllers;

import br.com.erpadv.models.Receipt;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.ReceiptService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Recibos e declarações para clientes. Recibos com valor exigem acesso ao financeiro; os de entrega/devolução de documentos,
 * acesso à central documental (verificado no serviço por tipo).
 */
@RestController
@RequestMapping("/api/receipts")
@PreAuthorize("hasAuthority('PERM_FINANCE_READ') or hasAuthority('PERM_DOCUMENTS_READ')")
public class ReceiptController {

    public record CancelRequest(String reason) {
    }

    private final ReceiptService receipts;

    public ReceiptController(ReceiptService receipts) {
        this.receipts = receipts;
    }

    @GetMapping
    public List<Receipt> list(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam(required = false) String clientId) {
        return receipts.list(user, clientId);
    }

    @GetMapping("/pending")
    public List<ReceiptService.PendingRevenue> pending(@AuthenticationPrincipal AuthenticatedUser user) {
        return receipts.pending(user);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Receipt create(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody ReceiptService.ReceiptRequest req) {
        return receipts.create(user, req);
    }

    @PostMapping("/from-transaction")
    @ResponseStatus(HttpStatus.CREATED)
    public Receipt fromTransaction(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody ReceiptService.FromTransactionRequest req) {
        return receipts.fromTransaction(user, req);
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return DashboardController.file(receipts.pdf(user, id));
    }

    @PostMapping("/{id}/cancel")
    public Receipt cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id, @RequestBody CancelRequest req) {
        return receipts.cancel(user, id, req.reason());
    }
}
