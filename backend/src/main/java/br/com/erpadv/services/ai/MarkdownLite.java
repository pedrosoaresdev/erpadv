package br.com.erpadv.services.ai;

import br.com.erpadv.services.generation.HtmlDocument;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conversão mínima e segura de Markdown (títulos, listas, negrito, itálico) para o HTML do Editor Jurídico. */
public final class MarkdownLite {

    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC = Pattern.compile("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])");

    private MarkdownLite() {
    }

    public static String toHtml(String md) {
        if (md == null || md.isBlank()) {
            return "<p><br></p>";
        }
        StringBuilder out = new StringBuilder();
        String list = null;
        StringBuilder para = new StringBuilder();
        for (String raw : md.replace("\r", "").split("\n")) {
            String line = raw.strip();
            String tag = null;
            String item = null;
            if (line.startsWith("### ")) {
                tag = "h3";
            } else if (line.startsWith("## ")) {
                tag = "h2";
            } else if (line.startsWith("# ")) {
                tag = "h1";
            }
            if (line.matches("^[-*•]\\s+.+")) {
                item = "ul";
            } else if (line.matches("^\\d+[.)]\\s+.+")) {
                item = "ol";
            }
            if (tag != null || item != null || line.isEmpty()) {
                flush(out, para);
            }
            if (item == null && list != null) {
                out.append("</").append(list).append('>');
                list = null;
            }
            if (tag != null) {
                out.append('<').append(tag).append('>').append(inline(line.substring(line.indexOf(' ') + 1))).append("</").append(tag).append('>');
            } else if (item != null) {
                if (!item.equals(list)) {
                    if (list != null) {
                        out.append("</").append(list).append('>');
                    }
                    out.append('<').append(item).append('>');
                    list = item;
                }
                out.append("<li>").append(inline(line.replaceFirst("^([-*•]|\\d+[.)])\\s+", ""))).append("</li>");
            } else if (!line.isEmpty() && !line.matches("^-{3,}$")) {
                if (para.length() > 0) {
                    para.append("<br>");
                }
                para.append(inline(line));
            }
        }
        flush(out, para);
        if (list != null) {
            out.append("</").append(list).append('>');
        }
        return out.toString();
    }

    private static void flush(StringBuilder out, StringBuilder para) {
        if (para.length() > 0) {
            out.append("<p>").append(para).append("</p>");
            para.setLength(0);
        }
    }

    private static String inline(String s) {
        String e = HtmlDocument.escape(s);
        Matcher m = BOLD.matcher(e);
        e = m.replaceAll("<strong>$1</strong>");
        return ITALIC.matcher(e).replaceAll("<em>$1</em>");
    }
}
