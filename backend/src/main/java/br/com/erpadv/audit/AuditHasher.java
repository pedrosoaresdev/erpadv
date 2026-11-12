package br.com.erpadv.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Encadeamento criptográfico da trilha de auditoria: {@code hash = SHA-256(prevHash + "\n" + canônico)}.
 * Qualquer alteração, remoção ou inserção fora de ordem quebra a cadeia.
 */
public final class AuditHasher {

    public static final String GENESIS = "0".repeat(64);

    private AuditHasher() {
    }

    public static String canonical(AuditLog log) {
        StringBuilder sb = new StringBuilder();
        append(sb, String.valueOf(log.getSeq()));
        append(sb, log.getAt() == null ? "" : String.valueOf(log.getAt().toEpochMilli()));
        append(sb, log.getOfficeId());
        append(sb, log.getActorUid());
        append(sb, log.getActorEmail());
        append(sb, log.getAction());
        append(sb, log.getEntityType());
        append(sb, log.getEntityId());
        append(sb, log.getSummary());
        append(sb, log.getIp());
        Map<String, String> details = log.getDetails() == null ? Map.of() : new TreeMap<>(log.getDetails());
        details.forEach((k, v) -> append(sb, k + "=" + (v == null ? "" : v)));
        // Campos da auditoria avançada entram no hash somente quando presentes: registros anteriores
        // à Fase 5 mantêm exatamente o mesmo texto canônico e continuam verificáveis.
        boolean v2 = notBlank(log.getActorName()) || notBlank(log.getModule()) || notBlank(log.getOrigin())
                || notBlank(log.getJustification()) || (log.getChanges() != null && !log.getChanges().isEmpty());
        if (v2) {
            append(sb, "v2");
            append(sb, log.getActorName());
            append(sb, log.getModule());
            append(sb, log.getOrigin());
            append(sb, log.getJustification());
            if (log.getChanges() != null) {
                for (AuditChange c : log.getChanges()) {
                    append(sb, c.getField());
                    append(sb, c.getBefore());
                    append(sb, c.getAfter());
                }
            }
        }
        return sb.toString();
    }

    public static String hash(String prevHash, AuditLog log) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((prevHash == null ? GENESIS : prevHash).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(canonical(log).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static void append(StringBuilder sb, String value) {
        // Escapa o separador para que campos diferentes não produzam o mesmo texto canônico.
        String v = value == null ? "" : value.replace("\\", "\\\\").replace("|", "\\|");
        sb.append(v).append('|');
    }
}
