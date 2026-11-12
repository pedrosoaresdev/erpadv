package br.com.erpadv.controllers;

import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.models.SecurityEvent;
import br.com.erpadv.models.SecurityPolicy;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.models.UserSession;
import br.com.erpadv.repositories.UserRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.security.MfaService;
import br.com.erpadv.services.security.SecurityEventService;
import br.com.erpadv.services.security.SecurityPolicyService;
import br.com.erpadv.services.security.SessionService;
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

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Segurança da conta e do escritório: 2FA, sessões e dispositivos, eventos de segurança e política. */
@RestController
@RequestMapping("/api/security")
public class SecurityController {

    public record CodeRequest(String code) {
    }

    public record SessionView(String id, String uid, String userName, String device, boolean mobile, String ip, Instant authTime,
                              Instant lastSeenAt, boolean newDevice, boolean mfaVerified, boolean current) {
        static SessionView of(UserSession s, String current) {
            return new SessionView(s.getId(), s.getUid(), s.getUserName(), s.getDevice(), s.isMobile(), s.getIp(), s.getAuthTime(),
                    s.getLastSeenAt(), s.isNewDevice(), s.isMfaVerified(), s.getId().equals(current));
        }
    }

    public record RestoreRequest(List<String> collections) {
    }

    public record MemberMfa(String uid, String name, String email, String role, boolean mfaEnabled, Instant mfaEnabledAt) {
    }

    private final SessionService sessions;
    private final MfaService mfa;
    private final SecurityPolicyService policies;
    private final SecurityEventService events;
    private final UserRepository users;
    @org.springframework.beans.factory.annotation.Autowired
    private br.com.erpadv.services.security.BackupService backups;

    public SecurityController(SessionService sessions, MfaService mfa, SecurityPolicyService policies, SecurityEventService events,
                              UserRepository users) {
        this.sessions = sessions;
        this.mfa = mfa;
        this.policies = policies;
        this.events = events;
        this.users = users;
    }

    @GetMapping("/session")
    public SessionService.SessionStatus session(@AuthenticationPrincipal AuthenticatedUser user) {
        UserProfile p = users.findById(user.uid()).orElseThrow(() -> new NotFoundException("Perfil"));
        return sessions.status(user, p);
    }

    // ------------------------------------------------------------------ 2FA

    @PostMapping("/mfa/setup")
    public MfaService.Setup setup(@AuthenticationPrincipal AuthenticatedUser user) {
        return mfa.setup(user);
    }

    @PostMapping("/mfa/confirm")
    public MfaService.Enabled confirm(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody CodeRequest req) {
        return mfa.confirm(user, req.code());
    }

    @PostMapping("/mfa/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verify(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody CodeRequest req) {
        mfa.verify(user, req.code());
    }

    @PostMapping("/mfa/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody CodeRequest req) {
        mfa.disable(user, req.code());
    }

    @PostMapping("/mfa/recovery-codes")
    public MfaService.Enabled recovery(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody CodeRequest req) {
        return mfa.regenerateRecovery(user, req.code());
    }

    @GetMapping("/users/mfa")
    @PreAuthorize("hasAuthority('PERM_EMPLOYEES_MANAGE') or hasAuthority('PERM_SETTINGS_MANAGE')")
    public List<MemberMfa> team(@AuthenticationPrincipal AuthenticatedUser user) {
        return users.activeStaff(user.requireOffice()).stream()
                .map(u -> new MemberMfa(u.getId(), u.getDisplayName(), u.getEmail(), u.getCustomRoleName() != null ? u.getCustomRoleName()
                        : u.getRole() == null ? null : u.getRole().label(), u.isMfaEnabled(), u.getMfaEnabledAt()))
                .sorted(Comparator.comparing(MemberMfa::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))).toList();
    }

    @PostMapping("/users/{uid}/mfa/reset")
    @PreAuthorize("hasAuthority('PERM_EMPLOYEES_MANAGE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String uid) {
        mfa.reset(user, uid);
    }

    // ------------------------------------------------------------------ sessões e dispositivos

    @GetMapping("/sessions/mine")
    public List<SessionView> mine(@AuthenticationPrincipal AuthenticatedUser user) {
        String current = SessionService.currentSessionId();
        return sessions.mine(user).stream().map(s -> SessionView.of(s, current)).toList();
    }

    @DeleteMapping("/sessions/mine/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void endMine(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        sessions.revoke(user, id, false);
    }

    @PostMapping("/sessions/mine/end-others")
    public Map<String, Integer> endOthers(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("ended", sessions.revokeOthers(user));
    }

    @GetMapping("/sessions")
    @PreAuthorize("hasAuthority('PERM_EMPLOYEES_MANAGE') or hasAuthority('PERM_SETTINGS_MANAGE')")
    public List<SessionView> office(@AuthenticationPrincipal AuthenticatedUser user) {
        String current = SessionService.currentSessionId();
        return sessions.office(user).stream().map(s -> SessionView.of(s, current)).toList();
    }

    @DeleteMapping("/sessions/{id}")
    @PreAuthorize("hasAuthority('PERM_EMPLOYEES_MANAGE') or hasAuthority('PERM_SETTINGS_MANAGE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void end(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        sessions.revoke(user, id, true);
    }

    // ------------------------------------------------------------------ eventos e política

    @GetMapping("/events")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE') or hasAuthority('PERM_AUDIT_READ')")
    public List<SecurityEvent> events(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam(required = false) String uid,
                                      @RequestParam(required = false) SecurityEvent.Severity severity,
                                      @RequestParam(defaultValue = "200") int size) {
        return events.list(user.requireOffice(), uid, severity, size);
    }

    @GetMapping("/policy")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE') or hasAuthority('PERM_AUDIT_READ')")
    public SecurityPolicy policy(@AuthenticationPrincipal AuthenticatedUser user) {
        return policies.get(user.requireOffice());
    }

    @PutMapping("/policy")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public SecurityPolicy savePolicy(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody SecurityPolicyService.PolicyRequest req) {
        return policies.save(user, req);
    }

    // ------------------------------------------------------------------ backup e recuperação

    @GetMapping("/backups")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public List<br.com.erpadv.models.Backup> backups(@AuthenticationPrincipal AuthenticatedUser user) {
        return backups.list(user);
    }

    @PostMapping("/backups")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    @ResponseStatus(HttpStatus.CREATED)
    public br.com.erpadv.models.Backup backupNow(@AuthenticationPrincipal AuthenticatedUser user) {
        return backups.manual(user);
    }

    @GetMapping("/backups/{id}/download")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public org.springframework.http.ResponseEntity<byte[]> downloadBackup(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        byte[] data = backups.download(user, id);
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType("application/gzip"))
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, org.springframework.http.ContentDisposition.attachment()
                        .filename("erpadv-backup-" + id + ".jsonl.gz").build().toString())
                .body(data);
    }

    @GetMapping("/backups/{id}/inspect")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public br.com.erpadv.services.security.BackupService.Inspection inspect(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return backups.inspect(user, id);
    }

    @PostMapping("/backups/{id}/restore")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public br.com.erpadv.services.security.BackupService.RestoreResult restore(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                                                             @RequestBody RestoreRequest req) {
        return backups.restore(user, id, req.collections());
    }
}
