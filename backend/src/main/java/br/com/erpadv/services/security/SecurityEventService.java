package br.com.erpadv.services.security;

import br.com.erpadv.audit.RequestInfo;
import br.com.erpadv.models.SecurityEvent;
import br.com.erpadv.repositories.SecurityRepositories;
import br.com.erpadv.security.AuthenticatedUser;
import com.google.cloud.firestore.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Registro de eventos de segurança (logins, dispositivos novos, 2FA, sessões encerradas, acessos negados). */
@Service
public class SecurityEventService {

    private static final Logger SECURITY = LoggerFactory.getLogger("SECURITY");
    private final SecurityRepositories.Events events;

    public SecurityEventService(SecurityRepositories.Events events) {
        this.events = events;
    }

    public void record(String officeId, String type, SecurityEvent.Severity severity, String description, String uid,
                       String userName, Map<String, String> details) {
        SECURITY.info("type={} severity={} office={} uid={} ip={} {}", type, severity, officeId, uid, RequestInfo.ip(), description);
        if (officeId == null) {
            return;
        }
        try {
            SecurityEvent e = new SecurityEvent();
            e.setId(UUID.randomUUID().toString());
            e.setType(type);
            e.setSeverity(severity);
            e.setDescription(description);
            e.setUid(uid);
            e.setUserName(userName);
            e.setIp(RequestInfo.ip());
            e.setUserAgent(RequestInfo.userAgent());
            e.setAt(Instant.now());
            e.setCreatedAt(e.getAt());
            e.setDetails(details == null ? new HashMap<>() : new HashMap<>(details));
            events.save(officeId, e);
        } catch (RuntimeException ex) {
            LoggerFactory.getLogger(SecurityEventService.class).error("Falha ao gravar evento de segurança {}", type, ex);
        }
    }

    public void record(AuthenticatedUser user, String type, SecurityEvent.Severity severity, String description) {
        record(user.officeId(), type, severity, description, user.uid(), user.displayName(), Map.of());
    }

    public List<SecurityEvent> list(String officeId, String uid, SecurityEvent.Severity severity, int max) {
        Query q = events.collection(officeId);
        if (uid != null && !uid.isBlank()) {
            q = q.whereEqualTo("uid", uid);
        } else if (severity != null) {
            q = q.whereEqualTo("severity", severity.name());
        } else {
            q = q.orderBy("at", Query.Direction.DESCENDING);
        }
        return events.list(q, Math.min(Math.max(max, 1), 500)).stream()
                .filter(e -> severity == null || e.getSeverity() == severity)
                .sorted(Comparator.comparing(SecurityEvent::getAt, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
    }
}
