package br.com.erpadv.dto;

import br.com.erpadv.dto.ClientDtos.ClientRequest;
import br.com.erpadv.models.ClientType;
import br.com.erpadv.models.CrmEnums.LeadActivityType;
import br.com.erpadv.models.CrmEnums.LeadSource;
import br.com.erpadv.models.CrmEnums.LeadStage;
import br.com.erpadv.models.LeadActivity;
import br.com.erpadv.models.LegalArea;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class CrmDtos {

    private CrmDtos() {
    }

    public record LeadRequest(
            @NotBlank @Size(max = 150) String name,
            @NotNull ClientType type,
            @Size(max = 150) String company,
            @Size(max = 20) String phone,
            @Email @Size(max = 150) String email,
            @NotNull LeadSource source,
            @Size(max = 120) String sourceDetail,
            LegalArea legalArea,
            @Size(max = 300) String subject,
            LeadStage stage,
            @Min(0) long estimatedValueCents,
            @Size(max = 128) String ownerUid,
            @Size(max = 200) String nextAction,
            LocalDate nextActionDate,
            @Size(max = 4000) String notes,
            @Size(max = 10) List<@Size(max = 30) String> tags) {
    }

    public record StageRequest(@NotNull LeadStage stage, @Size(max = 300) String lostReason) {
    }

    public record ActivityRequest(
            @NotNull LeadActivityType type,
            @NotBlank @Size(max = 2000) String text,
            @Size(max = 200) String nextAction,
            LocalDate nextActionDate) {
    }

    public record ConvertRequest(@NotNull @Valid ClientRequest client) {
    }

    public record LeadResponse(
            String id,
            String name,
            ClientType type,
            String company,
            String phone,
            String email,
            LeadSource source,
            String sourceLabel,
            String sourceDetail,
            LegalArea legalArea,
            String subject,
            LeadStage stage,
            String stageLabel,
            int probability,
            long estimatedValueCents,
            String ownerUid,
            String ownerName,
            String nextAction,
            LocalDate nextActionDate,
            boolean nextActionOverdue,
            String notes,
            String lostReason,
            List<String> tags,
            int score,
            String temperature,
            long daysInStage,
            Instant stageEnteredAt,
            Instant lastActivityAt,
            Instant wonAt,
            Instant lostAt,
            String convertedClientId,
            Instant createdAt) {
    }

    public record LeadDetail(LeadResponse lead, List<LeadActivity> activities) {
    }

    public record ConvertResponse(LeadResponse lead, String clientId) {
    }

    public record StageMetric(LeadStage stage, String label, long count, long valueCents) {
    }

    public record SourceMetric(LeadSource source, String label, long leads, long won, double conversionRate) {
    }

    public record OwnerMetric(String uid, String name, long open, long won, long wonValueCents) {
    }

    public record MonthMetric(String month, long created, long won, long wonValueCents) {
    }

    public record CrmMetrics(
            long openLeads,
            long pipelineValueCents,
            long weightedPipelineCents,
            long wonCount,
            long lostCount,
            double conversionRate,
            double avgDaysToWin,
            long overdueActions,
            long hotLeads,
            List<StageMetric> funnel,
            List<SourceMetric> sources,
            List<OwnerMetric> owners,
            List<MonthMetric> months,
            List<String> lostReasons) {
    }
}
