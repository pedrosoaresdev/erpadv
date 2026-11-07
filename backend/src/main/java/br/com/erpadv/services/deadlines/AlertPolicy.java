package br.com.erpadv.services.deadlines;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Política de alertas: 10, 5, 3 e 1 dia(s) antes e no dia do vencimento.
 * Se um marco foi perdido (ex.: prazo cadastrado faltando 4 dias), envia um único alerta e marca
 * como enviados todos os marcos já ultrapassados — evita rajadas de notificações repetidas.
 */
public final class AlertPolicy {

    public static final List<Integer> THRESHOLDS = List.of(10, 5, 3, 1, 0);

    public record Decision(int daysLeft, Set<Integer> markAsSent) {
    }

    private AlertPolicy() {
    }

    public static Optional<Decision> evaluate(long daysLeft, List<Integer> alreadySent) {
        if (daysLeft < 0 || daysLeft > THRESHOLDS.get(0)) {
            return Optional.empty();
        }
        Set<Integer> sent = alreadySent == null ? Set.of() : Set.copyOf(alreadySent);
        Set<Integer> due = new TreeSet<>();
        for (int t : THRESHOLDS) {
            if (t >= daysLeft && !sent.contains(t)) {
                due.add(t);
            }
        }
        if (due.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Decision((int) daysLeft, due));
    }

    /** Lembretes de agenda (minutos antes) que já devem disparar e ainda não foram enviados. */
    public static Set<Integer> dueReminders(long minutesUntilStart, List<Integer> reminders, List<Integer> sent) {
        Set<Integer> result = new TreeSet<>();
        if (reminders == null || minutesUntilStart < 0) {
            return result;
        }
        for (Integer r : reminders) {
            if (r != null && minutesUntilStart <= r && (sent == null || !sent.contains(r))) {
                result.add(r);
            }
        }
        return result;
    }

    public static String describe(int daysLeft) {
        return switch (daysLeft) {
            case 0 -> "vence HOJE";
            case 1 -> "vence amanhã";
            default -> "vence em " + daysLeft + " dias";
        };
    }
}
