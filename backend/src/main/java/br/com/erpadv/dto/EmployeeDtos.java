package br.com.erpadv.dto;

import br.com.erpadv.models.UserProfile;
import br.com.erpadv.models.UserStatus;
import br.com.erpadv.security.Permission;
import br.com.erpadv.security.Role;
import br.com.erpadv.security.RolePermissions;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
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
            List<Permission> permissionsGranted,
            List<Permission> permissionsRevoked,
            List<Permission> effectivePermissions,
            Instant invitedAt,
            Instant activatedAt,
            Instant lastLoginAt,
            Instant blockedAt,
            String blockedReason) {

        public static EmployeeResponse of(UserProfile p) {
            return new EmployeeResponse(p.getId(), p.getEmail(), p.getDisplayName(), p.getRole(),
                    p.getRole() == null ? null : p.getRole().label(), p.getStatus(), p.getOabNumber(), p.getPhone(),
                    p.getPosition(), p.getPermissionsGranted(), p.getPermissionsRevoked(),
                    RolePermissions.effective(p.getRole(), p.getPermissionsGranted(), p.getPermissionsRevoked())
                            .stream().sorted().toList(),
                    p.getInvitedAt(), p.getActivatedAt(), p.getLastLoginAt(), p.getBlockedAt(), p.getBlockedReason());
        }
    }

    /** Opção enxuta para seletores (ex.: advogado responsável). */
    public record EmployeeOption(String uid, String displayName, Role role, String oabNumber) {
    }

    public record InviteRequest(
            @NotBlank @Email @Size(max = 150) String email,
            @NotBlank @Size(max = 120) String displayName,
            @NotNull Role role,
            @Size(max = 20) String oabNumber,
            @Size(max = 20) String phone,
            @Size(max = 80) String position) {
    }

    public record InviteResponse(EmployeeResponse employee, String passwordSetupLink) {
    }

    public record UpdateEmployeeRequest(
            @NotBlank @Size(max = 120) String displayName,
            @NotNull Role role,
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

    public record PermissionInfo(Permission code, String module, String description) {
    }

    public record RoleInfo(Role role, String label, boolean staff, List<Permission> permissions) {
    }
}
