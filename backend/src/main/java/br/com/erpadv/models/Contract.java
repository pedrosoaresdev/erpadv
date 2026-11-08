package br.com.erpadv.models;

import br.com.erpadv.models.ContractEnums.ContractStatus;
import br.com.erpadv.models.ContractEnums.ContractType;
import br.com.erpadv.models.ContractEnums.FeeModel;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Contrato: {@code offices/{officeId}/contracts/{id}}. Os honorários viram contas a receber no
 * financeiro (campo {@code contractId}). O histórico de alterações vem da trilha de auditoria.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Contract extends BaseEntity {
    private String number;
    private String title;
    private ContractType type;
    private ContractStatus status;
    private String clientId;
    private String clientName;
    private String caseId;
    private String caseNumber;
    private String responsibleUid;
    private String responsibleName;
    /** Objeto/escopo dos serviços. */
    private String object;
    private LocalDate startDate;
    private LocalDate endDate;

    private FeeModel feeModel;
    /** Valor total (FIXO/PARCELADO) em centavos. */
    private Long amountCents;
    private Integer installments;
    private LocalDate firstDueDate;
    private Long monthlyCents;
    private Integer months;
    private List<FeeStage> stages = new ArrayList<>();
    /** Percentual de êxito em pontos-base (2000 = 20%). */
    private Integer successPercentBp;
    private String successBase;
    private boolean successBilled;
    private String successTransactionId;

    private String templateId;
    private String body;
    private Instant bodyGeneratedAt;
    private String signedDocumentId;
    private Instant signedAt;
    private Instant activatedAt;
    private Instant closedAt;
    private String cancelReason;
    private List<Integer> expiryAlertsSent = new ArrayList<>();
    /** Histórico de renovações (prorrogação do término e, no partido mensal, novas mensalidades). */
    private List<Renewal> renewals = new ArrayList<>();
    private List<String> searchTokens = new ArrayList<>();

    @Data
    public static class FeeStage {
        private String id;
        private String description;
        private long amountCents;
        private LocalDate dueDate;
        private boolean billed;
        private String transactionId;
    }

    @Data
    public static class Renewal {
        private LocalDate renewedOn;
        private LocalDate previousEndDate;
        private LocalDate newEndDate;
        private Integer monthsAdded;
        private Long monthlyCents;
        private String note;
        private String byName;
    }
}
