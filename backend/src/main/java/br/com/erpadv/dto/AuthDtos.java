package br.com.erpadv.dto;

import br.com.erpadv.validation.CpfCnpj;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record MeResponse(
            String uid,
            String email,
            String name,
            boolean emailVerified,
            boolean onboardingRequired,
            boolean emailVerificationRequired,
            String officeId,
            String officeName,
            String role,
            String roleLabel,
            String clientId,
            List<String> permissions) {
    }

    public record CreateOfficeRequest(
            @NotBlank @Size(max = 150) String name,
            @CpfCnpj String cnpj,
            @Email @Size(max = 150) String email,
            @Size(max = 20) String phone,
            @Size(max = 100) String city,
            @Pattern(regexp = "^$|^[A-Z]{2}$", message = "UF inválida") String state,
            @NotBlank @Size(max = 120) String responsibleName,
            @Size(max = 20) String oabNumber) {
    }

    public record OfficeCreatedResponse(String officeId, String name, boolean refreshTokenRequired) {
    }
}
