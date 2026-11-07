package br.com.erpadv.controllers;

import br.com.erpadv.dto.DashboardDtos.DashboardResponse;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.DashboardService;
import br.com.erpadv.services.generation.ReportExporter;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.nio.charset.StandardCharsets;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/api/dashboard")
    @PreAuthorize("hasAuthority('PERM_DASHBOARD_VIEW')")
    public DashboardResponse get(@AuthenticationPrincipal AuthenticatedUser user,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 @RequestParam(required = false) String lawyerUid) {
        return dashboard.get(user, from, to, lawyerUid);
    }

    /** Exporta o painel (PDF ou Excel) com o mesmo período e filtro de advogado. */
    @GetMapping("/api/dashboard/export")
    @PreAuthorize("hasAuthority('PERM_DASHBOARD_VIEW')")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal AuthenticatedUser user,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) String lawyerUid,
                                         @RequestParam(defaultValue = "pdf") String format) {
        return file(dashboard.export(user, from, to, lawyerUid, format));
    }

    static ResponseEntity<byte[]> file(ReportExporter.Exported file) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(file.bytes());
    }
}
