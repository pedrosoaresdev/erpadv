package br.com.erpadv.controllers;

import br.com.erpadv.audit.AuditLog;
import br.com.erpadv.audit.AuditService;
import br.com.erpadv.dto.CursorPage;
import br.com.erpadv.security.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Consulta da trilha de auditoria. Não há endpoints de alteração ou exclusão — por projeto. */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_AUDIT_READ')")
    public CursorPage<AuditLog> list(@AuthenticationPrincipal AuthenticatedUser user,
                                     @RequestParam(required = false) String entityType,
                                     @RequestParam(required = false) String entityId,
                                     @RequestParam(required = false) String actorUid,
                                     @RequestParam(required = false) String cursor,
                                     @RequestParam(defaultValue = "50") int size) {
        return audit.list(user.requireOffice(), entityType, entityId, actorUid, cursor, size);
    }

    @GetMapping("/verify")
    @PreAuthorize("hasAuthority('PERM_AUDIT_READ')")
    public AuditService.VerifyResult verify(@AuthenticationPrincipal AuthenticatedUser user) {
        AuditService.VerifyResult result = audit.verify(user.requireOffice());
        audit.record(user, "AUDIT_CHAIN_VERIFIED", "audit", null,
                result.valid() ? "Cadeia íntegra (" + result.checked() + " registros)" : "Falha: " + result.message());
        return result;
    }
}
