package br.com.erpadv.controllers;

import br.com.erpadv.audit.AuditLog;
import br.com.erpadv.audit.AuditService;
import br.com.erpadv.dto.CursorPage;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.CourtCalendarService;
import br.com.erpadv.services.generation.ReportExporter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
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
                                     @RequestParam(required = false) String module,
                                     @RequestParam(required = false) String action,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                     @RequestParam(required = false) String cursor,
                                     @RequestParam(defaultValue = "50") int size) {
        return audit.list(user.requireOffice(), filter(entityType, entityId, actorUid, module, action, from, to), cursor, Math.min(size, 100));
    }

    /** Exportação da consulta filtrada (até 5.000 registros) em PDF ou Excel, com antes/depois das alterações. */
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('PERM_AUDIT_READ')")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal AuthenticatedUser user,
                                         @RequestParam(required = false) String entityType,
                                         @RequestParam(required = false) String entityId,
                                         @RequestParam(required = false) String actorUid,
                                         @RequestParam(required = false) String module,
                                         @RequestParam(required = false) String action,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(defaultValue = "pdf") String format) {
        String officeId = user.requireOffice();
        AuditService.Filter f = filter(entityType, entityId, actorUid, module, action, from, to);
        List<AuditLog> rows = new ArrayList<>();
        String cursor = null;
        do {
            CursorPage<AuditLog> page = audit.list(officeId, f, cursor, 500);
            rows.addAll(page.items());
            cursor = page.nextCursor();
        } while (cursor != null && rows.size() < 5000);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(CourtCalendarService.ZONE);
        List<List<String>> table = new ArrayList<>();
        for (AuditLog a : rows) {
            String changes = a.getChanges() == null ? "" : a.getChanges().stream()
                    .map(c -> c.getField() + ": " + c.getBefore() + " → " + c.getAfter()).collect(Collectors.joining("; "));
            table.add(List.of(String.valueOf(a.getSeq()), a.getAt() == null ? "" : fmt.format(a.getAt()),
                    nz(a.getActorName() != null ? a.getActorName() : a.getActorEmail() == null ? a.getActorUid() : a.getActorEmail()),
                    nz(a.getModule()), nz(a.getAction()), nz(a.getSummary()), changes, nz(a.getJustification()), nz(a.getOrigin()), nz(a.getIp())));
        }
        ReportExporter.Report report = new ReportExporter.Report("Trilha de auditoria", null,
                rows.size() + " registro(s)" + (rows.size() >= 5000 ? " — limite de exportação atingido, refine os filtros" : ""),
                List.of(ReportExporter.Section.table(null,
                        List.of("Nº", "Data/hora", "Usuário", "Módulo", "Ação", "Descrição", "Antes → depois", "Justificativa", "Origem", "IP"),
                        table)), true);
        ReportExporter.Exported exported = ReportExporter.export(report, "xlsx".equalsIgnoreCase(format) ? "xlsx" : "pdf", "auditoria");
        audit.record(user, "AUDIT_EXPORTED", "audit", null, "Auditoria exportada (" + rows.size() + " registros, " + format + ")");
        return DashboardController.file(exported);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static AuditService.Filter filter(String entityType, String entityId, String actorUid, String module, String action,
                                              LocalDate from, LocalDate to) {
        return new AuditService.Filter(blank(entityType), blank(entityId), blank(actorUid), blank(module), blank(action),
                from == null ? null : from.atStartOfDay(CourtCalendarService.ZONE).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(CourtCalendarService.ZONE).toInstant().minusMillis(1));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
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
