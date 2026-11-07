package br.com.erpadv.models;

import br.com.erpadv.models.CrmEnums.LeadSource;
import br.com.erpadv.models.CrmEnums.LeadStage;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Oportunidade comercial ({@code offices/{officeId}/leads/{id}}). Por minimização de dados (LGPD),
 * CPF/CNPJ só são coletados na conversão em cliente.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Lead extends BaseEntity {
    private String name;
    private String nameLower;
    private ClientType type;
    private String company;
    private String phone;
    private String email;
    private LeadSource source;
    private String sourceDetail;
    private LegalArea legalArea;
    private String subject;
    private LeadStage stage;
    private long estimatedValueCents;
    private String ownerUid;
    private String ownerName;
    private String nextAction;
    private LocalDate nextActionDate;
    private String notes;
    private String lostReason;
    private List<String> tags = new ArrayList<>();
    private Instant stageEnteredAt;
    private Instant lastActivityAt;
    private Instant wonAt;
    private Instant lostAt;
    private String convertedClientId;
}
