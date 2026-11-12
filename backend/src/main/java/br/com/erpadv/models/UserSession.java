package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * Sessão de login ({@code offices/{officeId}/user_sessions/{id}}), identificada pelo usuário e pelo instante do login
 * ({@code auth_time} do token do Firebase), que se mantém nas renovações do token.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserSession extends BaseEntity {
    private String uid;
    private String userName;
    private String userEmail;
    private Instant authTime;
    private Instant lastSeenAt;
    private String ip;
    private String userAgent;
    /** Navegador e sistema (ex.: "Chrome · Windows"). */
    private String device;
    private boolean mobile;
    private boolean newDevice;
    private boolean mfaVerified;
    private Instant mfaVerifiedAt;
    private boolean revoked;
    private Instant revokedAt;
    private String revokedByName;
    private String endReason;
}
