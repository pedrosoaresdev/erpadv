package br.com.erpadv.dto;

import br.com.erpadv.models.Appointment;
import br.com.erpadv.models.AppointmentType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class CalendarDtos {

    private CalendarDtos() {
    }

    public record AppointmentRequest(
            @NotNull AppointmentType type,
            @NotBlank @Size(max = 200) String title,
            @NotNull Instant start,
            @NotNull Instant end,
            boolean allDay,
            @Size(max = 300) String location,
            @Size(max = 500) @Pattern(regexp = "^$|^https://.*", message = "Use um link https://") String meetingUrl,
            @Size(max = 4000) String description,
            @NotEmpty @Size(max = 20) List<@NotBlank String> responsibleUids,
            @Size(max = 128) String clientId,
            @Size(max = 128) String caseId,
            @Size(max = 5) List<@NotNull @Min(0) @Max(10080) Integer> reminders) {
    }

    public record AppointmentResponse(
            String id,
            AppointmentType type,
            String typeLabel,
            String title,
            Instant start,
            Instant end,
            boolean allDay,
            String location,
            String meetingUrl,
            String description,
            List<String> responsibleUids,
            List<String> responsibleNames,
            String clientId,
            String clientName,
            String caseId,
            String caseNumber,
            List<Integer> reminders,
            boolean restricted,
            Instant createdAt) {

        public static AppointmentResponse of(Appointment a) {
            return new AppointmentResponse(a.getId(), a.getType(), a.getType() == null ? null : a.getType().label(),
                    a.getTitle(), a.getStart(), a.getEnd(), a.isAllDay(), a.getLocation(), a.getMeetingUrl(),
                    a.getDescription(), a.getResponsibleUids(), a.getResponsibleNames(), a.getClientId(),
                    a.getClientName(), a.getCaseId(), a.getCaseNumber(), a.getReminders(), a.isRestricted(),
                    a.getCreatedAt());
        }
    }

    /** Item unificado da agenda: compromisso ou prazo (dia inteiro). */
    public record CalendarEvent(
            String id,
            String kind,
            String type,
            String typeLabel,
            String title,
            Instant start,
            Instant end,
            boolean allDay,
            String status,
            String location,
            String caseId,
            String caseNumber,
            String clientName,
            List<String> responsibleNames,
            String priority,
            Boolean validated) {
    }

    public record DeviceTokenRequest(
            @NotBlank @Size(max = 4096) String token,
            @Pattern(regexp = "^(WEB|ANDROID|IOS)$") String platform) {
    }
}
