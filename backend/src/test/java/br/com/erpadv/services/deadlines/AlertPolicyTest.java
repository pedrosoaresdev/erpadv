package br.com.erpadv.services.deadlines;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AlertPolicyTest {

    @Test
    void enviaNosMarcos10_5_3_1_0() {
        assertThat(AlertPolicy.evaluate(10, List.of())).get()
                .extracting(AlertPolicy.Decision::markAsSent).isEqualTo(Set.of(10));
        assertThat(AlertPolicy.evaluate(9, List.of(10))).isEmpty();
        assertThat(AlertPolicy.evaluate(5, List.of(10))).get()
                .extracting(AlertPolicy.Decision::markAsSent).isEqualTo(Set.of(5));
        assertThat(AlertPolicy.evaluate(0, List.of(10, 5, 3, 1))).get()
                .extracting(AlertPolicy.Decision::markAsSent).isEqualTo(Set.of(0));
    }

    @Test
    void marcosPerdidosGeramUmUnicoAlerta() {
        var decision = AlertPolicy.evaluate(4, List.of()).orElseThrow();
        assertThat(decision.daysLeft()).isEqualTo(4);
        assertThat(decision.markAsSent()).containsExactlyInAnyOrder(10, 5);
        assertThat(AlertPolicy.evaluate(4, List.of(10, 5))).isEmpty();
    }

    @Test
    void foraDaJanelaNaoAlerta() {
        assertThat(AlertPolicy.evaluate(11, List.of())).isEmpty();
        assertThat(AlertPolicy.evaluate(-1, List.of())).isEmpty();
    }

    @Test
    void lembretesDeAgenda() {
        assertThat(AlertPolicy.dueReminders(50, List.of(15, 60, 1440), List.of(1440)))
                .containsExactlyInAnyOrder(60);
        assertThat(AlertPolicy.dueReminders(10, List.of(15, 60), List.of(60))).containsExactly(15);
        assertThat(AlertPolicy.dueReminders(-5, List.of(15), List.of())).isEmpty();
    }

    @Test
    void textoDosAlertas() {
        assertThat(AlertPolicy.describe(0)).isEqualTo("vence HOJE");
        assertThat(AlertPolicy.describe(1)).isEqualTo("vence amanhã");
        assertThat(AlertPolicy.describe(5)).isEqualTo("vence em 5 dias");
    }
}
