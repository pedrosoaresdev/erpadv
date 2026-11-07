package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Prazo jurídico: {@code offices/{officeId}/deadlines/{id}}. */
@Data
@EqualsAndHashCode(callSuper = true)
public class Deadline extends BaseEntity {
    private String caseId;
    private String caseNumber;
    private String clientId;
    private String clientName;
    private String description;
    private DeadlineType type;
    /** Data da intimação/ciência (início da contagem). */
    private LocalDate startDate;
    private LocalDate dueDate;
    private Integer days;
    private CountingMode countingMode;
    private String court;
    private String responsibleUid;
    private String responsibleName;
    private DeadlinePriority priority;
    private DeadlineStatus status;
    private String notes;
    /** Contagem conferida pelo advogado responsável. */
    private boolean validated;
    private String validatedBy;
    private String validatedByName;
    private Instant validatedAt;
    private Instant completedAt;
    private String completedBy;
    private String completedByName;
    /** Alertas já enviados (dias antes do vencimento: 10, 5, 3, 1, 0). */
    private List<Integer> alertsSent = new ArrayList<>();
    /** Herdado do processo (criminal/sigiloso). */
    private boolean restricted;
}
