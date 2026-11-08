package br.com.erpadv.dto;

import br.com.erpadv.models.FinanceCategory;
import br.com.erpadv.models.FinanceDirection;
import br.com.erpadv.models.FinanceScope;
import br.com.erpadv.models.FinancialTransaction;
import br.com.erpadv.models.PaymentMethod;
import br.com.erpadv.models.TransactionStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

public final class FinanceDtos {

    private FinanceDtos() {
    }

    /** Situação exibida (derivada). */
    public enum Situation { A_VENCER, VENCE_HOJE, ATRASADO, PAGO, CANCELADO }

    public enum View { ABERTOS, A_VENCER, ATRASADOS, PAGOS, CANCELADOS, TODOS }

    public record TransactionRequest(
            @NotNull FinanceDirection direction,
            @NotNull FinanceCategory category,
            @NotBlank @Size(max = 200) String description,
            @NotNull @Positive @Max(100_000_000_000L) Long amountCents,
            @NotNull LocalDate dueDate,
            @Size(max = 128) String clientId,
            @Size(max = 128) String caseId,
            @Size(max = 200) String counterparty,
            @Size(max = 60) String documentNumber,
            PaymentMethod paymentMethod,
            @Size(max = 2000) String notes,
            FinanceScope scope,
            /** Parcelamento: divide o valor em N parcelas mensais. */
            @Min(1) @Max(60) Integer installments,
            /** Recorrência: repete o valor integral por N meses. */
            @Min(1) @Max(60) Integer recurrenceMonths,
            @Size(max = 128) String costCenterId) {

        public TransactionRequest(FinanceDirection direction, FinanceCategory category, String description, Long amountCents,
                                  LocalDate dueDate, String clientId, String caseId, String counterparty, String documentNumber,
                                  PaymentMethod paymentMethod, String notes, FinanceScope scope, Integer installments,
                                  Integer recurrenceMonths) {
            this(direction, category, description, amountCents, dueDate, clientId, caseId, counterparty, documentNumber,
                    paymentMethod, notes, scope, installments, recurrenceMonths, null);
        }
    }

    public record PayRequest(
            @NotNull LocalDate paidDate,
            @NotNull @Positive @Max(100_000_000_000L) Long paidAmountCents,
            @NotNull PaymentMethod paymentMethod,
            @Size(max = 128) String bankAccountId) {

        public PayRequest(LocalDate paidDate, Long paidAmountCents, PaymentMethod paymentMethod) {
            this(paidDate, paidAmountCents, paymentMethod, null);
        }
    }

    // ------------------------------------------------------------------ cadastros auxiliares

    public record CostCenterRequest(@NotBlank @Size(max = 80) String name, @Size(max = 20) String code,
                                    @Size(max = 300) String description, boolean active) {
    }

    public record CostCenterResponse(String id, String name, String code, String description, boolean active) {
        public static CostCenterResponse of(br.com.erpadv.models.CostCenter c) {
            return new CostCenterResponse(c.getId(), c.getName(), c.getCode(), c.getDescription(), c.isActive());
        }
    }

    public record BankAccountRequest(@NotBlank @Size(max = 80) String name, @Size(max = 80) String bankName,
                                     @Size(max = 5) String bankCode, @Size(max = 10) String agency,
                                     @Size(max = 20) String accountNumber, @NotNull br.com.erpadv.models.BankAccount.Kind kind,
                                     @jakarta.validation.constraints.Min(-100_000_000_000L) @Max(100_000_000_000L) long initialBalanceCents,
                                     LocalDate initialBalanceDate, boolean active) {
    }

    public record BankAccountResponse(String id, String name, String bankName, String bankCode, String agency, String accountNumber,
                                      String accountMasked, br.com.erpadv.models.BankAccount.Kind kind, long initialBalanceCents,
                                      LocalDate initialBalanceDate, boolean active, long balanceCents, long incomeCents,
                                      long expenseCents, int movements) {
    }

    public record StatementLine(String transactionId, LocalDate date, String description, String counterparty,
                                FinanceCategory category, String categoryLabel, long amountCents, long balanceCents) {
    }

    public record StatementResponse(BankAccountResponse account, LocalDate from, LocalDate to, long openingCents,
                                    long closingCents, List<StatementLine> lines) {
    }

    public record CollectionRequest(@NotNull @Size(min = 1, max = 50) List<@NotBlank String> transactionIds,
                                    @NotNull br.com.erpadv.models.FinanceCollection.Channel channel,
                                    @NotNull br.com.erpadv.models.FinanceCollection.Outcome outcome,
                                    @Size(max = 2000) String message, LocalDate promisedDate) {
    }

    public record CollectionResponse(String id, String clientId, String clientName, List<String> transactionIds, long amountCents,
                                     br.com.erpadv.models.FinanceCollection.Channel channel,
                                     br.com.erpadv.models.FinanceCollection.Outcome outcome, String message, LocalDate promisedDate,
                                     String createdByName, Instant createdAt) {
        public static CollectionResponse of(br.com.erpadv.models.FinanceCollection c) {
            return new CollectionResponse(c.getId(), c.getClientId(), c.getClientName(), c.getTransactionIds(), c.getAmountCents(),
                    c.getChannel(), c.getOutcome(), c.getMessage(), c.getPromisedDate(), c.getCreatedByName(), c.getCreatedAt());
        }
    }

