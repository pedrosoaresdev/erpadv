package br.com.erpadv.services.security;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.audit.RequestInfo;
import br.com.erpadv.exceptions.ForbiddenException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.models.SecurityEvent;
import br.com.erpadv.models.SecurityPolicy;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.models.UserSession;
import br.com.erpadv.repositories.SecurityRepositories;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Permission;
import br.com.erpadv.security.Role;
import br.com.erpadv.services.NotificationService;
import com.google.cloud.firestore.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sessões de login controladas pelo servidor: cada login (usuário + {@code auth_time} do token) vira um registro que
 * permite encerramento remoto, expiração por duração máxima e por inatividade, aviso de dispositivo novo e a
 * exigência do segundo fator por sessão.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    public static final String ATTR_SESSION = "erpadv.sessionId";
    public static final String ATTR_AUTH_TIME = "erpadv.authTime";
    private static final long CACHE_MS = 15_000;
    private static final Duration TOUCH_EVERY = Duration.ofSeconds(60);
    /** Rotas liberadas antes da confirmação do segundo fator. */
    private static final List<String> MFA_OPEN = List.of("/api/auth/", "/api/security/session", "/api/security/mfa/");

    public record Decision(HttpStatus status, String code, String message) {
    }

    public record SessionStatus(String sessionId, boolean mfaEnabled, boolean mfaRequired, boolean mfaVerified,
                                boolean enrollmentRequired, int idleMinutes, int sessionMaxHours,
                                boolean requireJustificationOnDelete, Instant authTime) {
    }

    private record Cached(UserSession session, long loadedAt) {
    }

    private final SecurityRepositories.Sessions sessions;
    private final SecurityPolicyService policies;
    private final SecurityEventService events;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public SessionService(SecurityRepositories.Sessions sessions, SecurityPolicyService policies, SecurityEventService events,
                          NotificationService notifications, AuditService audit) {
        this.sessions = sessions;
        this.policies = policies;
        this.events = events;
        this.notifications = notifications;
        this.audit = audit;
    }

    public static String sessionId(String uid, long authTimeSeconds) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((uid + "|" + authTimeSeconds).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sessão da requisição corrente (definida pelo filtro de autenticação). */
    public static String currentSessionId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
            return a.getRequest().getAttribute(ATTR_SESSION) instanceof String s ? s : null;
        }
        return null;
    }

    /**
     * Verifica a sessão a cada requisição autenticada de um integrante. Devolve {@code null} quando liberada ou a
     * recusa a ser enviada ao cliente.
     */
    public Decision check(String officeId, String uid, String name, String email, Role role, Set<Permission> permissions,
                          UserProfile profile, long authTimeSeconds, String path) {
        SecurityPolicy policy = policies.get(officeId);
        String sid = sessionId(uid, authTimeSeconds);
        UserSession s = load(officeId, sid);
        Instant now = Instant.now();
        if (s == null) {
            s = start(officeId, sid, uid, name, email, authTimeSeconds, policy);
        }
        if (s.isRevoked()) {
            return new Decision(HttpStatus.UNAUTHORIZED, "SESSION_REVOKED", "Esta sessão foi encerrada. Entre novamente.");
        }
        if (policy.getSessionMaxHours() > 0 && s.getAuthTime() != null
                && s.getAuthTime().plus(Duration.ofHours(policy.getSessionMaxHours())).isBefore(now)) {
            end(officeId, s, "Duração máxima da sessão atingida", null);
            return new Decision(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED",
                    "Sua sessão expirou (limite de " + policy.getSessionMaxHours() + " h). Entre novamente.");
        }
        if (policy.getIdleMinutes() > 0 && s.getLastSeenAt() != null
                && s.getLastSeenAt().plus(Duration.ofMinutes(policy.getIdleMinutes())).isBefore(now)) {
            end(officeId, s, "Encerrada por inatividade", null);
            return new Decision(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Sessão encerrada por inatividade. Entre novamente.");
        }
        touch(officeId, s, now);
        boolean required = SecurityPolicyService.mfaRequired(policy, role, permissions);
        boolean needMfa = profile.isMfaEnabled() || required;
        if (needMfa && !s.isMfaVerified() && MFA_OPEN.stream().noneMatch(path::startsWith)) {
            if (!profile.isMfaEnabled()) {
                return new Decision(HttpStatus.FORBIDDEN, "MFA_ENROLLMENT_REQUIRED",
                        "O escritório exige a autenticação em dois fatores. Configure o aplicativo autenticador para continuar.");
            }
            return new Decision(HttpStatus.FORBIDDEN, "MFA_REQUIRED", "Confirme o código do aplicativo autenticador para continuar.");
        }
        return null;
    }

    public SessionStatus status(AuthenticatedUser user, UserProfile profile) {
        SecurityPolicy policy = policies.get(user.requireOffice());
        String sid = currentSessionId();
        UserSession s = sid == null ? null : load(user.officeId(), sid);
        boolean required = SecurityPolicyService.mfaRequired(policy, user.role(), user.permissions());
        return new SessionStatus(sid, profile.isMfaEnabled(), required || profile.isMfaEnabled(), s != null && s.isMfaVerified(),
                required && !profile.isMfaEnabled(), policy.getIdleMinutes(), policy.getSessionMaxHours(),
                policy.isRequireJustificationOnDelete(), s == null ? null : s.getAuthTime());
    }

    public void markMfaVerified(String officeId, String sid) {
        UserSession s = load(officeId, sid);
        if (s == null) {
            return;
        }
        s.setMfaVerified(true);
        s.setMfaVerifiedAt(Instant.now());
        sessions.save(officeId, s);
        cache.put(officeId + "/" + sid, new Cached(s, System.currentTimeMillis()));
    }

    // ------------------------------------------------------------------ consultas e encerramento

    public List<UserSession> mine(AuthenticatedUser user) {
        return active(user.requireOffice(), sessions.collection(user.officeId()).whereEqualTo("uid", user.uid()));
    }

    public List<UserSession> office(AuthenticatedUser user) {
        return active(user.requireOffice(), sessions.collection(user.officeId()).orderBy("lastSeenAt", Query.Direction.DESCENDING));
    }

    private List<UserSession> active(String officeId, Query q) {
        SecurityPolicy p = policies.get(officeId);
        Instant now = Instant.now();
        return sessions.list(q, 300).stream()
                .filter(s -> !s.isRevoked())
                .filter(s -> p.getSessionMaxHours() == 0 || s.getAuthTime() == null
                        || s.getAuthTime().plus(Duration.ofHours(p.getSessionMaxHours())).isAfter(now))
                .filter(s -> p.getIdleMinutes() == 0 || s.getLastSeenAt() == null
                        || s.getLastSeenAt().plus(Duration.ofMinutes(p.getIdleMinutes())).isAfter(now))
                // tokens do Firebase vivem 1 h; sem uso há 30 dias a sessão já não é renovada pelo navegador
                .filter(s -> s.getLastSeenAt() == null || s.getLastSeenAt().isAfter(now.minus(Duration.ofDays(30))))
                .sorted(Comparator.comparing(UserSession::getLastSeenAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /** Encerra uma sessão: a própria (qualquer usuário) ou de outro integrante (administração). */
    public void revoke(AuthenticatedUser user, String sid, boolean asAdmin) {
        String officeId = user.requireOffice();
        UserSession s = sessions.findById(officeId, sid).orElseThrow(() -> new NotFoundException("Sessão"));
        if (!asAdmin && !s.getUid().equals(user.uid())) {
            throw new ForbiddenException("ACCESS_DENIED", "Você só pode encerrar as próprias sessões.");
        }
        end(officeId, s, s.getUid().equals(user.uid()) ? "Encerrada pelo próprio usuário" : "Encerrada pelo administrador", user.displayName());
        boolean other = !s.getUid().equals(user.uid());
        events.record(officeId, "SESSION_REVOKED", other ? SecurityEvent.Severity.ATENCAO : SecurityEvent.Severity.INFO,
                "Sessão de " + s.getUserName() + " (" + s.getDevice() + ") encerrada por " + user.displayName(), s.getUid(), s.getUserName(),
                Map.of("sessionId", sid, "by", user.uid()));
        audit.record(user, "SESSION_REVOKED", "session", sid, "Sessão encerrada: " + s.getUserName() + " · " + s.getDevice());
        if (other) {
            notifications.notify(officeId, s.getUid(), "SECURITY", "Sessão encerrada pelo administrador",
                    "Seu acesso em " + s.getDevice() + " foi encerrado por " + user.displayName() + ".", "/seguranca");
        }
    }

    /** Encerra todas as outras sessões do usuário (mantém a atual). */
    public int revokeOthers(AuthenticatedUser user) {
        String current = currentSessionId();
        int n = 0;
        for (UserSession s : mine(user)) {
            if (!s.getId().equals(current)) {
                end(user.officeId(), s, "Encerrada pelo próprio usuário (outras sessões)", user.displayName());
                n++;
            }
        }
        if (n > 0) {
            events.record(user, "SESSIONS_REVOKED", SecurityEvent.Severity.INFO, n + " outra(s) sessão(ões) encerrada(s) por " + user.displayName());
            audit.record(user, "SESSIONS_REVOKED", "session", null, n + " sessão(ões) encerrada(s)");
        }
        return n;
    }

    // ------------------------------------------------------------------ internos

    private UserSession load(String officeId, String sid) {
        String key = officeId + "/" + sid;
        Cached c = cache.get(key);
        long now = System.currentTimeMillis();
        if (c != null && now - c.loadedAt() < CACHE_MS) {
            return c.session();
        }
        UserSession s = sessions.findById(officeId, sid).orElse(null);
        if (s != null) {
            cache.put(key, new Cached(s, now));
        }
        if (cache.size() > 20_000) {
            cache.entrySet().removeIf(e -> now - e.getValue().loadedAt() > CACHE_MS);
        }
        return s;
    }

    private UserSession start(String officeId, String sid, String uid, String name, String email, long authTimeSeconds,
                              SecurityPolicy policy) {
        Instant now = Instant.now();
        String ua = RequestInfo.userAgent();
        String device = device(ua);
        List<UserSession> previous = sessions.list(sessions.collection(officeId).whereEqualTo("uid", uid), 200);
        boolean known = previous.stream().anyMatch(p -> device.equals(p.getDevice()));
        UserSession s = new UserSession();
        s.setId(sid);
        s.setUid(uid);
        s.setUserName(name);
        s.setUserEmail(email);
        s.setAuthTime(Instant.ofEpochSecond(authTimeSeconds));
        s.setLastSeenAt(now);
        s.setCreatedAt(now);
        s.setIp(RequestInfo.ip());
        s.setUserAgent(ua);
        s.setDevice(device);
        s.setMobile(ua != null && ua.toLowerCase(Locale.ROOT).matches(".*(mobile|android|iphone|ipad).*"));
        s.setNewDevice(!previous.isEmpty() && !known);
        sessions.save(officeId, s);
        cache.put(officeId + "/" + sid, new Cached(s, System.currentTimeMillis()));
        if (s.isNewDevice()) {
            events.record(officeId, "NEW_DEVICE", SecurityEvent.Severity.ATENCAO, "Acesso de " + name + " por um dispositivo novo: " + device,
                    uid, name, Map.of("sessionId", sid));
            if (policy.isNewDeviceAlert()) {
                notifications.notify(officeId, uid, "SECURITY", "Novo acesso à sua conta",
                        "Entrada por " + device + (s.getIp() == null ? "" : " (IP " + s.getIp() + ")")
                                + ". Se não foi você, encerre a sessão em Segurança e troque a senha.", "/seguranca");
            }
        } else {
            events.record(officeId, "SESSION_STARTED", SecurityEvent.Severity.INFO, "Login de " + name + " · " + device, uid, name,
                    Map.of("sessionId", sid));
        }
        return s;
    }

    private void touch(String officeId, UserSession s, Instant now) {
        if (s.getLastSeenAt() != null && s.getLastSeenAt().plus(TOUCH_EVERY).isAfter(now)) {
            return;
        }
        s.setLastSeenAt(now);
        try {
            sessions.collection(officeId).document(s.getId()).update("lastSeenAt", now.toEpochMilli());
        } catch (RuntimeException e) {
            log.warn("Não foi possível atualizar a última atividade da sessão {}", s.getId(), e);
        }
    }

    private void end(String officeId, UserSession s, String reason, String byName) {
        if (s.isRevoked()) {
            return;
        }
        s.setRevoked(true);
        s.setRevokedAt(Instant.now());
        s.setRevokedByName(byName);
        s.setEndReason(reason);
        sessions.save(officeId, s);
        cache.put(officeId + "/" + s.getId(), new Cached(s, System.currentTimeMillis()));
    }

    /** "Chrome · Windows", "Safari · iPhone"… a partir do user-agent. */
    public static String device(String ua) {
        if (ua == null || ua.isBlank()) {
            return "Dispositivo desconhecido";
        }
        String browser = ua.contains("Edg/") ? "Edge" : ua.contains("OPR/") || ua.contains("Opera") ? "Opera"
                : ua.contains("Firefox/") ? "Firefox" : ua.contains("Chrome/") || ua.contains("CriOS/") ? "Chrome"
                : ua.contains("Safari/") ? "Safari" : "Navegador";
        String os = ua.contains("iPhone") ? "iPhone" : ua.contains("iPad") ? "iPad" : ua.contains("Android") ? "Android"
                : ua.contains("Windows") ? "Windows" : ua.contains("Mac OS X") || ua.contains("Macintosh") ? "macOS"
                : ua.contains("Linux") ? "Linux" : "outro sistema";
        return browser + " · " + os;
    }
}
