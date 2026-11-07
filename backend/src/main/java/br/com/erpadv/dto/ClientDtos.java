package br.com.erpadv.dto;

import br.com.erpadv.models.Address;
import br.com.erpadv.models.ClientStatus;
import br.com.erpadv.models.ClientType;
import br.com.erpadv.models.InteractionChannel;
import br.com.erpadv.validation.CpfCnpj;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ClientDtos {

    private ClientDtos() {
    }

    public record ClientRequest(
            @NotNull ClientType type,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 200) String tradeName,
            /** Obrigatório no cadastro; na edição, nulo mantém o documento atual. */
            @CpfCnpj String document,
            @Size(max = 30) String rg,
            @Past LocalDate birthDate,
            @Size(max = 20) String phone,
            @Size(max = 20) String whatsapp,
            @Email @Size(max = 150) String email,
            @Valid Address address,
            @Size(max = 100) String profession,
            @Size(max = 40) String maritalStatus,
            @Size(max = 4000) String notes,
            @Size(max = 128) String responsibleUid,
            @Size(max = 60) String nationality,
            @Size(max = 30) String stateRegistration,
            @Size(max = 30) String municipalRegistration,
            @Size(max = 150) String legalRepresentative,
            @Size(max = 80) String legalRepresentativeRole,
            @Size(max = 10) List<@Valid ContactRequest> contacts,
            @jakarta.validation.constraints.Pattern(regexp = "^$|^[A-Z_]{2,20}$", message = "Origem inválida") String source,
            @Size(max = 120) String sourceDetail) {

        /** Compatibilidade com chamadas anteriores (sem os campos complementares). */
        public ClientRequest(ClientType type, String name, String tradeName, String document, String rg, LocalDate birthDate,
                             String phone, String whatsapp, String email, Address address, String profession,
                             String maritalStatus, String notes, String responsibleUid) {
            this(type, name, tradeName, document, rg, birthDate, phone, whatsapp, email, address, profession, maritalStatus,
                    notes, responsibleUid, null, null, null, null, null, null, null, null);
        }
    }

    public record ContactRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 80) String role,
            @Size(max = 20) String phone,
            @Email @Size(max = 150) String email) {
    }

    /** Possível duplicidade: cliente existente e o motivo (documento, telefone, e-mail ou nome). */
    public record DuplicateMatch(ClientSummary client, String reason) {
    }

    public record ClientSummary(
            String id,
            ClientType type,
            String name,
            String documentMasked,
            String phone,
            String email,
            ClientStatus status,
            boolean defaulting,
            Instant createdAt) {
    }

    public record ClientResponse(
            String id,
            ClientType type,
            String name,
            String tradeName,
            /** Completo somente com CLIENTS_SENSITIVE_READ; caso contrário, mascarado. */
            String document,
            String documentMasked,
            String rg,
            boolean sensitiveVisible,
            LocalDate birthDate,
            String phone,
            String whatsapp,
            String email,
            Address address,
            String profession,
            String maritalStatus,
            String notes,
            ClientStatus status,
            boolean defaulting,
            String responsibleUid,
            Instant inactivatedAt,
            String inactivationReason,
            Instant createdAt,
            Instant updatedAt,
            String nationality,
            String stateRegistration,
            String municipalRegistration,
            String legalRepresentative,
            String legalRepresentativeRole,
            List<br.com.erpadv.models.ClientContact> contacts,
            String source,
            String sourceDetail,
            Instant lastActivityAt) {
    }

    public record InactivateRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record DefaultingRequest(boolean defaulting) {
    }

    public record InteractionRequest(
            @NotNull InteractionChannel channel,
            @NotBlank @Size(max = 200) String subject,
            @Size(max = 4000) String description,
            @NotNull @PastOrPresent Instant occurredAt) {
    }
}
