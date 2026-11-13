package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/** Fatura da assinatura de um escritório ({@code subscription_invoices}, coleção da plataforma). */
@Data
@EqualsAndHashCode(callSuper = true)
public class SubscriptionInvoice extends BaseEntity {
    public enum Status { ABERTA, VENCIDA, PAGA, CANCELADA }

    private String number;
    private String officeName;
    private String planCode;
    private String planName;
    private String cycle;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private long amountCents;
    private LocalDate dueDate;
    private Status status = Status.ABERTA;
    private LocalDate paidDate;
    private Long paidAmountCents;
    private String paymentMethod;
    private String note;
    private String markedByName;
    private String cancelReason;
    private boolean overdueNotified;
}
