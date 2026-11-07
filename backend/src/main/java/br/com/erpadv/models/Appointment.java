package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Evento da agenda jurídica: {@code offices/{officeId}/appointments/{id}}. Audiências são eventos do
 * tipo {@link AppointmentType#AUDIENCIA} (consultadas também pelo dashboard).
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Appointment extends BaseEntity {
    private AppointmentType type;
    private String title;
    private Instant start;
    private Instant end;
    private boolean allDay;
    private String location;
    private String meetingUrl;
    private String description;
    private List<String> responsibleUids = new ArrayList<>();
    private List<String> responsibleNames = new ArrayList<>();
    private String clientId;
    private String clientName;
    private String caseId;
    private String caseNumber;
    /** Lembretes em minutos antes do início (ex.: 15, 60, 1440). */
    private List<Integer> reminders = new ArrayList<>();
    private List<Integer> remindersSent = new ArrayList<>();
    private boolean restricted;
}
