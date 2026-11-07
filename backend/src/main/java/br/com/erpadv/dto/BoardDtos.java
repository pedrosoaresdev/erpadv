package br.com.erpadv.dto;

import br.com.erpadv.models.Board;
import br.com.erpadv.models.BoardCard;
import br.com.erpadv.models.CardActivity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class BoardDtos {

    public static final String COLOR = "^$|^#[0-9a-fA-F]{6}$";
    public static final String PRIORITY = "^(BAIXA|MEDIA|ALTA|URGENTE)$";

    private BoardDtos() {
    }

    public record BoardRequest(
            @NotBlank @Size(max = 80) String name,
            @Size(max = 500) String description,
            @Pattern(regexp = COLOR, message = "Cor inválida") String color,
            @NotBlank @Pattern(regexp = "^(OFFICE|MEMBERS)$", message = "Visibilidade inválida") String visibility,
            @Size(max = 100) List<String> memberUids,
            @Size(max = 40) String templateKey) {
    }

    public record ColumnRequest(
            @Size(max = 40) String id,
            @NotBlank @Size(max = 40) String name,
            @Pattern(regexp = COLOR, message = "Cor inválida") String color,
            @Min(0) @Max(500) int wipLimit,
            boolean done,
            boolean notifyAssignees,
            @Size(max = 40) String addLabelId,
            @Size(max = 128) String assignUid,
            @Min(0) @Max(365) Integer setDueInDays) {
    }

    public record ColumnsRequest(@NotEmpty @Size(max = 20) List<@Valid ColumnRequest> columns) {
    }

    public record LabelRequest(
            @Size(max = 40) String id,
            @NotBlank @Size(max = 30) String name,
            @NotBlank @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "Cor inválida") String color) {
    }

    public record LabelsRequest(@NotNull @Size(max = 30) List<@Valid LabelRequest> labels) {
    }

    public record ChecklistItemRequest(@Size(max = 40) String id, @NotBlank @Size(max = 200) String text, boolean done) {
    }

    public record CardRequest(
            @Size(max = 40) String columnId,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 8000) String description,
            @Size(max = 20) List<String> assigneeUids,
            @Size(max = 20) List<String> labelIds,
            @NotBlank @Pattern(regexp = PRIORITY, message = "Prioridade inválida") String priority,
            LocalDate startDate,
            LocalDate dueDate,
            @Size(max = 100) List<@Valid ChecklistItemRequest> checklist,
            @Size(max = 128) String clientId,
            @Size(max = 128) String caseId,
            @Min(0) @Max(1000) Integer estimateHours) {
    }

    public record MoveRequest(@NotBlank @Size(max = 40) String columnId, Double position) {
    }

    public record CommentRequest(@NotBlank @Size(max = 4000) String text, @Size(max = 20) List<String> mentionUids) {
    }

    public record BoardSummary(
            String id,
            String name,
            String description,
            String color,
            String visibility,
            String ownerUid,
            String ownerName,
            List<String> memberUids,
            int columns,
            long openCards,
            boolean archived,
            boolean canManage,
            Instant updatedAt) {
    }

    public record BoardView(Board board, List<BoardCard> cards, boolean canManage) {
    }

    public record CardDetail(BoardCard card, List<CardActivity> activity, String boardName) {
    }

    public record MyCard(BoardCard card, String boardName, String columnName, String boardColor) {
    }

    public record BoardTemplate(String key, String name, String description, List<String> columns) {
    }

    public record ColumnInsight(String columnId, String name, long cards, int wipLimit, double avgAgeDays,
                                boolean overLimit) {
    }

    public record MemberInsight(String uid, String name, long open, long overdue, long completed30d) {
    }

    public record BoardInsights(
            long open,
            long completed,
            long overdue,
            long dueThisWeek,
            long unassigned,
            long stale,
            long completed7d,
            double avgCycleDays,
            List<ColumnInsight> columns,
            List<MemberInsight> members,
            List<Long> throughputWeeks,
            List<String> alerts) {
    }
}
