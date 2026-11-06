package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Processo judicial: {@code offices/{officeId}/cases/{id}}. */
@Data
@EqualsAndHashCode(callSuper = true)
public class LegalCase extends BaseEntity {
    private String cnjNumber;
    private String clientId;
    private String clientName;
    private String opposingParty;
    private String court;
    private String courtDivision;
    private String district;
    private String state;
    private LegalArea area;
    private String proceduralClass;
    private String subject;
    private String responsibleUid;
    private String responsibleName;
    private Long claimValueCents;
    private LocalDate distributionDate;
    private CaseStatus status;
    private CaseStatus statusBeforeArchive;
    private String notes;
    /** Marcado manualmente como sigiloso. */
    private boolean confidential;
    /** Calculado: criminal ou sigiloso — exige CRIMINAL_READ para ser listado/consultado. */
    private boolean restricted;
    private boolean archived;
    private Instant archivedAt;
    private List<String> searchTokens = new ArrayList<>();
}
