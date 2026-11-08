package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/** Comunicação processual do DJEN ({@code offices/{officeId}/publications/djen-{id}}). */
@Data
@EqualsAndHashCode(callSuper = true)
public class Publication extends BaseEntity {

    public enum Status { NOVA, LIDA, PRAZO_CRIADO, ARQUIVADA }

    private String source = "DJEN";
    private long externalId;
    private String hash;
    private LocalDate availableDate;
    private String court;
    private String kind;
    private String documentType;
    private String organ;
    private String className;
    private String text;
    private String caseNumber;
    private String caseNumberDigits;
    private String link;
    private String oab;
    private String lawyerUid;
    private String lawyerName;
    private String caseId;
    private String clientName;
    private boolean restricted;
    private Status status = Status.NOVA;
    private String deadlineId;
    private java.time.Instant readAt;
    private String readByName;
}
