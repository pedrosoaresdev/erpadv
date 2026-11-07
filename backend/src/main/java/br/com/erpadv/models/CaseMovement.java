package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/** Movimentação processual: {@code offices/{officeId}/case_movements/{id}}. */
@Data
@EqualsAndHashCode(callSuper = true)
public class CaseMovement extends BaseEntity {
    private String caseId;
    private MovementType type;
    private LocalDate date;
    private String title;
    private String description;
    /** MANUAL ou INTEGRACAO (consulta processual automatizada — etapa futura). */
    private String source;
    /** Marcada como importante (destaque na linha do tempo e nos alertas). */
    private boolean important;
    private String authorUid;
    private String authorName;
    /** Ordenação estável da linha do tempo: data do ato + instante do registro. */
    private String sortKey;
}
