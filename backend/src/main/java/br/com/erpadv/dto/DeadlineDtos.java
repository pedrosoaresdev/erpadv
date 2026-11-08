package br.com.erpadv.dto;

import br.com.erpadv.models.CalendarEntryKind;
import br.com.erpadv.models.CountingMode;
import br.com.erpadv.models.CourtCalendarEntry;
import br.com.erpadv.models.Deadline;
import br.com.erpadv.models.DeadlinePriority;
import br.com.erpadv.models.DeadlineStatus;
import br.com.erpadv.models.DeadlineType;
import br.com.erpadv.services.deadlines.DeadlineCalculator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

public final class DeadlineDtos {

    private DeadlineDtos() {
    }

    /** Situação exibida (derivada): vencido não é persistido. */
    public enum Situation { FUTURO, URGENTE, HOJE, VENCIDO, CONCLUIDO, CANCELADO }

    public static final int URGENT_DAYS = 3;

    public record DeadlineRequest(
            @Size(max = 128) String caseId,
            @Size(max = 128) String clientId,
            @NotBlank @Size(max = 300) String description,
            @NotNull DeadlineType type,
            @NotNull LocalDate startDate,
            /** Obrigatório quando countingMode = MANUAL; nos demais modos é calculado pelo servidor. */
            LocalDate dueDate,
            @Min(1) @Max(3650) Integer days,
            @NotNull CountingMode countingMode,
            @Size(max = 120) String court,
            @NotBlank @Size(max = 128) String responsibleUid,
            @NotNull DeadlinePriority priority,
            @Size(max = 4000) String notes,
            br.com.erpadv.models.DeadlineOrigin origin,
            LocalDate availabilityDate,
            LocalDate publicationDate,
            LocalDate awarenessDate) {

        public DeadlineRequest(String caseId, String clientId, String description, DeadlineType type, LocalDate startDate,
                               LocalDate dueDate, Integer days, CountingMode countingMode, String court, String responsibleUid,
                               DeadlinePriority priority, String notes) {
            this(caseId, clientId, description, type, startDate, dueDate, days, countingMode, court, responsibleUid,
                    priority, notes, null, null, null, null);
        }
    }

    /** Publicação = 1º dia útil seguinte à disponibilização (Lei 11.419/2006, art. 4º, § 3º). */
    public record PublicationDateResponse(LocalDate availabilityDate, LocalDate publicationDate, LocalDate suggestedStartDate,
                                          List<String> notes) {
    }

    public record DeadlineResponse(
            String id,
            String caseId,
            String caseNumber,
            String clientId,
            String clientName,
            String description,
            DeadlineType type,
            String typeLabel,
            LocalDate startDate,
            LocalDate dueDate,
            Integer days,
            CountingMode countingMode,
            String court,
            String responsibleUid,
            String responsibleName,
            DeadlinePriority priority,
            DeadlineStatus status,
            Situation situation,
            Long daysLeft,
            String notes,
            boolean validated,
            String validatedByName,
            Instant validatedAt,
            Instant completedAt,
            String completedByName,
            boolean restricted,
            Instant createdAt,
            Instant updatedAt,
            br.com.erpadv.models.DeadlineOrigin origin,
            String originLabel,
            LocalDate availabilityDate,
            LocalDate publicationDate,
            LocalDate awarenessDate,
            String receiptName,
            Instant receiptUploadedAt) {

        public static DeadlineResponse of(Deadline d, LocalDate today) {
            Long daysLeft = d.getDueDate() == null ? null : ChronoUnit.DAYS.between(today, d.getDueDate());
            return new DeadlineResponse(d.getId(), d.getCaseId(), d.getCaseNumber(), d.getClientId(), d.getClientName(),
                    d.getDescription(), d.getType(), d.getType() == null ? null : d.getType().label(), d.getStartDate(),
                    d.getDueDate(), d.getDays(), d.getCountingMode(), d.getCourt(), d.getResponsibleUid(),
                    d.getResponsibleName(), d.getPriority(), d.getStatus(), situation(d.getStatus(), daysLeft), daysLeft,
                    d.getNotes(), d.isValidated(), d.getValidatedByName(), d.getValidatedAt(), d.getCompletedAt(),
                    d.getCompletedByName(), d.isRestricted(), d.getCreatedAt(), d.getUpdatedAt(), d.getOrigin(),
                    d.getOrigin() == null ? null : d.getOrigin().label(), d.getAvailabilityDate(), d.getPublicationDate(),
                    d.getAwarenessDate(), d.getReceiptName(), d.getReceiptUploadedAt());
        }

        static Situation situation(DeadlineStatus status, Long daysLeft) {
            if (status == DeadlineStatus.CONCLUIDO) return Situation.CONCLUIDO;
            if (status == DeadlineStatus.CANCELADO) return Situation.CANCELADO;
            if (daysLeft == null) return Situation.FUTURO;
            if (daysLeft < 0) return Situation.VENCIDO;
            if (daysLeft == 0) return Situation.HOJE;
            if (daysLeft <= URGENT_DAYS) return Situation.URGENTE;
            return Situation.FUTURO;
        }
    }

    public record DeadlineStats(long dueToday, long urgent, long upcoming, long overdue, long completed,
                                long unvalidated) {
    }

    public record CalculateRequest(
            @NotNull LocalDate startDate,
            @NotNull @Min(1) @Max(3650) Integer days,
            @NotNull CountingMode countingMode,
            @Size(max = 120) String court) {
    }

    public record CalculationResponse(LocalDate startDate, int days, CountingMode mode, LocalDate dueDate,
                                      List<DeadlineCalculator.SkippedDay> skippedDays, List<String> notes) {
        public static CalculationResponse of(DeadlineCalculator.Result r) {
            return new CalculationResponse(r.startDate(), r.days(), r.mode(), r.dueDate(), r.skippedDays(), r.notes());
        }
    }

    public record CompleteRequest(@Size(max = 1000) String note) {
    }

    public record CourtCalendarRequest(
            @NotNull LocalDate startDate,
            LocalDate endDate,
            @Size(max = 120) String court,
            @NotBlank @Size(max = 200) String description,
            @NotNull CalendarEntryKind kind) {
    }

    public record HolidayInfo(LocalDate date, String description, boolean national) {
    }

    public record CourtCalendarResponse(List<HolidayInfo> national, List<CourtCalendarEntry> office) {
    }
}
