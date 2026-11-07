package br.com.erpadv.services.generation;

import java.util.ArrayList;
import java.util.List;

/** Valor monetário por extenso em português do Brasil (ex.: "quatro mil e quinhentos reais"). */
public final class ValorExtenso {

    private static final String[] UNITS = {"zero", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito",
            "nove", "dez", "onze", "doze", "treze", "quatorze", "quinze", "dezesseis", "dezessete", "dezoito", "dezenove"};
    private static final String[] TENS = {"", "", "vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta",
            "oitenta", "noventa"};
    private static final String[] HUNDREDS = {"", "cento", "duzentos", "trezentos", "quatrocentos", "quinhentos",
            "seiscentos", "setecentos", "oitocentos", "novecentos"};
    private static final String[][] SCALES = {{"bilhão", "bilhões"}, {"milhão", "milhões"}, {"mil", "mil"}, {"", ""}};

    private ValorExtenso() {
    }

    public static String reais(long cents) {
        if (cents < 0) {
            return "menos " + reais(-cents);
        }
        long reais = cents / 100;
        long centavos = cents % 100;
        String r = reais == 0 ? "" : reais == 1 ? "um real"
                : inteiro(reais) + (reais >= 1_000_000 && reais % 1_000_000 == 0 ? " de reais" : " reais");
        String c = centavos == 0 ? "" : centavos == 1 ? "um centavo" : inteiro(centavos) + " centavos";
        if (r.isEmpty() && c.isEmpty()) {
            return "zero real";
        }
        if (r.isEmpty()) {
            return c;
        }
        return c.isEmpty() ? r : r + " e " + c;
    }

    public static String inteiro(long n) {
        if (n == 0) {
            return UNITS[0];
        }
        if (n >= 1_000_000_000_000L) {
            throw new IllegalArgumentException("Valor fora do intervalo suportado");
        }
        long[] groups = {n / 1_000_000_000L, (n / 1_000_000L) % 1000, (n / 1000) % 1000, n % 1000};
        List<String> parts = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int g = (int) groups[i];
            if (g == 0) {
                continue;
            }
            String text;
            if (i == 2 && g == 1) {
                text = "mil";
            } else {
                text = trio(g) + (SCALES[i][0].isEmpty() ? "" : " " + (g == 1 ? SCALES[i][0] : SCALES[i][1]));
            }
            parts.add(text);
            idx.add(i);
        }
        StringBuilder sb = new StringBuilder(parts.get(0));
        for (int k = 1; k < parts.size(); k++) {
            int g = (int) groups[idx.get(k)];
            boolean last = k == parts.size() - 1;
            if (last && (g < 100 || g % 100 == 0)) {
                sb.append(" e ");
            } else if (idx.get(k - 1) == 2) {
                sb.append(' ');
            } else {
                sb.append(", ");
            }
            sb.append(parts.get(k));
        }
        return sb.toString();
    }

    private static String trio(int n) {
        if (n == 100) {
            return "cem";
        }
        int h = n / 100;
        int r = n % 100;
        StringBuilder sb = new StringBuilder();
        if (h > 0) {
            sb.append(HUNDREDS[h]);
        }
        if (r > 0) {
            if (h > 0) {
                sb.append(" e ");
            }
            sb.append(r < 20 ? UNITS[r] : TENS[r / 10] + (r % 10 > 0 ? " e " + UNITS[r % 10] : ""));
        }
        return sb.toString();
    }
}
