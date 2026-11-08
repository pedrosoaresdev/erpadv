package br.com.erpadv.dto;

import br.com.erpadv.models.LeadProposal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;

public final class ProposalDtos {

    private ProposalDtos() {
    }

    public record ProposalRequest(@NotBlank @Size(max = 200) String title, @Size(max = 8000) String scope,
                                  @Size(max = 1000) String feeTerms, @NotNull @PositiveOrZero @Max(100_000_000_000L) Long valueCents,
                                  LocalDate validUntil) {
    }

    public record DecisionRequest(@NotNull LeadProposal.Status status, @Size(max = 500) String note) {
    }

    public record ProposalResponse(String id, String leadId, String leadName, String number, String title, String scope, String feeTerms,
                                   long valueCents, LocalDate validUntil, LeadProposal.Status status, Instant sentAt, Instant decidedAt,
                                   String decisionNote, String createdByName, Instant createdAt) {
        public static ProposalResponse of(LeadProposal p) {
            return new ProposalResponse(p.getId(), p.getLeadId(), p.getLeadName(), p.getNumber(), p.getTitle(), p.getScope(), p.getFeeTerms(),
                    p.getValueCents(), p.getValidUntil(), p.getStatus(), p.getSentAt(), p.getDecidedAt(), p.getDecisionNote(),
                    p.getCreatedByName(), p.getCreatedAt());
        }
    }
}
