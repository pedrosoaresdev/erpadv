package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Evento de segurança ({@code offices/{officeId}/security_events}). */
@Data
@EqualsAndHashCode(callSuper = true)
public class SecurityEvent extends BaseEntity {

    public enum Severity { INFO, ATENCAO, CRITICO }

    private String type;
    private Severity severity = Severity.INFO;
    private String description;
    private String uid;
    private String userName;
    private String ip;
    private String userAgent;
    private Instant at;
    private Map<String, String> details = new HashMap<>();
}
