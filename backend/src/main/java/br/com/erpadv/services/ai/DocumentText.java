package br.com.erpadv.services.ai;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** Extração de texto de documentos da central (PDF, DOCX e texto) e mascaramento de CPF/CNPJ. */
public final class DocumentText {

    public static final int MAX_CHARS = 150_000;
    private static final Pattern CPF = Pattern.compile("\\b(\\d{3})[.\\s]?(\\d{3})[.\\s]?(\\d{3})[-\\s]?(\\d{2})\\b");
    private static final Pattern CNPJ = Pattern.compile("\\b(\\d{2})[.\\s]?(\\d{3})[.\\s]?(\\d{3})[/\\s]?(\\d{4})[-\\s]?(\\d{2})\\b");

    private DocumentText() {
    }

    /** Texto do arquivo; vazio quando não há camada de texto (PDF digitalizado) ou o formato não é suportado. */
    public static String extract(byte[] bytes, String contentType, String fileName) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        try {
            if (ct.contains("pdf") || name.endsWith(".pdf")) {
                try (PDDocument doc = Loader.loadPDF(bytes)) {
                    return limit(new PDFTextStripper().getText(doc));
                }
            }
            if (ct.contains("wordprocessingml") || name.endsWith(".docx")) {
                try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes)); XWPFWordExtractor ex = new XWPFWordExtractor(doc)) {
                    return limit(ex.getText());
                }
            }
            if (ct.startsWith("text/") || name.endsWith(".txt")) {
                return limit(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            return "";
        }
        return "";
    }

    public static boolean isPdf(String contentType, String fileName) {
        return (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("pdf"))
                || (fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".pdf"));
    }

    private static String limit(String s) {
        String t = s == null ? "" : s.replace("\r", "").replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
        return t.length() > MAX_CHARS ? t.substring(0, MAX_CHARS) + "\n[… documento truncado no limite de " + MAX_CHARS + " caracteres]" : t;
    }

    /** Mascara CPF e CNPJ com asteriscos, mantendo apenas os dois dígitos verificadores para referência. */
    public static String pseudonymize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String t = CNPJ.matcher(text).replaceAll("**.***.***/****-$5");
        return CPF.matcher(t).replaceAll("***.***.***-$4");
    }
}