    /** Mensagem de cobrança pronta (o envio é feito pelo próprio escritório: WhatsApp, e-mail ou carta). */
    public record CollectionDraft(String clientId, String clientName, String phone, String email, long amountCents,
                                  int titles, String message, String whatsappUrl) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record TransactionResponse(
            String id,
            FinanceDirection direction,
            FinanceCategory category,
            String categoryLabel,
            FinanceScope scope,
            String description,
            long amountCents,
            LocalDate dueDate,
            TransactionStatus status,
            Situation situation,
            Long daysOverdue,
            LocalDate paidDate,
            Long paidAmountCents,
            PaymentMethod paymentMethod,
            String clientId,
            String clientName,
            String caseId,
            String caseNumber,
            String contractId,
            String counterparty,
            String documentNumber,
            String notes,
            String groupId,
            Integer installmentNumber,
            Integer installmentCount,
            boolean recurrent,
            String canceledReason,
            boolean hasReceipt,
            String receiptName,
            Instant createdAt,
            String costCenterId,
            String costCenterName,
            String bankAccountId,
            String bankAccountName,
            String receiptNumber) {

        public static TransactionResponse of(FinancialTransaction t, LocalDate today) {
            Situation situation;
            Long overdue = null;
            if (t.getStatus() == TransactionStatus.PAGO) {
                situation = Situation.PAGO;
            } else if (t.getStatus() == TransactionStatus.CANCELADO) {
                situation = Situation.CANCELADO;
            } else if (t.getDueDate().isBefore(today)) {
                situation = Situation.ATRASADO;
                overdue = ChronoUnit.DAYS.between(t.getDueDate(), today);
            } else if (t.getDueDate().isEqual(today)) {
                situation = Situation.VENCE_HOJE;
            } else {
                situation = Situation.A_VENCER;
            }
            return new TransactionResponse(t.getId(), t.getDirection(), t.getCategory(),
                    t.getCategory() == null ? null : t.getCategory().label(), t.getScope(), t.getDescription(),
                    t.getAmountCents(), t.getDueDate(), t.getStatus(), situation, overdue, t.getPaidDate(),
                    t.getPaidAmountCents(), t.getPaymentMethod(), t.getClientId(), t.getClientName(), t.getCaseId(),
                    t.getCaseNumber(), t.getContractId(), t.getCounterparty(), t.getDocumentNumber(), t.getNotes(), t.getGroupId(),
                    t.getInstallmentNumber(), t.getInstallmentCount(), t.isRecurrent(), t.getCanceledReason(),
                    t.getReceiptPath() != null, t.getReceiptName(), t.getCreatedAt(), t.getCostCenterId(), t.getCostCenterName(),
                    t.getBankAccountId(), t.getBankAccountName(), t.getReceiptNumber());
        }
    }

    public record TransactionFilter(
            FinanceScope scope,
            FinanceDirection direction,
            View view,
            FinanceCategory category,
            String clientId,
            LocalDate from,
            LocalDate to,
            String contractId,
            String costCenterId) {

        public TransactionFilter(FinanceScope scope, FinanceDirection direction, View view, FinanceCategory category,
                                 String clientId, LocalDate from, LocalDate to) {
            this(scope, direction, view, category, clientId, from, to, null, null);
        }

        public TransactionFilter(FinanceScope scope, FinanceDirection direction, View view, FinanceCategory category,
                                 String clientId, LocalDate from, LocalDate to, String contractId) {
            this(scope, direction, view, category, clientId, from, to, contractId, null);
        }
    }

    // ------------------------------------------------------------------ relatórios

    public record MonthFlow(String month, long incomeCents, long expenseCents, long netCents, long cumulativeCents,
                            long forecastIncomeCents, long forecastExpenseCents) {
    }

    public record CategoryAmount(FinanceCategory category, String label, String group, long amountCents) {
    }

    public record DreLine(String label, long amountCents, int level, String kind) {
    }

    public record Dre(List<DreLine> lines, long grossRevenueCents, long deductionsCents, long netRevenueCents,
                      long expensesCents, long resultCents, Double marginPercent) {
    }

    public record FinanceSummary(
            LocalDate from,
            LocalDate to,
            FinanceScope scope,
            long receivedCents,
            long paidCents,
            long balanceCents,
            long receivableCents,
            long payableCents,
            long overdueReceivableCents,
            long overdueReceivableCount,
            long overduePayableCents,
            long overduePayableCount,
            long feesReceivedCents,
            long feesPendingCents,
            long currentMonthIncomeCents,
            List<MonthFlow> monthly,
            List<MonthFlow> projection,
            List<CategoryAmount> incomeByCategory,
            List<CategoryAmount> expenseByCategory,
            Dre dre) {
    }

    public record DelinquentClient(String clientId, String clientName, long totalCents, long count,
                                   LocalDate oldestDueDate, long maxDaysOverdue) {
    }

    public record AgingBucket(String label, long amountCents, long count) {
    }

    public record DelinquencyReport(long totalCents, long count, List<AgingBucket> aging, List<DelinquentClient> clients,
                                    boolean truncated) {
    }
}
