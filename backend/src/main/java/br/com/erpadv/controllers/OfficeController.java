package br.com.erpadv.controllers;

import br.com.erpadv.models.Office;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.platform.SystemLinksService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Dados e preferências do escritório (Configurações) e atalhos para os sistemas dos tribunais. */
@RestController
@RequestMapping("/api/office")
public class OfficeController {

    public record ModulesRequest(java.util.List<String> enabled) {
    }

    private final SystemLinksService systems;
    private final br.com.erpadv.services.platform.OfficeSettingsService settings;

    public OfficeController(SystemLinksService systems, br.com.erpadv.services.platform.OfficeSettingsService settings) {
        this.systems = systems;
        this.settings = settings;
    }

    @GetMapping
    public br.com.erpadv.services.platform.OfficeSettingsService.Settings get(@AuthenticationPrincipal AuthenticatedUser user) {
        return settings.get(user);
    }

    @PutMapping
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public br.com.erpadv.services.platform.OfficeSettingsService.Settings update(@AuthenticationPrincipal AuthenticatedUser user,
                                                                               @RequestBody br.com.erpadv.services.platform.OfficeSettingsService.SettingsRequest req) {
        return settings.update(user, req);
    }

    @GetMapping("/logo")
    public org.springframework.http.ResponseEntity<byte[]> logo(@AuthenticationPrincipal AuthenticatedUser user) {
        var l = settings.logo(user);
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(l.contentType()))
                .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofMinutes(10)).cachePrivate())
                .body(l.bytes());
    }

    @org.springframework.web.bind.annotation.PostMapping(value = "/logo", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public br.com.erpadv.services.platform.OfficeSettingsService.Settings uploadLogo(@AuthenticationPrincipal AuthenticatedUser user,
                                                                                   @org.springframework.web.bind.annotation.RequestPart("file") org.springframework.web.multipart.MultipartFile file) {
        return settings.uploadLogo(user, DocumentController.bytes(file), file.getOriginalFilename());
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/logo")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public br.com.erpadv.services.platform.OfficeSettingsService.Settings deleteLogo(@AuthenticationPrincipal AuthenticatedUser user) {
        return settings.deleteLogo(user);
    }

    @PutMapping("/modules")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public br.com.erpadv.services.platform.OfficeSettingsService.Settings modules(@AuthenticationPrincipal AuthenticatedUser user,
                                                                                @RequestBody ModulesRequest req) {
        return settings.modules(user, req.enabled());
    }

    @GetMapping("/usage")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public br.com.erpadv.services.platform.OfficeSettingsService.Usage usage(@AuthenticationPrincipal AuthenticatedUser user) {
        return settings.usage(user);
    }

    @GetMapping("/onboarding")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public br.com.erpadv.services.platform.OfficeSettingsService.Onboarding onboarding(@AuthenticationPrincipal AuthenticatedUser user) {
        return settings.onboarding(user);
    }

    @org.springframework.web.bind.annotation.PostMapping("/onboarding/dismiss")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public br.com.erpadv.services.platform.OfficeSettingsService.Onboarding dismiss(@AuthenticationPrincipal AuthenticatedUser user) {
        return settings.dismissOnboarding(user);
    }

    @GetMapping("/systems")
    public List<Office.SystemLink> systems(@AuthenticationPrincipal AuthenticatedUser user) {
        return systems.list(user);
    }

    @PutMapping("/systems")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public List<Office.SystemLink> saveSystems(@AuthenticationPrincipal AuthenticatedUser user,
                                               @RequestBody List<SystemLinksService.LinkInput> links) {
        return systems.save(user, links);
    }
}
