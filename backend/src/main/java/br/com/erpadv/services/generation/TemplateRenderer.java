package br.com.erpadv.services.generation;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitui campos dinâmicos {@code {{grupo.campo}}} pelos valores informados. Campos sem valor ficam
 * visíveis como {@code [grupo.campo]} e são devolvidos como pendências para revisão humana.
 */
public final class TemplateRenderer {

    private static final Pattern FIELD = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.]+)\\s*}}");

    public record Rendered(String text, Set<String> missing) {
    }

    private TemplateRenderer() {
    }

    public static Rendered render(String template, Map<String, String> values) {
        Set<String> missing = new LinkedHashSet<>();
        Matcher m = FIELD.matcher(template == null ? "" : template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                missing.add(key);
                value = "[" + key + "]";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return new Rendered(sb.toString(), missing);
    }

    public static Set<String> fields(String template) {
        Set<String> fields = new LinkedHashSet<>();
        Matcher m = FIELD.matcher(template == null ? "" : template);
        while (m.find()) {
            fields.add(m.group(1));
        }
        return fields;
    }
}
