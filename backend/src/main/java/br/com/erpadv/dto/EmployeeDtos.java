package br.com.erpadv.dto;

import br.com.erpadv.models.UserProfile;
import br.com.erpadv.models.UserStatus;
import br.com.erpadv.security.Permission;
import br.com.erpadv.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public final class EmployeeDtos {

    private EmployeeDtos() {
    }

    public record EmployeeResponse(
            String uid,
            String email,
            String displayName,
            Role role,
            String roleLabel,
            UserStatus status,
            String oabNumber,
            String phone,
            String position,
            String customRoleId,
            String customRoleName,
            String cpfMasked,
            List<Permission> permissionsGranted,
            List<Permission> permissionsRevoked,
            List<Permission> effectivePermissions,
            Instant invitedAt,
            Instant activatedAt,
            Instant lastLoginAt,
            Instant blockedAt,
            String blockedReason) {

        public static EmployeeResponse of(UserProfile p, Collection<Permission> effective) {
            return new EmployeeResponse(p.getId(), p.getEmail(), p.getDisplayName(), p.getRole(),
                    p.getRole() == null ? null : p.getRole().label(), p.getStatus(), p.getOabNumber(), p.getPhone(),
                    p.getPosition(), p.getCustomRoleId(), p.getCustomRoleName(), p.getCpfMasked(),
                    p.getPermissionsGranted(), p.getPermissionsRevoked(),
                    effective.stream().sorted().toList(),
                    p.getInvitedAt(), p.getActivatedAt(), p.getLastLoginAt(), p.getBlockedAt(), p.getBlockedReason());
        }
    }

    /** Opção enxuta para seletores (ex.: advogado responsável). */
    public record EmployeeOption(String uid, String displayName, Role role, String oabNumber) {
    }

    /**
     * Cadastro de integrante. Informe o papel-base ou um cargo personalizado (customRoleId). Com senha,
     * a conta já nasce com acesso; sem senha, é devolvido um link para a pessoa definir a própria.
     */
    public record InviteRequest(
            @NotBlank @Email @Size(max = 150) String email,
            @NotBlank @Size(max = 120) String displayName,
            Role role,
            @Size(max = 64) String customRoleId,
            @Size(max = 20) String oabNumber,
            @Size(max = 18) String cpf,
            @Size(max = 20) String phone,
            @Size(max = 80) String position,
            @Size(min = 8, max = 128) String password) {
    }

    public record InviteResponse(EmployeeResponse employee, String passwordSetupLink) {
    }

    public record UpdateEmployeeRequest(
            @NotBlank @Size(max = 120) String displayName,
            Role role,
            @Size(max = 64) String customRoleId,
            @Size(max = 18) String cpf,
            @Size(max = 20) String oabNumber,
            @Size(max = 20) String phone,
            @Size(max = 80) String position,
            List<Permission> permissionsGranted,
            List<Permission> permissionsRevoked) {
    }

    public record BlockRequest(@Size(max = 500) String reason) {
    }

    public record ResetLinkResponse(String passwordResetLink) {
    }

    public record SetPasswordRequest(@NotBlank @Size(min = 8, max = 128) String password) {
    }

    // ------------------------------------------------------------------ cargos personalizados

    public record CustomRoleRequest(
            @NotBlank @Size(max = 60) String name,
            @Size(max = 300) String description,
            @jakarta.validation.constraints.Pattern(regexp = "^$|^#[0-9a-fA-F]{6}$", message = "Cor inválida") String color,
            @NotNull Role baseRole,
            List<Permission> permissions) {
    }

    public record CustomRoleResponse(
            String id,
            String name,
            String description,
            String color,
            Role baseRole,
            String baseRoleLabel,
            List<Permission> permissions,
            long users,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** Sugestão de cargo (ex.: "Advogado júnior") com permissões iniciais editáveis. */
    public record RolePreset(String name, String description, Role baseRole, String color, List<Permission> permissions) {
    }

    public record PermissionInfo(Permission code, String module, String description) {
    }

    public record RoleInfo(Role role, String label, boolean staff, List<Permission> permissions) {
    }
}
