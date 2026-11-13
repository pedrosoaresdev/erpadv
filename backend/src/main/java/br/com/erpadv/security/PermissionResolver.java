package br.com.erpadv.security;

import br.com.erpadv.config.AppProperties;
import br.com.erpadv.models.CustomRole;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.repositories.CustomRoleRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Permissões efetivas de um perfil: cargo personalizado (quando houver) ou matriz padrão do papel,
 * mais concessões e menos revogações individuais.
 */
@Component
public class PermissionResolver {

    private final CustomRoleRepository roles;
    private final TtlCache<String, CustomRole> cache;

    public PermissionResolver(CustomRoleRepository roles, AppProperties properties) {
        this.roles = roles;
        this.cache = new TtlCache<>(Duration.ofSeconds(Math.max(1, properties.security().profileCacheSeconds())), 5_000);
    }

    public Set<Permission> resolve(UserProfile profile) {
        if (profile == null) {
            return Set.of();
        }
        Optional<CustomRole> custom = customRole(profile.getOfficeId(), profile.getCustomRoleId());
        Set<Permission> perms;
        if (custom.isPresent() && profile.getRole() != Role.CLIENTE) {
            perms = RolePermissions.effectiveCustom(custom.get().getPermissions(),
                    profile.getPermissionsGranted(), profile.getPermissionsRevoked());
        } else {
            perms = RolePermissions.effective(profile.getRole(), profile.getPermissionsGranted(), profile.getPermissionsRevoked());
        }
        // módulos fora do plano contratado (ou desligados pelo escritório) não liberam permissões
        return license == null || profile.getOfficeId() == null ? perms : license.restrict(profile.getOfficeId(), perms);
    }

    public Optional<CustomRole> customRole(String officeId, String roleId) {
        if (officeId == null || roleId == null || roleId.isBlank()) {
            return Optional.empty();
        }
        return cache.get(officeId + "/" + roleId, k -> roles.findById(officeId, roleId));
    }

    public void evict(String officeId, String roleId) {
        cache.evict(officeId + "/" + roleId);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private br.com.erpadv.services.platform.LicenseService license;
}
