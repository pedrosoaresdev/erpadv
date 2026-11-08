package br.com.erpadv.dto;

import br.com.erpadv.models.FinanceCategory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class FeeDtos {

    private FeeDtos() {
    }

    public enum GroupBy { CLIENT, CASE, CONTRACT, CATEGORY }

    public record FeeTotals(long contractedCents, long receivedCents, long pendingCents, long overdueCents, int titles) {
    }

    public record FeeGroup(String key, String label, String detail, FeeTotals totals, Integer successPercentBp, String feeModel) {
    }

    public record FeeSummary(LocalDate from, LocalDate to, FeeTotals totals, List<FeeGroup> groups, List<FeeGroup> byCategory) {
    }

    public record FeeItem(String id, FinanceCategory category, String categoryLabel, String description, String clientId, String clientName,
                          String caseId, String caseNumber, String contractId, LocalDate dueDate, long amountCents, String situation,
                          LocalDate paidDate, Long paidAmountCents, String paymentMethod, Integer installmentNumber,
                          Integer installmentCount, String receiptNumber) {
    }

    public record RuleRequest(@NotBlank @Size(max = 128) String beneficiaryUid, @Size(max = 200) String description,
                              @Min(1) @Max(10000) int percentBp, @Size(max = 4) List<@NotNull FinanceCategory> categories,
                              @Size(max = 128) String clientId, @Size(max = 128) String caseId, boolean responsibleOnly, boolean active) {
    }

    public record RuleResponse(String id, String beneficiaryUid, String beneficiaryName, String description, int percentBp,
                               List<FinanceCategory> categories, String clientId, String clientName, String caseId, String caseNumber,
                               boolean responsibleOnly, boolean active) {
        public static RuleResponse of(br.com.erpadv.models.CommissionRule r) {
            return new RuleResponse(r.getId(), r.getBeneficiaryUid(), r.getBeneficiaryName(), r.getDescription(), r.getPercentBp(),
                    r.getCategories(), r.getClientId(), r.getClientName(), r.getCaseId(), r.getCaseNumber(), r.isResponsibleOnly(), r.isActive());
        }
    }

    public record CommissionLine(String receiptId, LocalDate paidDate, String description, String clientName, String caseNumber,
                                 long baseCents, int percentBp, long commissionCents, String ruleId, boolean paidOut) {
    }

    public record CommissionStatement(String beneficiaryUid, String beneficiaryName, long baseCents, long commissionCents,
                                      long pendingCents, long paidOutCents, List<CommissionLine> lines) {
    }

    public record PayoutRequest(@NotBlank @Size(max = 128) String beneficiaryUid, @NotNull LocalDate from, @NotNull LocalDate to,
                                @NotNull LocalDate dueDate) {
    }

    public record PayoutResponse(String id, String beneficiaryUid, String beneficiaryName, LocalDate periodFrom, LocalDate periodTo,
                                 long amountCents, int receipts, String expenseId, String createdByName, Instant createdAt) {
        public static PayoutResponse of(br.com.erpadv.models.CommissionPayout p) {
            return new PayoutResponse(p.getId(), p.getBeneficiaryUid(), p.getBeneficiaryName(), p.getPeriodFrom(), p.getPeriodTo(),
                    p.getAmountCents(), p.getReceiptIds().size(), p.getExpenseId(), p.getCreatedByName(), p.getCreatedAt());
        }
    }
}
