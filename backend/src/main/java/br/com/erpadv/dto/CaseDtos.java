package br.com.erpadv.dto;

import br.com.erpadv.models.CaseStatus;
import br.com.erpadv.models.LegalArea;
import br.com.erpadv.models.LegalCase;
import br.com.erpadv.models.MovementType;
import br.com.erpadv.validation.CnjNumber;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;

public final class CaseDtos {

    private CaseDtos() {
    }

    public record CaseRequest(
            @CnjNumber String cnjNumber,
            @NotBlank @Size(max = 128) String clientId,
            @Size(max = 200) String opposingParty,
            @Size(max = 120) String court,
            @Size(max = 120) String courtDivision,
            @Size(max = 120) String district,
            @Pattern(regexp = "^$|^[A-Z]{2}$", message = "UF inválida") String state,
            @NotNull LegalArea area,
            @Size(max = 120) String proceduralClass,
            @Size(max = 300) String subject,
            @NotBlank @Size(max = 128) String responsibleUid,
            @PositiveOrZero Long claimValueCents,
            @PastOrPresent LocalDate distributionDate,
            @NotNull CaseStatus status,
            @Size(max = 4000) String notes,
            boolean confidential) {
    }

    public record CaseResponse(
            String id,
            String cnjNumber,
            String clientId,
            String clientName,
            String opposingParty,
            String court,
            String courtDivision,
            String district,
            String state,
            LegalArea area,
            String areaLabel,
            String proceduralClass,
            String subject,
            String responsibleUid,
            String responsibleName,
            Long claimValueCents,
            LocalDate distributionDate,
            CaseStatus status,
            String statusLabel,
            String notes,
            boolean confidential,
            boolean restricted,
            boolean archived,
            Instant archivedAt,
            Instant createdAt,
            Instant updatedAt) {

        public static CaseResponse of(LegalCase c) {
            return new CaseResponse(c.getId(), c.getCnjNumber(), c.getClientId(), c.getClientName(),
                    c.getOpposingParty(), c.getCourt(), c.getCourtDivision(), c.getDistrict(), c.getState(),
                    c.getArea(), c.getArea() == null ? null : c.getArea().label(), c.getProceduralClass(),
                    c.getSubject(), c.getResponsibleUid(), c.getResponsibleName(), c.getClaimValueCents(),
                    c.getDistributionDate(), c.getStatus(), c.getStatus() == null ? null : c.getStatus().label(),
                    c.getNotes(), c.isConfidential(), c.isRestricted(), c.isArchived(), c.getArchivedAt(),
                    c.getCreatedAt(), c.getUpdatedAt());
        }
    }

    public record MovementRequest(
            @NotNull MovementType type,
            @NotNull @PastOrPresent LocalDate date,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 8000) String description) {
    }

    public record CaseFilter(
            String search,
            CaseStatus status,
            LegalArea area,
            String responsibleUid,
            String court,
            String clientId,
            boolean includeArchived) {
    }
}
