package br.com.erpadv.dto;

import br.com.erpadv.models.UserStatus;
import br.com.erpadv.security.Role;
import br.com.erpadv.validation.CpfCnpj;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Painel master da plataforma (desenvolvedor): escritórios e seus administradores. */
public final class PlatformDtos {

    public static final String PLAN_REGEX = "^(ESSENCIAL|PROFISSIONAL|ENTERPRISE)$";
    public static final String OAB_REGEX = "^[A-Za-z0-9 ./-]{3,20}$";

    private PlatformDtos() {
    }

    /** Usuário administrador (advogado dono do escritório). */
    public record AdminUserRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Size(max = 18) String cpf,
            @NotBlank @Pattern(regexp = OAB_REGEX, message = "Nº OAB inválido (ex.: SP 123456)") String oabNumber,
            @NotBlank @Email @Size(max = 150) String email,
            @NotBlank @Size(min = 8, max = 128) String password,
            @Size(max = 20) String phone) {
    }

    public record CreateOfficeRequest(
            @NotBlank @Size(max = 150) String name,
            @CpfCnpj String cnpj,
            @Email @Size(max = 150) String email,
            @Size(max = 20) String phone,
            @Size(max = 100) String city,
            @Pattern(regexp = "^$|^[A-Z]{2}$", message = "UF inválida") String state,
            @NotBlank @Pattern(regexp = PLAN_REGEX, message = "Plano inválido") String plan,
            @Min(1) @Max(10000) Integer maxUsers,
            @NotNull @Valid AdminUserRequest admin) {
    }

    public record UpdateOfficeRequest(
            @NotBlank @Size(max = 150) String name,
            @CpfCnpj String cnpj,
            @Email @Size(max = 150) String email,
            @Size(max = 20) String phone,
            @Size(max = 100) String city,
            @Pattern(regexp = "^$|^[A-Z]{2}$", message = "UF inválida") String state,
            @NotBlank @Pattern(regexp = PLAN_REGEX, message = "Plano inválido") String plan,
            @Min(1) @Max(10000) Integer maxUsers) {
    }

    public record SuspendRequest(@Size(max = 500) String reason) {
    }

    public record PasswordRequest(@NotBlank @Size(min = 8, max = 128) String password) {
    }

    public record LinkResponse(String link) {
    }

    public record OfficeSummary(
            String id,
            String name,
            String cnpj,
            String email,
            String phone,
            String city,
            String state,
            String plan,
            String status,
            Integer maxUsers,
            Instant createdAt,
            Instant suspendedAt,
            String suspendedReason,
            boolean createdByPlatform,
            long seats) {
    }

    public record OfficeUser(
            String uid,
            String displayName,
            String email,
            Role role,
            String roleLabel,
            String customRoleName,
            UserStatus status,
            String oabNumber,
            String cpfMasked,
            boolean owner,
            Instant createdAt,
            Instant lastLoginAt) {
    }

    public record OfficeDetail(
            OfficeSummary office,
            List<OfficeUser> users,
            long clients,
            long cases,
            long contracts,
            long boards,
            long leads) {
    }

    public record PlatformStats(
            long offices,
            long activeOffices,
            long suspendedOffices,
            long users,
            long activeUsers) {
    }

    public record PlatformOverview(PlatformStats stats, List<OfficeSummary> offices) {
    }

    public record OfficeCreated(OfficeSummary office, OfficeUser admin) {
    }
}
