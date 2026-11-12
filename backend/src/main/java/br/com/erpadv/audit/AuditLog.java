package br.com.erpadv.audit;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Registro imutável de {@code offices/{officeId}/audit_logs/{seq}}. */
@Data
public class AuditLog {
    private String id;
    private String officeId;
    private long seq;
    private Instant at;
    private String actorUid;
    private String actorEmail;
    private String action;
    private String entityType;
    private String entityId;
    private String summary;
    private Map<String, String> details = new TreeMap<>();
    private String ip;
    private String userAgent;
    private String prevHash;
    private String hash;
    // ---- auditoria avançada (Fase 5); ausentes nos registros antigos, que continuam íntegros
    private String actorName;
    /** Módulo do sistema (Clientes, Processos, Financeiro…), derivado do tipo de registro. */
    private String module;
    /** Origem da operação: WEB, CELULAR, WEBHOOK, SISTEMA (rotina automática) ou API. */
    private String origin;
    /** Justificativa informada pelo usuário, quando exigida pela política do escritório. */
    private String justification;
    /** Campos alterados com o valor anterior e o posterior (dados cifrados nunca aparecem). */
    private List<AuditChange> changes = new ArrayList<>();
}
