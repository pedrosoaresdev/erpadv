package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.time.LocalDate;

/** Proposta de honorários enviada a uma oportunidade do CRM ({@code offices/{officeId}/lead_proposals/{id}}). */
@Data
@EqualsAndHashCode(callSuper = true)
public class LeadProposal extends BaseEntity {

    public enum Status { RASCUNHO, ENVIADA, ACEITA, RECUSADA }

    private String leadId;
    private String leadName;
    private String number;
    private String title;
    /** Escopo dos serviços (texto livre). */
    private String scope;
    /** Forma de cobrança (ex.: "R$ 5.000,00 em 5 parcelas + 20% de êxito"). */
    private String feeTerms;
    private long valueCents;
    private LocalDate validUntil;
    private Status status = Status.RASCUNHO;
    private Instant sentAt;
    private Instant decidedAt;
    private String decisionNote;
    private String createdByName;
}
