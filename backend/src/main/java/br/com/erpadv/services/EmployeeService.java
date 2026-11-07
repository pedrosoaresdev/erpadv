package br.com.erpadv.services;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.config.AppProperties;
import br.com.erpadv.dto.CursorPage;
import br.com.erpadv.dto.EmployeeDtos.EmployeeOption;
import br.com.erpadv.dto.EmployeeDtos.EmployeeResponse;
import br.com.erpadv.dto.EmployeeDtos.InviteRequest;
import br.com.erpadv.dto.EmployeeDtos.InviteResponse;
import br.com.erpadv.dto.EmployeeDtos.PermissionInfo;
import br.com.erpadv.dto.EmployeeDtos.RoleInfo;
import br.com.erpadv.dto.EmployeeDtos.UpdateEmployeeRequest;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.ConflictException;
import br.com.erpadv.exceptions.DataAccessFailure;
import br.com.erpadv.exceptions.ForbiddenException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.firebase.CryptoService;
import br.com.erpadv.firebase.SearchTokens;
import br.com.erpadv.models.CustomRole;
import br.com.erpadv.models.Office;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.models.UserStatus;
import br.com.erpadv.repositories.OfficeRepository;
import br.com.erpadv.repositories.UserRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Permission;
import br.com.erpadv.security.PermissionResolver;
import br.com.erpadv.security.Role;
import br.com.erpadv.security.RolePermissions;
import br.com.erpadv.security.UserProfileCache;
import br.com.erpadv.validation.BrazilianDocuments;
import br.com.erpadv.validation.PasswordPolicy;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Gestão de equipe: cadastro (com senha inicial ou link), papéis e cargos personalizados, permissões
 * granulares, bloqueio, revogação de sessões e limite de usuários do plano.
 */
@Service
public class EmployeeService {

    /** Permissões que não podem ser concedidas individualmente a ninguém da equipe. */
    static final Set<Permission> NON_GRANTABLE = EnumSet.of(Permission.PLATFORM_ADMIN, Permission.PORTAL_ACCESS);

    private final UserRepository users;
    private final OfficeRepository offices;
    private final FirebaseAuth firebaseAuth;
    private final UserProfileCache profileCache;
    private final PermissionResolver resolver;
    private final CryptoService crypto;
    private final AuditService audit;
    private final AppProperties properties;

    public EmployeeService(UserRepository users, OfficeRepository offices, FirebaseAuth firebaseAuth,
                           UserProfileCache profileCache, PermissionResolver resolver, CryptoService crypto,
                           AuditService audit, AppProperties properties) {
        this.users = users;
        this.offices = offices;
        this.firebaseAuth = firebaseAuth;
        this.profileCache = profileCache;
        this.resolver = resolver;
        this.crypto = crypto;
        this.audit = audit;
        this.properties = properties;
    }

    public CursorPage<EmployeeResponse> list(AuthenticatedUser user, String cursor, int size) {
        return users.byOffice(user.requireOffice(), cursor, size).map(this::response);
    }

    public List<EmployeeOption> lawyers(AuthenticatedUser user) {
        return users.activeStaff(user.requireOffice()).stream()
                .filter(u -> u.getRole().isLawyer())
                .sorted(Comparator.comparing(u -> u.getDisplayNameLower() == null ? "" : u.getDisplayNameLower()))
                .map(u -> new EmployeeOption(u.getId(), u.getDisplayName(), u.getRole(), u.getOabNumber()))
                .toList();
    }

    public List<EmployeeOption> staff(AuthenticatedUser user) {
        return users.activeStaff(user.requireOffice()).stream()
                .sorted(Comparator.comparing(u -> u.getDisplayNameLower() == null ? "" : u.getDisplayNameLower()))
                .map(u -> new EmployeeOption(u.getId(), u.getDisplayName(), u.getRole(), u.getOabNumber()))
                .toList();
    }

    public EmployeeResponse get(AuthenticatedUser user, String uid) {
        return response(load(user, uid));
    }

