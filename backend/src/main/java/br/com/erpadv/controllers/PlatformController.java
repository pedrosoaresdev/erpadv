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

    public PlatformController(PlatformService platform) {
        this.platform = platform;
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
