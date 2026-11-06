package br.com.erpadv.controllers;

import br.com.erpadv.dto.DashboardDtos.DashboardResponse;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.DashboardService;
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
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return dashboard.get(user, from, to);
    }
}