    public InviteResponse invite(AuthenticatedUser actor, InviteRequest req) {
        String officeId = actor.requireOffice();
        CustomRole customRole = customRole(officeId, req.customRoleId());
        Role role = customRole != null ? customRole.getBaseRole() : req.role();
        if (role == null) {
            throw new BusinessException("ROLE_REQUIRED", "Informe o papel ou o cargo do integrante.");
        }
        if (role == Role.CLIENTE) {
            throw new BusinessException("INVALID_ROLE", "Clientes são convidados pelo portal do cliente.");
        }
        assertCanAssign(actor, role);
        assertCanAssignCustom(actor, customRole);
        if (req.password() != null && !req.password().isBlank()) {
            PasswordPolicy.check(req.password());
        }
        String cpf = optionalCpf(req.cpf());
        if (cpf != null && users.existsCpfInOffice(officeId, crypto.hmac(cpf))) {
            throw new ConflictException("CPF_IN_USE", "Já existe um integrante com este CPF.");
        }
        assertSeat(offices.findById(officeId).orElseThrow(() -> new NotFoundException("Escritório")));

        String email = req.email().trim().toLowerCase(Locale.ROOT);
        String password = req.password() == null || req.password().isBlank() ? null : req.password();
        String uid = createOrAttachAuthUser(email, req.displayName().trim(), password);

        Instant now = Instant.now();
        UserProfile profile = new UserProfile();
        profile.setId(uid);
        profile.setOfficeId(officeId);
        profile.setEmail(email);
        profile.setDisplayName(req.displayName().trim());
        profile.setDisplayNameLower(SearchTokens.normalize(req.displayName()));
        profile.setRole(role);
        applyCustomRole(profile, customRole);
        profile.setStatus(UserStatus.INVITED);
        profile.setOabNumber(blankToNull(req.oabNumber()));
        profile.setPhone(blankToNull(req.phone()));
        profile.setPosition(blankToNull(req.position()) != null ? blankToNull(req.position())
                : customRole != null ? customRole.getName() : null);
        applyCpf(profile, cpf);
        profile.setInvitedAt(now);
        profile.setInvitedBy(actor.uid());
        profile.setCreatedAt(now);
        profile.setUpdatedAt(now);
        profile.setCreatedBy(actor.uid());
        users.save(profile);
        setClaims(uid, officeId, role);

        String link = password == null ? passwordLink(email) : null;
        Map<String, String> details = new HashMap<>();
        details.put("role", role.name());
        details.put("customRole", customRole == null ? "" : customRole.getName());
        details.put("initialPassword", password == null ? "nao" : "sim");
        audit.record(actor, "EMPLOYEE_INVITED", "user", uid, "Integrante cadastrado: " + email, details);
        return new InviteResponse(response(profile), link);
    }

    public EmployeeResponse update(AuthenticatedUser actor, String uid, UpdateEmployeeRequest req) {
        UserProfile profile = load(actor, uid);
        assertCanManage(actor, profile);
        CustomRole customRole = customRole(profile.getOfficeId(), req.customRoleId());
        Role newRole = customRole != null ? customRole.getBaseRole() : req.role() != null ? req.role() : profile.getRole();
        boolean customChanged = !Objects.equals(req.customRoleId() == null || req.customRoleId().isBlank()
                ? null : req.customRoleId(), profile.getCustomRoleId());
        if (newRole != profile.getRole() || customChanged) {
            if (actor.uid().equals(uid)) {
                throw new BusinessException("SELF_ROLE_CHANGE", "Você não pode alterar o próprio papel ou cargo.");
            }
            if (newRole == Role.CLIENTE) {
                throw new BusinessException("INVALID_ROLE", "Papel inválido para integrante da equipe.");
            }
            assertCanAssign(actor, newRole);
            assertCanAssignCustom(actor, customRole);
        }
        List<Permission> granted = sanitize(req.permissionsGranted());
        List<Permission> revoked = sanitize(req.permissionsRevoked());
        boolean permissionsChanged = !granted.equals(profile.getPermissionsGranted())
                || !revoked.equals(profile.getPermissionsRevoked());
        if (permissionsChanged && !actor.has(Permission.EMPLOYEES_MANAGE)) {
            throw new ForbiddenException("ACCESS_DENIED", "Sem permissão para alterar permissões.");
        }
        // Ninguém concede a outro uma permissão que ele próprio não possui.
        for (Permission p : granted) {
            if (!actor.has(p)) {
                throw new ForbiddenException("GRANT_DENIED", "Você não pode conceder a permissão " + p.name() + ".");
            }
        }
        String cpf = optionalCpf(req.cpf());
        if (cpf != null && !crypto.hmac(cpf).equals(profile.getCpfHash())
                && users.existsCpfInOffice(profile.getOfficeId(), crypto.hmac(cpf))) {
            throw new ConflictException("CPF_IN_USE", "Já existe um integrante com este CPF.");
        }
        Role previousRole = profile.getRole();
        String previousCustom = profile.getCustomRoleName();
        profile.setDisplayName(req.displayName().trim());
        profile.setDisplayNameLower(SearchTokens.normalize(req.displayName()));
        profile.setRole(newRole);
        applyCustomRole(profile, customRole);
        profile.setOabNumber(blankToNull(req.oabNumber()));
        profile.setPhone(blankToNull(req.phone()));
        profile.setPosition(blankToNull(req.position()));
        if (cpf != null) {
            applyCpf(profile, cpf);
        }
        profile.setPermissionsGranted(granted);
        profile.setPermissionsRevoked(revoked);
        profile.setUpdatedAt(Instant.now());
        profile.setUpdatedBy(actor.uid());
        users.save(profile);
        if (previousRole != newRole) {
            setClaims(uid, profile.getOfficeId(), newRole);
        }
        profileCache.evict(uid);
        Map<String, String> details = new HashMap<>();
        details.put("role", previousRole + "->" + newRole);
        details.put("customRole", (previousCustom == null ? "-" : previousCustom) + "->"
                + (profile.getCustomRoleName() == null ? "-" : profile.getCustomRoleName()));
        details.put("granted", granted.toString());
        details.put("revoked", revoked.toString());
        audit.record(actor, "EMPLOYEE_UPDATED", "user", uid, "Acesso atualizado: " + profile.getEmail(), details);
        return response(profile);
    }

