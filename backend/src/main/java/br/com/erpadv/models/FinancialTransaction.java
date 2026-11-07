package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.time.LocalDate;

/** Lançamento financeiro (conta a receber ou a pagar): {@code offices/{officeId}/financial_transactions/{id}}. */
@Data
@EqualsAndHashCode(callSuper = true)
public class FinancialTransaction extends BaseEntity {
    private FinanceDirection direction;
    private FinanceCategory category;
    private FinanceScope scope;
    /** Sócio dono do lançamento quando {@code scope = PESSOAL}. */
    private String ownerUid;
    private String description;
    /** Valor em centavos. */
    private long amountCents;
    private LocalDate dueDate;
    private TransactionStatus status;
    private LocalDate paidDate;
    private Long paidAmountCents;
    private PaymentMethod paymentMethod;
    private String clientId;
    private String clientName;
    private String caseId;
    private String caseNumber;
    /** Cliente (receitas) ou fornecedor/favorecido (despesas). */
    private String counterparty;
    private String documentNumber;
    private String notes;
    /** Parcelamento ou recorrência: lançamentos do mesmo grupo. */
    private String groupId;
    private Integer installmentNumber;
    private Integer installmentCount;
    private boolean recurrent;
    private String canceledReason;
    private Instant paidAt;
    private String paidBy;
    // Comprovante (Firebase Storage, acesso apenas via backend)
    private String receiptPath;
    private String receiptName;
    private String receiptContentType;
    private Long receiptSize;
    private String receiptSha256;
    private String receiptUploadedBy;
    private Instant receiptUploadedAt;
}
