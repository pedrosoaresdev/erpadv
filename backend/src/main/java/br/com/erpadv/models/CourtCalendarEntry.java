package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * Feriado local/forense ou suspensão de expediente cadastrada pelo escritório:
 * {@code offices/{officeId}/court_calendar/{id}}. {@code court} nulo vale para todos os tribunais.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CourtCalendarEntry extends BaseEntity {
    private LocalDate startDate;
    private LocalDate endDate;
    private String court;
    private String description;
    private CalendarEntryKind kind;
}
