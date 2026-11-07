package br.com.erpadv.firebase;

import br.com.erpadv.exceptions.BusinessException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Set;

/**
 * Validação de arquivos enviados: o tipo é determinado pela assinatura binária (magic bytes),
 * nunca pela extensão ou pelo Content-Type informados pelo cliente.
 */
public final class FileInspector {

    public enum FileKind {
        PDF("application/pdf", "pdf"),
        PNG("image/png", "png"),
        JPEG("image/jpeg", "jpg"),
        DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");

        public final String contentType;
        public final String extension;

        FileKind(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }
    }

    public record Inspection(FileKind kind, String safeName, String sha256, long size) {
    }

    private FileInspector() {
    }

    public static Inspection inspect(byte[] bytes, String originalName, long maxBytes, Set<FileKind> allowed) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException("EMPTY_FILE", "Arquivo vazio.");
        }
        if (bytes.length > maxBytes) {
            throw new BusinessException("FILE_TOO_LARGE", "Arquivo excede o limite de " + (maxBytes / (1024 * 1024)) + " MB.");
        }
        FileKind kind = detect(bytes);
        if (kind == null || !allowed.contains(kind)) {
            throw new BusinessException("FILE_TYPE_NOT_ALLOWED", "Tipo de arquivo não permitido. Envie " + describe(allowed) + ".");
        }
        return new Inspection(kind, safeName(originalName, kind), sha256(bytes), bytes.length);
    }

    static FileKind detect(byte[] b) {
        if (startsWith(b, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
            return FileKind.PDF;
        }
        if (startsWith(b, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})) {
            return FileKind.PNG;
        }
        if (startsWith(b, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return FileKind.JPEG;
        }
        // DOCX é um ZIP (PK\3\4) contendo "word/" — verificação leve do diretório interno.
        if (startsWith(b, new byte[]{'P', 'K', 3, 4}) && new String(b, 0, Math.min(b.length, 4096), StandardCharsets.ISO_8859_1).contains("word/")) {
            return FileKind.DOCX;
        }
        return null;
    }

    /** Nome sem caminho, sem acentos e com extensão coerente com o conteúdo. */
    public static String safeName(String original, FileKind kind) {
        String base = original == null ? "arquivo" : original.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        base = Normalizer.normalize(base, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("_+", "_").replaceAll("^[._]+", "");
        if (base.isBlank()) {
            base = "arquivo";
        }
        if (base.length() > 80) {
            base = base.substring(0, 80);
        }
        return base + "." + kind.extension;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String describe(Set<FileKind> allowed) {
        return String.join(", ", allowed.stream().map(k -> k.extension.toUpperCase()).sorted().toList());
    }
}
