package br.com.erpadv.services.security;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.models.SecurityEvent;
import br.com.erpadv.models.SecurityPolicy;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.repositories.SecurityRepositories;
import br.com.erpadv.repositories.UserRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Permission;
import br.com.erpadv.security.Role;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Política de segurança do escritório, com cache curto (lida a cada requisição autenticada). */
@Service
public class SecurityPolicyService {

    public static final String DOC_ID = "policy";
    private static final long TTL_MS = 20_000;

    private record Entry(SecurityPolicy policy, long expiresAt) {
    }

    public record PolicyRequest(boolean requireJustificationOnDelete, SecurityPolicy.MfaRequirement mfaRequirement,
                                int sessionMaxHours, int idleMinutes, boolean newDeviceAlert, boolean backupEnabled,
                                int backupRetentionDays) {
    }

    private final SecurityRepositories.Policies policies;
    private final UserRepository users;
    private final AuditService audit;
    private final SecurityEventService events;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public SecurityPolicyService(SecurityRepositories.Policies policies, UserRepository users, AuditService audit,
                                 SecurityEventService events) {
        this.policies = policies;
        this.users = users;
        this.audit = audit;
        this.events = events;
    }

    public SecurityPolicy get(String officeId) {
        long now = System.currentTimeMillis();
        Entry e = cache.get(officeId);
        if (e != null && e.expiresAt() > now) {
            return e.policy();
        }
        SecurityPolicy p = policies.findById(officeId, DOC_ID).orElseGet(SecurityPolicy::new);
        cache.put(officeId, new Entry(p, now + TTL_MS));
        return p;
    }

    public void evict(String officeId) {
        cache.remove(officeId);
    }

    public SecurityPolicy save(AuthenticatedUser user, PolicyRequest req) {
        String officeId = user.requireOffice();
        if (req.sessionMaxHours() < 0 || req.sessionMaxHours() > 720) {
            throw new BusinessException("INVALID_POLICY", "Duração máxima da sessão: de 1 a 720 horas (ou 0 para sem limite).");
        }
        if (req.idleMinutes() != 0 && (req.idleMinutes() < 5 || req.idleMinutes() > 1440)) {
            throw new BusinessException("INVALID_POLICY", "Inatividade: de 5 a 1440 minutos (ou 0 para desativar).");
        }
        if (req.backupRetentionDays() < 7 || req.backupRetentionDays() > 365) {
            throw new BusinessException("INVALID_POLICY", "Retenção dos backups: de 7 a 365 dias.");
        }
        SecurityPolicy.MfaRequirement mfa = req.mfaRequirement() == null ? SecurityPolicy.MfaRequirement.NENHUM : req.mfaRequirement();
        if (mfa != SecurityPolicy.MfaRequirement.NENHUM) {
            // Evita que o administrador se tranque do lado de fora: ele precisa ter o 2FA ativo antes de exigi-lo.
            UserProfile me = users.findById(user.uid()).orElse(null);
            if (me == null || !me.isMfaEnabled()) {
                throw new BusinessException("MFA_SELF_FIRST", "Ative a autenticação em dois fatores na sua conta antes de exigi-la da equipe.");
            }
        }
        SecurityPolicy p = policies.findById(officeId, DOC_ID).orElseGet(SecurityPolicy::new);
        Map<String, Object> before = audit.snapshot(p);
        p.setId(DOC_ID);
        p.setRequireJustificationOnDelete(req.requireJustificationOnDelete());
        p.setMfaRequirement(mfa);
        p.setSessionMaxHours(req.sessionMaxHours());
        p.setIdleMinutes(req.idleMinutes());
        p.setNewDeviceAlert(req.newDeviceAlert());
        p.setBackupEnabled(req.backupEnabled());
        p.setBackupRetentionDays(req.backupRetentionDays());
        p.setUpdatedAt(Instant.now());
        p.setUpdatedBy(user.uid());
        p.setUpdatedByName(user.displayName());
        if (p.getCreatedAt() == null) {
            p.setCreatedAt(p.getUpdatedAt());
        }
        policies.save(officeId, p);
        evict(officeId);
        audit.recordChange(user, "SECURITY_POLICY_UPDATED", "security", DOC_ID, "Política de segurança atualizada", Map.of(), before, p);
        events.record(user, "POLICY_CHANGED", SecurityEvent.Severity.ATENCAO, "Política de segurança alterada por " + user.displayName());
        return p;
    }

    /** 2FA exigido para este usuário pela política (além de quem ativou por conta própria). */
    public static boolean mfaRequired(SecurityPolicy p, Role role, Set<Permission> permissions) {
        if (role == null || !role.isStaff() || p == null) {
            return false;
        }
        return switch (p.getMfaRequirement() == null ? SecurityPolicy.MfaRequirement.NENHUM : p.getMfaRequirement()) {
            case NENHUM -> false;
            case EQUIPE -> true;
            case ADMINISTRADORES -> role == Role.SOCIO_ADMIN || permissions.contains(Permission.SETTINGS_MANAGE)
                    || permissions.contains(Permission.EMPLOYEES_MANAGE);
        };
    }
}
