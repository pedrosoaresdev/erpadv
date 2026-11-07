package br.com.erpadv.services.deadlines;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Map;
import java.util.TreeMap;

/**
 * Feriados nacionais (Leis 662/1949, 6.802/1980 e 14.759/2023) e datas móveis em que os tribunais
 * tradicionalmente não têm expediente (Carnaval, Sexta-feira Santa e Corpus Christi).
 * Feriados estaduais, municipais e portarias de cada tribunal são cadastrados pelo escritório.
 */
public final class BrazilianHolidays {

    private static final Map<MonthDay, String> FIXED = Map.of(
            MonthDay.of(1, 1), "Confraternização Universal",
            MonthDay.of(4, 21), "Tiradentes",
            MonthDay.of(5, 1), "Dia do Trabalho",
            MonthDay.of(9, 7), "Independência do Brasil",
            MonthDay.of(10, 12), "Nossa Senhora Aparecida",
            MonthDay.of(11, 2), "Finados",
            MonthDay.of(11, 15), "Proclamação da República",
            MonthDay.of(11, 20), "Dia Nacional de Zumbi e da Consciência Negra",
            MonthDay.of(12, 25), "Natal");

    private BrazilianHolidays() {
    }

    /** Domingo de Páscoa (algoritmo de Meeus/Jones/Butcher, calendário gregoriano). */
    public static LocalDate easter(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }

    /** Feriados e datas sem expediente forense do ano, ordenados. */
    public static Map<LocalDate, String> of(int year) {
        Map<LocalDate, String> result = new TreeMap<>();
        FIXED.forEach((md, name) -> {
            if (md.equals(MonthDay.of(11, 20)) && year < 2024) {
                return; // feriado nacional a partir da Lei 14.759/2023
            }
            result.put(md.atYear(year), name);
        });
        LocalDate easter = easter(year);
        result.put(easter.minusDays(48), "Carnaval (segunda-feira)");
        result.put(easter.minusDays(47), "Carnaval (terça-feira)");
        result.put(easter.minusDays(2), "Sexta-feira Santa");
        result.put(easter.plusDays(60), "Corpus Christi");
        return result;
    }
}
