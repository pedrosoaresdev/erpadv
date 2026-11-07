package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Cartão de quadro ({@code offices/{officeId}/board_cards/{id}}). */
@Data
@EqualsAndHashCode(callSuper = true)
public class BoardCard extends BaseEntity {
    private String boardId;
    private String columnId;
    /** Ordem dentro da coluna (fracionária: inserir entre dois cartões não reordena os demais). */
    private double position;
    private String title;
    private String description;
    private List<String> assigneeUids = new ArrayList<>();
    private List<String> labelIds = new ArrayList<>();
    /** BAIXA, MEDIA, ALTA, URGENTE */
    private String priority;
    private LocalDate startDate;
    private LocalDate dueDate;
    private boolean completed;
    private Instant completedAt;
    private List<ChecklistItem> checklist = new ArrayList<>();
    private String clientId;
    private String clientName;
    private String caseId;
    private String caseNumber;
    private String deadlineId;
    private String leadId;
    private Integer estimateHours;
    private Instant columnEnteredAt;
    private Instant lastActivityAt;
    private int commentCount;
    private boolean archived;

    @Data
    public static class ChecklistItem {
        private String id;
        private String text;
        private boolean done;
    }
}