    /** O administrador define diretamente a senha de um integrante (encerra as sessões abertas). */
    public EmployeeResponse setPassword(AuthenticatedUser actor, String uid, String password) {
        UserProfile profile = load(actor, uid);
        assertCanManage(actor, profile);
        PasswordPolicy.check(password);
        try {
            firebaseAuth.updateUser(new UserRecord.UpdateRequest(uid).setPassword(password));
            if (!actor.uid().equals(uid)) {
                firebaseAuth.revokeRefreshTokens(uid);
            }
        } catch (FirebaseAuthException e) {
            throw new DataAccessFailure("Falha ao definir a senha no provedor de identidade.", e);
        }
        if (!actor.uid().equals(uid)) {
            profile.setTokensRevokedAt(Instant.now().plusSeconds(1));
        }
        profile.setUpdatedAt(Instant.now());
        profile.setUpdatedBy(actor.uid());
        users.save(profile);
        profileCache.evict(uid);
        audit.record(actor, "EMPLOYEE_PASSWORD_SET", "user", uid, "Senha definida pelo administrador: " + profile.getEmail());
        return response(profile);
    }

    public EmployeeResponse block(AuthenticatedUser actor, String uid, String reason) {
        UserProfile profile = load(actor, uid);
        assertCanManage(actor, profile);
        if (actor.uid().equals(uid)) {
            throw new BusinessException("SELF_BLOCK", "Você não pode bloquear a própria conta.");
        }
        if (profile.getStatus() == UserStatus.BLOCKED) {
            throw new BusinessException("ALREADY_BLOCKED", "Conta já está bloqueada.");
        }
        try {
            firebaseAuth.updateUser(new UserRecord.UpdateRequest(uid).setDisabled(true));
            firebaseAuth.revokeRefreshTokens(uid);
        } catch (FirebaseAuthException e) {
            throw new DataAccessFailure("Falha ao bloquear a conta no provedor de identidade.", e);
        }
        Instant now = Instant.now();
        profile.setStatus(UserStatus.BLOCKED);
        profile.setBlockedAt(now);
        profile.setBlockedReason(blankToNull(reason));
        profile.setTokensRevokedAt(now);
        profile.setUpdatedAt(now);
        profile.setUpdatedBy(actor.uid());
        users.save(profile);
        profileCache.evict(uid);
        audit.record(actor, "EMPLOYEE_BLOCKED", "user", uid, "Conta bloqueada: " + profile.getEmail(),
                Map.of("reason", reason == null ? "" : reason));
        return response(profile);
    }

