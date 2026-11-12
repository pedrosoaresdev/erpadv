package br.com.erpadv.audit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compara dois retratos (mapas no formato gravado no Firestore) e devolve os campos alterados com o valor anterior e o
 * posterior. Campos técnicos são ignorados e campos cifrados ({@code *Enc}) ou de hash nunca têm o valor exposto.
 */
public final class AuditDiff {

    private static final int MAX_VALUE = 300;
    private static final int MAX_CHANGES = 60;
    private static final Set<String> IGNORED = Set.of("id", "officeId", "createdAt", "createdBy", "updatedAt", "updatedBy",
            "searchTokens", "nameLower", "titleLower", "sortKey", "alertsSent", "remindersSent", "lastActivityAt",
            "statusChangedAt", "expiryAlertsSent", "commentCount", "columnEnteredAt", "minutesUpdatedAt");

    private AuditDiff() {
    }

    public static List<AuditChange> diff(Map<String, Object> before, Map<String, Object> after) {
        List<AuditChange> out = new ArrayList<>();
        if (before == null || after == null) {
            return out;
        }
        Set<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String k : keys) {
            if (IGNORED.contains(k)) {
                continue;
            }
            Object a = normalize(before.get(k));
            Object b = normalize(after.get(k));
            if (Objects.equals(a, b)) {
                continue;
            }
            if (k.endsWith("Enc") || k.endsWith("Hash") || k.endsWith("Sha256") || k.equals("sha256")) {
                out.add(new AuditChange(k, a == null ? "" : "(protegido)", b == null ? "" : "(protegido — alterado)"));
            } else {
                out.add(new AuditChange(k, text(a), text(b)));
            }
            if (out.size() >= MAX_CHANGES) {
                break;
            }
        }
        return out;
    }

    /** Vazio, nulo e coleção vazia são equivalentes (evita "alterações" que não mudam nada). */
    private static Object normalize(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof String s && s.isBlank()) {
            return null;
        }
        if (v instanceof Collection<?> c && c.isEmpty()) {
            return null;
        }
        if (v instanceof Map<?, ?> m && m.isEmpty()) {
            return null;
        }
        if (v instanceof Number n && !(v instanceof Double) && !(v instanceof Float)) {
            return n.longValue();
        }
        return v;
    }

    static String text(Object v) {
        if (v == null) {
            return "";
        }
        String s = v instanceof Collection<?> || v instanceof Map<?, ?> ? String.valueOf(v) : v.toString();
        return s.length() > MAX_VALUE ? s.substring(0, MAX_VALUE) + "…" : s;
    }

    /** Módulo exibido na auditoria a partir do tipo de registro. */
    public static String module(String entityType) {
        if (entityType == null) {
            return "Sistema";
        }
        return switch (entityType) {
            case "client" -> "Clientes";
            case "case", "court_calendar" -> "Processos";
            case "deadline" -> "Prazos";
            case "appointment" -> "Agenda";
            case "finance", "cost_center", "bank_account", "commission_rule", "commission_payout", "collection" -> "Financeiro";
            case "contract", "contract_template" -> "Contratos";
            case "document", "folder" -> "Documentos";
            case "legal_document", "legal_template", "letterhead" -> "Editor Jurídico";
            case "lead", "proposal" -> "CRM";
            case "board", "card" -> "Quadros";
            case "user", "role" -> "Equipe e permissões";
            case "office", "security", "session", "backup" -> "Segurança";
            case "integration", "publication" -> "Integrações";
            case "communication", "message_template" -> "Comunicação";
            case "report" -> "Relatórios";
            case "audit" -> "Auditoria";
            case "lgpd_request", "consent", "processing_activity", "incident", "privacy_policy", "data_share", "retention" -> "LGPD";
            case "ai" -> "IA Jurídica";
            case "automation" -> "Automação";
            default -> "Sistema";
        };
    }
}
