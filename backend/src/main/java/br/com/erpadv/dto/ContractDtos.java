package br.com.erpadv.dto;

import br.com.erpadv.models.Contract;
import br.com.erpadv.models.ContractEnums.ContractStatus;
import br.com.erpadv.models.ContractEnums.ContractType;
import br.com.erpadv.models.ContractEnums.FeeModel;
import br.com.erpadv.models.ContractTemplate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public final class ContractDtos {

    /** Renovação: novo término; no partido mensal, meses adicionais e (opcional) novo valor da mensalidade. */
    public record RenewRequest(@jakarta.validation.constraints.NotNull java.time.LocalDate newEndDate,
                               @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(60) Integer monthsAdded,
                               @jakarta.validation.constraints.Positive @jakarta.validation.constraints.Max(100_000_000_000L) Long monthlyCents,
                               @jakarta.validation.constraints.Size(max = 500) String note) {
    }

    private ContractDtos() {
    }

    public record StageRequest(
            @Size(max = 64) String id,
            @NotBlank @Size(max = 200) String description,
            @NotNull @Positive Long amountCents,
            LocalDate dueDate) {
    }

    public record ContractRequest(
            @NotBlank @Size(max = 200) String title,
            @NotNull ContractType type,
            @NotBlank @Size(max = 128) String clientId,
            @Size(max = 128) String caseId,
            @NotBlank @Size(max = 128) String responsibleUid,
            @NotBlank @Size(max = 4000) String object,
            @NotNull LocalDate startDate,
            LocalDate endDate,
            @NotNull FeeModel feeModel,
            @Positive Long amountCents,
            @Min(1) @Max(60) Integer installments,
            LocalDate firstDueDate,
            @Positive Long monthlyCents,
            @Min(1) @Max(60) Integer months,
            @Valid @Size(max = 20) List<StageRequest> stages,
            @Min(1) @Max(10000) Integer successPercentBp,
            @Size(max = 300) String successBase) {
    }

    public record ContractResponse(
            String id,
            String number,
            String title,
            ContractType type,
            String typeLabel,
            ContractStatus status,
            String clientId,
            String clientName,
            String caseId,
            String caseNumber,
            String responsibleUid,
            String responsibleName,
            String object,
            LocalDate startDate,
            LocalDate endDate,
            Long daysToEnd,
            FeeModel feeModel,
            String feeModelLabel,
            Long amountCents,
            Integer installments,
            LocalDate firstDueDate,
            Long monthlyCents,
            Integer months,
            List<Contract.FeeStage> stages,
            Integer successPercentBp,
            String successBase,
            boolean successBilled,
            String templateId,
            String body,
            Instant bodyGeneratedAt,
            String signedDocumentId,
            Instant signedAt,
            Instant activatedAt,
            Instant closedAt,
            String cancelReason,
            Instant createdAt,
            Instant updatedAt,
            List<Contract.Renewal> renewals) {

        public static ContractResponse of(Contract c, LocalDate today, boolean includeBody) {
            Long daysToEnd = c.getEndDate() == null ? null : java.time.temporal.ChronoUnit.DAYS.between(today, c.getEndDate());
            return new ContractResponse(c.getId(), c.getNumber(), c.getTitle(), c.getType(),
                    c.getType() == null ? null : c.getType().label(), c.getStatus(), c.getClientId(), c.getClientName(),
                    c.getCaseId(), c.getCaseNumber(), c.getResponsibleUid(), c.getResponsibleName(), c.getObject(),
                    c.getStartDate(), c.getEndDate(), daysToEnd, c.getFeeModel(),
                    c.getFeeModel() == null ? null : c.getFeeModel().label(), c.getAmountCents(), c.getInstallments(),
                    c.getFirstDueDate(), c.getMonthlyCents(), c.getMonths(), c.getStages(), c.getSuccessPercentBp(),
                    c.getSuccessBase(), c.isSuccessBilled(), c.getTemplateId(), includeBody ? c.getBody() : null,
                    c.getBodyGeneratedAt(), c.getSignedDocumentId(), c.getSignedAt(), c.getActivatedAt(), c.getClosedAt(),
                    c.getCancelReason(), c.getCreatedAt(), c.getUpdatedAt(), c.getRenewals());
        }
    }

    public record SuccessRequest(@NotNull @Positive Long obtainedCents, @NotNull LocalDate dueDate,
                                 @Size(max = 300) String note) {
    }

    public record GenerateRequest(@NotBlank @Size(max = 128) String templateId) {
    }

    public record GenerateResponse(String body, Set<String> missingFields) {
    }

    public record BodyRequest(@NotBlank @Size(max = 100_000) String body) {
    }

    public record TemplateRequest(
            @NotBlank @Size(max = 150) String name,
            @NotNull ContractType type,
            @Size(max = 300) String description,
            @NotBlank @Size(max = 100_000) String body,
            boolean active) {
    }

    public record TemplateResponse(String id, String name, ContractType type, String typeLabel, String description,
                                   String body, boolean active, boolean system, Set<String> fields) {
        public static TemplateResponse of(ContractTemplate t) {
            return new TemplateResponse(t.getId(), t.getName(), t.getType(), t.getType() == null ? null : t.getType().label(),
                    t.getDescription(), t.getBody(), t.isActive(), t.isSystem(),
                    br.com.erpadv.services.generation.TemplateRenderer.fields(t.getBody()));
        }
    }

    public record FieldInfo(String key, String description) {
    }
}
