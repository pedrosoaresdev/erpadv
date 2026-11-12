package br.com.erpadv.services;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.dto.EmployeeDtos.CustomRoleRequest;
import br.com.erpadv.dto.EmployeeDtos.CustomRoleResponse;
import br.com.erpadv.dto.EmployeeDtos.RolePreset;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.ConflictException;
import br.com.erpadv.exceptions.ForbiddenException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.firebase.SearchTokens;
import br.com.erpadv.models.CustomRole;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.repositories.CustomRoleRepository;
import br.com.erpadv.repositories.UserRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Permission;
import br.com.erpadv.security.PermissionResolver;
import br.com.erpadv.security.Role;
import br.com.erpadv.security.RolePermissions;
import br.com.erpadv.security.UserProfileCache;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static br.com.erpadv.security.Permission.*;

/**
 * Cargos personalizados do escritório: o sócio administrador cria cargos como "Advogado júnior",
 * "Estagiário", "Secretário(a)" ou "Financeiro" e escolhe exatamente o que cada um acessa.
 */
@Service
public class RoleService {

    private static final List<RolePreset> PRESETS = List.of(
            new RolePreset("Advogado júnior", "Atua nos processos sob supervisão; sem gestão de contratos.",
                    Role.ADVOGADO_ASSOCIADO, "#2f5d9e", sorted(RolePermissions.defaultsFor(Role.ADVOGADO_ASSOCIADO))),
            new RolePreset("Advogado sênior", "Responsável por carteira própria de clientes e processos.",
                    Role.ADVOGADO, "#1f3b63", sorted(RolePermissions.defaultsFor(Role.ADVOGADO))),
            new RolePreset("Estagiário", "Consulta processos, prazos e agenda; apoio em tarefas.",
                    Role.ESTAGIARIO, "#6b8f71", sorted(RolePermissions.defaultsFor(Role.ESTAGIARIO))),
            new RolePreset("Secretário(a)", "Atendimento, agenda, cadastro de clientes e captação.",
                    Role.SECRETARIA, "#8a6d3b", sorted(RolePermissions.defaultsFor(Role.SECRETARIA))),
            new RolePreset("Financeiro", "Contas a pagar e receber, honorários e relatórios financeiros.",
                    Role.FINANCEIRO, "#b8860b", sorted(RolePermissions.defaultsFor(Role.FINANCEIRO))),
            new RolePreset("Comercial", "Funil de captação (CRM), atendimento e agenda.",
                    Role.ADMINISTRATIVO, "#7a4e9e", sorted(EnumSet.of(DASHBOARD_VIEW, CLIENTS_READ, CLIENTS_WRITE,
                    CALENDAR_READ, CALENDAR_WRITE, CRM_USE, BOARDS_USE, MESSAGES_USE))));

    private final Firestore db;
    private final CustomRoleRepository roles;
    private final UserRepository users;
    private final PermissionResolver resolver;
    private final UserProfileCache profileCache;
    private final EmployeeService employees;
    private final AuditService audit;

    public RoleService(Firestore db, CustomRoleRepository roles, UserRepository users, PermissionResolver resolver,
                       UserProfileCache profileCache, EmployeeService employees, AuditService audit) {
        this.db = db;
        this.roles = roles;
        this.users = users;
        this.resolver = resolver;
        this.profileCache = profileCache;
        this.employees = employees;
        this.audit = audit;
    }

    public List<CustomRoleResponse> list(AuthenticatedUser user) {
        String officeId = user.requireOffice();
        return roles.all(officeId).stream()
                .map(r -> response(r, users.countByCustomRole(officeId, r.getId())))
                .toList();
    }

    public List<RolePreset> presets() {
        return PRESETS;
    }

    public CustomRoleResponse create(AuthenticatedUser actor, CustomRoleRequest req) {
        String officeId = actor.requireOffice();
        validate(actor, req);
        assertUniqueName(officeId, req.name(), null);
        Instant now = Instant.now();
        CustomRole role = new CustomRole();
        apply(role, req);
        role.setCreatedAt(now);
        role.setUpdatedAt(now);
        role.setCreatedBy(actor.uid());
        roles.save(officeId, role);
        audit.record(actor, "ROLE_CREATED", "role", role.getId(), "Cargo criado: " + role.getName(),
                Map.of("baseRole", role.getBaseRole().name(), "permissions", role.getPermissions().toString()));
        return response(role, 0);
    }

