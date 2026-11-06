package br.com.erpadv.audit;

import lombok.Data;

import java.time.Instant;
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
}