    public EmployeeResponse unblock(AuthenticatedUser actor, String uid) {
        UserProfile profile = load(actor, uid);
        assertCanManage(actor, profile);
        if (profile.getStatus() != UserStatus.BLOCKED) {
            throw new BusinessException("NOT_BLOCKED", "Conta não está bloqueada.");
        }
        assertSeat(offices.findById(profile.getOfficeId()).orElseThrow(() -> new NotFoundException("Escritório")));
        try {
            firebaseAuth.updateUser(new UserRecord.UpdateRequest(uid).setDisabled(false));
        } catch (FirebaseAuthException e) {
            throw new DataAccessFailure("Falha ao desbloquear a conta no provedor de identidade.", e);
        }
        profile.setStatus(profile.getActivatedAt() == null ? UserStatus.INVITED : UserStatus.ACTIVE);
        profile.setBlockedAt(null);
        profile.setBlockedReason(null);
        profile.setUpdatedAt(Instant.now());
        profile.setUpdatedBy(actor.uid());
        users.save(profile);
        profileCache.evict(uid);
        audit.record(actor, "EMPLOYEE_UNBLOCKED", "user", uid, "Conta desbloqueada: " + profile.getEmail());
        return response(profile);
    }

    public EmployeeResponse revokeSessions(AuthenticatedUser actor, String uid) {
        UserProfile profile = load(actor, uid);
        assertCanManage(actor, profile);
        try {
            firebaseAuth.revokeRefreshTokens(uid);
        } catch (FirebaseAuthException e) {
            throw new DataAccessFailure("Falha ao revogar sessões.", e);
        }
        // Margem de 1s: o "iat" do token tem resolução de segundos.
        profile.setTokensRevokedAt(Instant.now().plusSeconds(1));
        profile.setUpdatedAt(Instant.now());
        profile.setUpdatedBy(actor.uid());
        users.save(profile);
        profileCache.evict(uid);
        audit.record(actor, "EMPLOYEE_SESSIONS_REVOKED", "user", uid, "Sessões revogadas: " + profile.getEmail());
        return response(profile);
    }

    public String resetAccess(AuthenticatedUser actor, String uid) {
        UserProfile profile = load(actor, uid);
        assertCanManage(actor, profile);
        String link = passwordLink(profile.getEmail());
        audit.record(actor, "EMPLOYEE_ACCESS_RESET", "user", uid, "Link de redefinição gerado: " + profile.getEmail());
        return link;
    }

    public List<RoleInfo> roles() {
        return Arrays.stream(Role.values())
                .filter(r -> r != Role.MASTER)
                .map(r -> new RoleInfo(r, r.label(), r.isStaff(), RolePermissions.defaultsFor(r).stream().sorted().toList()))
                .toList();
    }

    public List<PermissionInfo> permissions() {
        return Arrays.stream(Permission.values())
                .filter(p -> !NON_GRANTABLE.contains(p))
                .map(p -> new PermissionInfo(p, p.module(), p.description()))
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    EmployeeResponse response(UserProfile profile) {
        return EmployeeResponse.of(profile, resolver.resolve(profile));
    }

    /** Limite de usuários do plano (definido no painel master). */
    void assertSeat(Office office) {
        if (office.getMaxUsers() != null && users.countSeats(office.getId()) >= office.getMaxUsers()) {
            throw new BusinessException("SEAT_LIMIT", "Limite de " + office.getMaxUsers()
                    + " usuários do plano atingido. Fale com o suporte da plataforma para ampliar.");
        }
    }

    private UserProfile load(AuthenticatedUser actor, String uid) {
        return users.findInOffice(actor.requireOffice(), uid).orElseThrow(() -> new NotFoundException("Usuário"));
    }

    private CustomRole customRole(String officeId, String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        return resolver.customRole(officeId, roleId).orElseThrow(() -> new NotFoundException("Cargo"));
    }

    private static void applyCustomRole(UserProfile profile, CustomRole role) {
        profile.setCustomRoleId(role == null ? null : role.getId());
        profile.setCustomRoleName(role == null ? null : role.getName());
    }

    private void applyCpf(UserProfile profile, String cpf) {
        if (cpf == null) {
            return;
        }
        profile.setCpfEnc(crypto.encrypt(cpf));
        profile.setCpfHash(crypto.hmac(cpf));
        profile.setCpfMasked(BrazilianDocuments.mask(cpf));
    }

    private static String optionalCpf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return PlatformService.validCpf(raw);
    }

