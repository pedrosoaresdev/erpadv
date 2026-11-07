package br.com.erpadv.services.deadlines;

import br.com.erpadv.models.CalendarEntryKind;
import br.com.erpadv.models.CountingMode;
import br.com.erpadv.models.CourtCalendarEntry;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeadlineCalculatorTest {

    @Test
    void feriadosMoveisDe2026() {
        assertThat(BrazilianHolidays.easter(2026)).isEqualTo(LocalDate.of(2026, 4, 5));
        assertThat(BrazilianHolidays.easter(2027)).isEqualTo(LocalDate.of(2027, 3, 28));
        var h = BrazilianHolidays.of(2026);
        assertThat(h).containsKeys(LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 17), LocalDate.of(2026, 4, 3),
                LocalDate.of(2026, 6, 4), LocalDate.of(2026, 11, 20));
        assertThat(BrazilianHolidays.of(2023)).doesNotContainKey(LocalDate.of(2023, 11, 20));
    }

    @Test
    void diasUteisExcluiInicioFimDeSemanaEFeriado() {
        var result = new DeadlineCalculator(List.of(), null)
                .calculate(LocalDate.of(2026, 4, 17), 5, CountingMode.UTEIS);
        // 20 (1), 21 Tiradentes, 22 (2), 23 (3), 24 (4), 27 (5)
        assertThat(result.dueDate()).isEqualTo(LocalDate.of(2026, 4, 27));
        assertThat(result.skippedDays()).extracting(DeadlineCalculator.SkippedDay::date)
                .contains(LocalDate.of(2026, 4, 18), LocalDate.of(2026, 4, 19), LocalDate.of(2026, 4, 21));
    }

    @Test
    void recessoForenseSuspendeContagemEmDiasUteis() {
        var result = new DeadlineCalculator(List.of(), null)
                .calculate(LocalDate.of(2026, 12, 18), 15, CountingMode.UTEIS);
        // Retoma em 21/01/2027; Carnaval 08 e 09/02/2027 não contam.
        assertThat(result.dueDate()).isEqualTo(LocalDate.of(2027, 2, 12));
        assertThat(result.skippedDays()).anyMatch(s -> s.reason().contains("Recesso"));
    }

    @Test
    void diasCorridosProrrogamParaDiaUtil() {
        var result = new DeadlineCalculator(List.of(), null)
                .calculate(LocalDate.of(2026, 4, 17), 15, CountingMode.CORRIDOS);
        // 02/05 é sábado → prorroga para segunda 04/05
        assertThat(result.dueDate()).isEqualTo(LocalDate.of(2026, 5, 4));
    }

    @Test
    void diasCorridosNaoSofremRecesso() {
        var result = new DeadlineCalculator(List.of(), null)
                .calculate(LocalDate.of(2026, 12, 10), 10, CountingMode.CORRIDOS);
        assertThat(result.dueDate()).isEqualTo(LocalDate.of(2026, 12, 21));
    }

    @Test
    void suspensaoDoTribunalSoAfetaOTribunalInformado() {
        CourtCalendarEntry suspension = new CourtCalendarEntry();
        suspension.setStartDate(LocalDate.of(2026, 4, 22));
        suspension.setEndDate(LocalDate.of(2026, 4, 23));
        suspension.setCourt("TJSP");
        suspension.setDescription("Indisponibilidade do sistema");
        suspension.setKind(CalendarEntryKind.SUSPENSAO);

        assertThat(new DeadlineCalculator(List.of(suspension), "tjsp")
                .calculate(LocalDate.of(2026, 4, 17), 5, CountingMode.UTEIS).dueDate())
                .isEqualTo(LocalDate.of(2026, 4, 29));
        assertThat(new DeadlineCalculator(List.of(suspension), "TRF3")
                .calculate(LocalDate.of(2026, 4, 17), 5, CountingMode.UTEIS).dueDate())
                .isEqualTo(LocalDate.of(2026, 4, 27));
    }

    @Test
    void feriadoLocalSemTribunalValeParaTodos() {
        CourtCalendarEntry local = new CourtCalendarEntry();
        local.setStartDate(LocalDate.of(2026, 4, 20));
        local.setDescription("Feriado municipal");
        local.setKind(CalendarEntryKind.FERIADO);
        var result = new DeadlineCalculator(List.of(local), "TJSP")
                .calculate(LocalDate.of(2026, 4, 17), 1, CountingMode.UTEIS);
        assertThat(result.dueDate()).isEqualTo(LocalDate.of(2026, 4, 22));
    }

    @Test
    void recusaParametrosInvalidos() {
        var calc = new DeadlineCalculator(List.of(), null);
        assertThatThrownBy(() -> calc.calculate(LocalDate.now(), 0, CountingMode.UTEIS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calc.calculate(LocalDate.now(), 5, CountingMode.MANUAL))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
