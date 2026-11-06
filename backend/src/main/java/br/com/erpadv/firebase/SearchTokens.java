package br.com.erpadv.firebase;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Geração de tokens de busca para consultas {@code array-contains} no Firestore (que não possui
 * busca textual). Nomes viram prefixos por palavra; telefones viram sufixos; documentos (CPF/CNPJ)
 * entram apenas como HMAC, calculado pelo {@link CryptoService}.
 */
public final class SearchTokens {

    public static final int MIN_PREFIX = 2;
    public static final int MAX_PREFIX = 15;

    private SearchTokens() {
    }

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return decomposed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    public static String digits(String text) {
        return text == null ? "" : text.replaceAll("\\D", "");
    }

    /** Prefixos (2..15 caracteres) de cada palavra do texto. */
    public static Set<String> forText(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String word : normalize(text).split(" ")) {
            if (word.length() < MIN_PREFIX) {
                continue;
            }
            int max = Math.min(word.length(), MAX_PREFIX);
            for (int i = MIN_PREFIX; i <= max; i++) {
                tokens.add(word.substring(0, i));
            }
        }
        return tokens;
    }

    /** Telefone completo e sufixos de 8 dígitos em diante (permite buscar sem DDD). */
    public static Set<String> forPhone(String phone) {
        Set<String> tokens = new LinkedHashSet<>();
        String d = digits(phone);
        if (d.length() >= 8) {
            for (int len = 8; len <= d.length(); len++) {
                tokens.add("p:" + d.substring(d.length() - len));
            }
        }
        return tokens;
    }

    /** Token único usado na consulta: a palavra mais longa do termo (até 15 caracteres). */
    public static String queryToken(String term) {
        String normalized = normalize(term);
        if (normalized.isEmpty()) {
            return null;
        }
        String longest = Arrays.stream(normalized.split(" "))
                .max(Comparator.comparingInt(String::length))
                .orElse(normalized);
        if (longest.length() < MIN_PREFIX) {
            return null;
        }
        return longest.substring(0, Math.min(longest.length(), MAX_PREFIX));
    }
}