    public CustomRoleResponse update(AuthenticatedUser actor, String id, CustomRoleRequest req) {
        String officeId = actor.requireOffice();
        CustomRole role = roles.findById(officeId, id).orElseThrow(() -> new NotFoundException("Cargo"));
        Map<String, Object> auditBefore = audit.snapshot(role);
        validate(actor, req);
        assertUniqueName(officeId, req.name(), id);
        Role previousBase = role.getBaseRole();
        String previousName = role.getName();
        List<Permission> previousPermissions = role.getPermissions();
        apply(role, req);
        role.setUpdatedAt(Instant.now());
        role.setUpdatedBy(actor.uid());
        roles.save(officeId, role);
        resolver.evict(officeId, id);

        // Propaga nome e papel-base aos integrantes do cargo.
        List<UserProfile> members = Futures.await(db.collection("users").whereEqualTo("officeId", officeId)
                        .whereEqualTo("customRoleId", id).limit(500).get())
                .getDocuments().stream().map(d -> users.findById(d.getId()).orElse(null))
                .filter(Objects::nonNull).toList();
        for (UserProfile member : members) {
            boolean baseChanged = member.getRole() != role.getBaseRole();
            if (baseChanged || !Objects.equals(member.getCustomRoleName(), role.getName())) {
                member.setCustomRoleName(role.getName());
                member.setRole(role.getBaseRole());
                member.setUpdatedAt(Instant.now());
                member.setUpdatedBy(actor.uid());
                users.save(member);
                if (baseChanged) {
                    employees.updateClaims(member.getId(), officeId, role.getBaseRole());
                }
            }
            profileCache.evict(member.getId());
        }
        audit.recordChange(actor, "ROLE_UPDATED", "role", id, "Cargo atualizado: " + role.getName(),
                Map.of("name", previousName + "->" + role.getName(),
                        "baseRole", previousBase + "->" + role.getBaseRole(),
                        "permissions", previousPermissions + "->" + role.getPermissions(),
                        "members", String.valueOf(members.size())), auditBefore, role);
        return response(role, members.size());
    }

    public void delete(AuthenticatedUser actor, String id) {
        String officeId = actor.requireOffice();
        CustomRole role = roles.findById(officeId, id).orElseThrow(() -> new NotFoundException("Cargo"));
        long inUse = users.countByCustomRole(officeId, id);
        if (inUse > 0) {
            throw new ConflictException("ROLE_IN_USE", "Cargo atribuído a " + inUse
                    + " integrante(s). Altere o cargo deles antes de excluir.");
        }
        roles.delete(officeId, id);
        resolver.evict(officeId, id);
        audit.record(actor, "ROLE_DELETED", "role", id, "Cargo excluído: " + role.getName());
    }

    // ------------------------------------------------------------------ helpers

    private void validate(AuthenticatedUser actor, CustomRoleRequest req) {
        if (req.baseRole() == Role.CLIENTE || req.baseRole() == Role.MASTER) {
            throw new BusinessException("INVALID_ROLE", "Papel-base inválido para cargo da equipe.");
        }
        if (req.baseRole().isPrivileged() && actor.role() != Role.SOCIO_ADMIN) {
            throw new ForbiddenException("ROLE_ASSIGN_DENIED", "Somente sócio administrador cria cargos de sócio.");
        }
        for (Permission p : EmployeeService.sanitize(req.permissions())) {
            if (!actor.has(p)) {
                throw new ForbiddenException("GRANT_DENIED", "Você não pode incluir a permissão " + p.name()
                        + " porque não a possui.");
            }
        }
        if (EmployeeService.sanitize(req.permissions()).isEmpty()) {
            throw new BusinessException("PERMISSIONS_REQUIRED", "Selecione ao menos uma permissão para o cargo.");
        }
    }

    private void assertUniqueName(String officeId, String name, String ignoreId) {
        String lower = SearchTokens.normalize(name);
        boolean exists = roles.all(officeId).stream()
                .anyMatch(r -> lower.equals(r.getNameLower()) && !r.getId().equals(ignoreId));
        if (exists) {
            throw new ConflictException("ROLE_NAME_IN_USE", "Já existe um cargo com este nome.");
        }
    }

    private static void apply(CustomRole role, CustomRoleRequest req) {
        role.setName(req.name().trim());
        role.setNameLower(SearchTokens.normalize(req.name()));
        role.setDescription(req.description() == null || req.description().isBlank() ? null : req.description().trim());
        role.setColor(req.color() == null || req.color().isBlank() ? null : req.color());
        role.setBaseRole(req.baseRole());
        role.setPermissions(EmployeeService.sanitize(req.permissions()));
    }

    private static CustomRoleResponse response(CustomRole r, long members) {
        return new CustomRoleResponse(r.getId(), r.getName(), r.getDescription(), r.getColor(), r.getBaseRole(),
                r.getBaseRole() == null ? null : r.getBaseRole().label(), r.getPermissions(), members,
                r.getCreatedAt(), r.getUpdatedAt());
    }

    private static List<Permission> sorted(java.util.Collection<Permission> permissions) {
        return permissions.stream().filter(p -> !EmployeeService.NON_GRANTABLE.contains(p)).sorted().toList();
    }
}