    private void assertCanAssign(AuthenticatedUser actor, Role role) {
        if (role.isPrivileged() && actor.role() != Role.SOCIO_ADMIN && actor.role() != Role.MASTER) {
            throw new ForbiddenException("ROLE_ASSIGN_DENIED", "Somente sócio administrador atribui este papel.");
        }
        if (role == Role.MASTER && actor.role() != Role.MASTER) {
            throw new ForbiddenException("ROLE_ASSIGN_DENIED", "Papel exclusivo da plataforma.");
        }
    }

    /** Atribuir um cargo personalizado exige possuir todas as permissões dele (sem escalonamento). */
    private void assertCanAssignCustom(AuthenticatedUser actor, CustomRole role) {
        if (role == null) {
            return;
        }
        for (Permission p : role.getPermissions()) {
            if (!actor.has(p)) {
                throw new ForbiddenException("GRANT_DENIED", "Você não pode atribuir o cargo " + role.getName()
                        + ": ele inclui a permissão " + p.name() + ", que você não possui.");
            }
        }
    }

    private void assertCanManage(AuthenticatedUser actor, UserProfile target) {
        if (target.getRole() != null && target.getRole().isPrivileged()
                && actor.role() != Role.SOCIO_ADMIN && actor.role() != Role.MASTER) {
            throw new ForbiddenException("MANAGE_DENIED", "Somente sócio administrador gerencia este usuário.");
        }
        if (target.getRole() == Role.MASTER && actor.role() != Role.MASTER) {
            throw new ForbiddenException("MANAGE_DENIED", "Usuário da plataforma.");
        }
    }

    private String createOrAttachAuthUser(String email, String displayName, String password) {
        try {
            UserRecord.CreateRequest request = new UserRecord.CreateRequest()
                    .setEmail(email)
                    .setDisplayName(displayName)
                    .setEmailVerified(password != null)
                    .setDisabled(false);
            if (password != null) {
                request.setPassword(password);
            }
            return firebaseAuth.createUser(request).getUid();
        } catch (FirebaseAuthException e) {
            if (e.getAuthErrorCode() != AuthErrorCode.EMAIL_ALREADY_EXISTS) {
                throw new DataAccessFailure("Falha ao criar usuário no provedor de identidade.", e);
            }
        }
        // E-mail já existe no Firebase Auth: só pode ser vinculado se ainda não pertence a nenhum escritório.
        try {
            UserRecord existing = firebaseAuth.getUserByEmail(email);
            Object office = existing.getCustomClaims().get("officeId");
            if (office != null || users.findById(existing.getUid()).map(UserProfile::getOfficeId).isPresent()) {
                throw new ConflictException("EMAIL_IN_USE", "Este e-mail já está vinculado a um escritório.");
            }
            if (password != null) {
                firebaseAuth.updateUser(new UserRecord.UpdateRequest(existing.getUid()).setPassword(password));
            }
            return existing.getUid();
        } catch (FirebaseAuthException e) {
            throw new DataAccessFailure("Falha ao consultar usuário no provedor de identidade.", e);
        }
    }

    private void setClaims(String uid, String officeId, Role role) {
        try {
            firebaseAuth.setCustomUserClaims(uid, Map.of("officeId", officeId, "role", role.name()));
        } catch (FirebaseAuthException e) {
            throw new DataAccessFailure("Falha ao definir credenciais do usuário.", e);
        }
    }

    void updateClaims(String uid, String officeId, Role role) {
        setClaims(uid, officeId, role);
    }

    private String passwordLink(String email) {
        try {
            return publicLink(firebaseAuth.generatePasswordResetLink(email));
        } catch (FirebaseAuthException e) {
            throw new DataAccessFailure("Falha ao gerar link de definição de senha.", e);
        }
    }

    /** No Emulator Suite em Docker, troca o host interno (ex.: firebase-emulator:9099) pelo público. */
    String publicLink(String link) {
        String publicUrl = properties.firebase().emulatorPublicAuthUrl();
        String internalHost = System.getenv("FIREBASE_AUTH_EMULATOR_HOST");
        if (link == null || publicUrl == null || publicUrl.isBlank() || internalHost == null || internalHost.isBlank()) {
            return link;
        }
        return link.replaceFirst("^https?://" + java.util.regex.Pattern.quote(internalHost), publicUrl.replaceAll("/$", ""));
    }

    static List<Permission> sanitize(List<Permission> permissions) {
        if (permissions == null) {
            return new ArrayList<>();
        }
        return permissions.stream().filter(p -> p != null && !NON_GRANTABLE.contains(p)).distinct().sorted().toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
