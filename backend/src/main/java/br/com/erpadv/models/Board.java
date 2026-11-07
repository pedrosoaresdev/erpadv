package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * Quadro de tarefas ({@code offices/{officeId}/boards/{id}}): colunas com limite WIP e automações,
 * etiquetas e visibilidade (todo o escritório ou somente membros).
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Board extends BaseEntity {
    private String name;
    private String description;
    private String color;
    private String templateKey;
    /** OFFICE = toda a equipe com acesso a quadros; MEMBERS = somente membros. */
    private String visibility;
    private String ownerUid;
    private String ownerName;
    private List<String> memberUids = new ArrayList<>();
    private List<BoardColumn> columns = new ArrayList<>();
    private List<BoardLabel> labels = new ArrayList<>();
    private boolean archived;

    @Data
    public static class BoardColumn {
        private String id;
        private String name;
        private String color;
        /** Limite de cartões em andamento (0 = sem limite). */
        private int wipLimit;
        /** Coluna de conclusão: cartões que entram aqui são marcados como concluídos. */
        private boolean done;
        private ColumnAutomation automation = new ColumnAutomation();
    }

    /** Ações executadas quando um cartão entra na coluna. */
    @Data
    public static class ColumnAutomation {
        private boolean notifyAssignees;
        private String addLabelId;
        private String assignUid;
        /** Define o vencimento para hoje + N dias quando o cartão ainda não tem data. */
        private Integer setDueInDays;
    }

    @Data
    public static class BoardLabel {
        private String id;
        private String name;
        private String color;
    }
}
