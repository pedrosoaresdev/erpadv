package br.com.erpadv.services.deadlines;

import br.com.erpadv.models.CalendarEntryKind;
import br.com.erpadv.models.CountingMode;
import br.com.erpadv.models.CourtCalendarEntry;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Calculadora de prazos processuais — SUGESTÃO que deve ser validada pelo advogado responsável.
 *
 * <ul>
 *   <li>{@link CountingMode#UTEIS} (CPC, arts. 219 e 224): exclui o dia do começo, conta apenas dias
 *       úteis; feriados, fins de semana, suspensões cadastradas e o recesso de 20/12 a 20/01
 *       (art. 220) não são contados.</li>
 *   <li>{@link CountingMode#CORRIDOS} (ex.: CPP, art. 798; prazos materiais): conta dias corridos a
 *       partir do dia seguinte; se o termo final cair em dia sem expediente, prorroga para o
 *       primeiro dia útil seguinte.</li>
 * </ul>
 * Regras especiais (prazo em dobro, publicação no DJe, intimação eletrônica tácita, calendários
 * estaduais) não são presumidas: dependem dos dados informados e do cadastro do escritório.
 */
public final class DeadlineCalculator {

    public record SkippedDay(LocalDate date, String reason) {
    }

    public record Result(LocalDate startDate, int days, CountingMode mode, LocalDate dueDate,
                         List<SkippedDay> skippedDays, List<String> notes) {
    }

    private static final int MAX_DAYS = 3650;

    private final List<CourtCalendarEntry> entries;
    private final String court;
    private final Map<Integer, Map<LocalDate, String>> holidayCache = new HashMap<>();

    /**
     * @param entries feriados/suspensões do escritório
     * @param court   tribunal do processo (entradas com tribunal diferente são ignoradas)
     */
    public DeadlineCalculator(List<CourtCalendarEntry> entries, String court) {
        this.entries = entries == null ? List.of() : entries;
        this.court = court == null || court.isBlank() ? null : court.trim().toUpperCase();
    }

    public Result calculate(LocalDate start, int days, CountingMode mode) {
        if (start == null || days < 1 || days > MAX_DAYS || mode == null || mode == CountingMode.MANUAL) {
            throw new IllegalArgumentException("Parâmetros de cálculo inválidos.");
        }
        List<SkippedDay> skipped = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        LocalDate due;
        if (mode == CountingMode.UTEIS) {
            LocalDate day = start;
            int counted = 0;
            while (counted < days) {
                day = day.plusDays(1);
                Optional<String> reason = nonBusinessReason(day, true);
                if (reason.isPresent()) {
                    skipped.add(new SkippedDay(day, reason.get()));
                } else {
                    counted++;
                }
            }
            due = day;
            notes.add("Contagem em dias úteis, excluído o dia do começo e incluído o do vencimento (CPC, arts. 219 e 224).");
        } else {
            due = start.plusDays(days);
            Optional<String> reason = nonBusinessReason(due, false);
            while (reason.isPresent()) {
                skipped.add(new SkippedDay(due, reason.get() + " — prorrogação"));
                due = due.plusDays(1);
                reason = nonBusinessReason(due, false);
            }
            notes.add("Contagem em dias corridos, com prorrogação do termo final para o primeiro dia útil.");
        }
        notes.add("Cálculo sugerido pelo sistema: confira publicação/intimação, prazos em dobro e o calendário do tribunal.");
        return new Result(start, days, mode, due, skipped, notes);
    }

    /** Motivo pelo qual o dia não é útil, ou vazio se for dia útil. */
    public Optional<String> nonBusinessReason(LocalDate day, boolean applyForensicRecess) {
        if (day.getDayOfWeek() == DayOfWeek.SATURDAY) {
            return Optional.of("Sábado");
        }
        if (day.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return Optional.of("Domingo");
        }
        String holiday = holidayCache.computeIfAbsent(day.getYear(), BrazilianHolidays::of).get(day);
        if (holiday != null) {
            return Optional.of("Feriado: " + holiday);
        }
        if (applyForensicRecess && isRecess(day)) {
            return Optional.of("Recesso forense (CPC, art. 220)");
        }
        for (CourtCalendarEntry e : entries) {
            if (e.getStartDate() == null) {
                continue;
            }
            LocalDate end = e.getEndDate() == null ? e.getStartDate() : e.getEndDate();
            boolean appliesToCourt = e.getCourt() == null || e.getCourt().isBlank()
                    || (court != null && court.equalsIgnoreCase(e.getCourt().trim()));
            if (appliesToCourt && !day.isBefore(e.getStartDate()) && !day.isAfter(end)) {
                String prefix = e.getKind() == CalendarEntryKind.SUSPENSAO ? "Suspensão" : "Feriado";
                return Optional.of(prefix + ": " + e.getDescription());
            }
        }
        return Optional.empty();
    }

    static boolean isRecess(LocalDate day) {
        MonthDay md = MonthDay.from(day);
        return !md.isBefore(MonthDay.of(12, 20)) || !md.isAfter(MonthDay.of(1, 20));
    }
}
