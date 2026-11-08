package br.com.erpadv.controllers;

import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.ReportCenterService;
import br.com.erpadv.services.ReportCenterService.ReportDef;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Central de relatórios (REPORTS_VIEW + permissão do módulo de cada relatório). */
@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasAuthority('PERM_REPORTS_VIEW')")
public class ReportCenterController {

    private final ReportCenterService reports;

    public ReportCenterController(ReportCenterService reports) {
        this.reports = reports;
    }

    @GetMapping
    public List<ReportDef> catalog(@AuthenticationPrincipal AuthenticatedUser user) {
        return reports.catalog(user);
    }

    @GetMapping("/{key}")
    public ResponseEntity<byte[]> generate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String key,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                           @RequestParam(required = false) String lawyerUid,
                                           @RequestParam(defaultValue = "pdf") String format) {
        return DashboardController.file(reports.generate(user, key, from, to, lawyerUid, format));
    }
}
