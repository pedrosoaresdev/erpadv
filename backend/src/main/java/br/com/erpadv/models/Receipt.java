package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.time.LocalDate;

/** Recibo ou declaração emitida ao cliente ({@code offices/{officeId}/receipts}), numerada por ano (REC-AAAA-NNNN). */
@Data
@EqualsAndHashCode(callSuper = true)
public class Receipt extends BaseEntity {

    public enum Kind {
        PAGAMENTO("Recibo de pagamento"),
        REEMBOLSO("Recibo de reembolso de despesas"),
        QUITACAO("Declaração de quitação"),
        ENTREGA_DOCUMENTOS("Recibo de entrega de documentos"),
        DEVOLUCAO_DOCUMENTOS("Recibo de devolução de documentos");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** Recibos com valor envolvem o financeiro; os de documentos, a central documental. */
        public boolean monetary() {
            return this == PAGAMENTO || this == REEMBOLSO || this == QUITACAO;
        }
    }

    private String number;
    private Kind kind;
    private String clientId;
    private String clientName;
    private String payerName;
    /** Documento do pagador já mascarado (o PDF mostra o número completo só a quem pode ver dados sensíveis). */
    private String payerDocumentMasked;
    private Long amountCents;
    private String description;
    private LocalDate date;
    private String paymentMethod;
    private String caseId;
    private String caseNumber;
    private String transactionId;
    private String city;
    private String issuerName;
    private String documentId;
    private boolean canceled;
    private String cancelReason;
    private Instant canceledAt;
    private String canceledByName;
}
