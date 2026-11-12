package br.com.erpadv.services.security;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.ForbiddenException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.firebase.CryptoService;
import br.com.erpadv.models.SecurityEvent;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.repositories.UserRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.UserProfileCache;
import br.com.erpadv.services.NotificationService;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Autenticação em dois fatores por aplicativo autenticador (TOTP). O segredo fica cifrado no perfil; a confirmação vale
 * para a sessão de login corrente. Cinco códigos errados bloqueiam novas tentativas por 15 minutos.
 */
@Service
public class MfaService {

    private static final String ISSUER = "ERPAdv";
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration LOCK = Duration.ofMinutes(15);
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Setup(String secret, String otpauthUri, String qrSvg) {
    }

    public record Enabled(List<String> recoveryCodes) {
    }

    private final UserRepository users;
    private final UserProfileCache profileCache;
    private final CryptoService crypto;
    private final SessionService sessions;
    private final SecurityPolicyService policies;
    private final SecurityEventService events;
    private final NotificationService notifications;
    private final AuditService audit;

    public MfaService(UserRepository users, UserProfileCache profileCache, CryptoService crypto, SessionService sessions,
                      SecurityPolicyService policies, SecurityEventService events, NotificationService notifications, AuditService audit) {
        this.users = users;
        this.profileCache = profileCache;
        this.crypto = crypto;
        this.sessions = sessions;
        this.policies = policies;
        this.events = events;
        this.notifications = notifications;
        this.audit = audit;
    }

    private UserProfile me(AuthenticatedUser user) {
        user.requireOffice();
        return users.findById(user.uid()).orElseThrow(() -> new NotFoundException("Perfil"));
    }

    private void save(UserProfile p) {
        p.setUpdatedAt(Instant.now());
        users.save(p);
        profileCache.evict(p.getId());
    }

    /** Gera um novo segredo pendente e o QR code para o aplicativo autenticador. */
    public Setup setup(AuthenticatedUser user) {
        UserProfile p = me(user);
        if (p.isMfaEnabled()) {
            throw new BusinessException("MFA_ALREADY_ENABLED", "A autenticação em dois fatores já está ativa. Desative-a antes de configurar outro aparelho.");
        }
        String secret = Totp.newSecret();
        p.setMfaPendingSecretEnc(crypto.encrypt(secret));
        save(p);
        String uri = Totp.uri(ISSUER, p.getEmail() == null ? user.email() : p.getEmail(), secret);
        return new Setup(secret.replaceAll("(.{4})", "$1 ").trim(), uri, Totp.qrSvg(uri));
    }

    /** Confirma o primeiro código, ativa o 2FA, valida a sessão atual e devolve os códigos de recuperação (uma única vez). */
    public Enabled confirm(AuthenticatedUser user, String code) {
        UserProfile p = me(user);
        if (p.isMfaEnabled()) {
            throw new BusinessException("MFA_ALREADY_ENABLED", "A autenticação em dois fatores já está ativa.");
        }
        if (p.getMfaPendingSecretEnc() == null) {
            throw new BusinessException("MFA_SETUP_REQUIRED", "Gere o QR code antes de confirmar.");
        }
        String secret = crypto.decrypt(p.getMfaPendingSecretEnc());
        long step = Totp.verify(secret, code, 0, Instant.now().getEpochSecond());
        if (step < 0) {
            throw new BusinessException("MFA_INVALID_CODE", "Código inválido. Confira a hora do celular e digite o código atual do aplicativo.");
        }
        p.setMfaEnabled(true);
        p.setMfaSecretEnc(p.getMfaPendingSecretEnc());
        p.setMfaPendingSecretEnc(null);
        p.setMfaEnabledAt(Instant.now());
        p.setMfaLastStep(step);
        p.setMfaFailedAttempts(0);
        p.setMfaLockedUntil(null);
        List<String> codes = newRecoveryCodes(p);
        save(p);
        String sid = SessionService.currentSessionId();
        if (sid != null) {
            sessions.markMfaVerified(user.officeId(), sid);
        }
        events.record(user, "MFA_ENABLED", SecurityEvent.Severity.INFO, "Autenticação em dois fatores ativada por " + user.displayName());
        audit.record(user, "MFA_ENABLED", "user", user.uid(), "2FA ativado");
        return new Enabled(codes);
    }

    /** Confirma o segundo fator na sessão atual (código do aplicativo ou de recuperação). */
    public void verify(AuthenticatedUser user, String code) {
        UserProfile p = me(user);
        if (!p.isMfaEnabled() || p.getMfaSecretEnc() == null) {
            throw new BusinessException("MFA_NOT_ENABLED", "A autenticação em dois fatores não está ativa nesta conta.");
        }
        Instant now = Instant.now();
        if (p.getMfaLockedUntil() != null && p.getMfaLockedUntil().isAfter(now)) {
            long min = Math.max(1, Duration.between(now, p.getMfaLockedUntil()).toMinutes() + 1);
            throw new BusinessException("MFA_LOCKED", "Muitas tentativas incorretas. Tente novamente em " + min + " min.");
        }
        String clean = code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        boolean ok = false;
        boolean usedRecovery = false;
        if (clean.matches("\\d{6}")) {
            long step = Totp.verify(crypto.decrypt(p.getMfaSecretEnc()), clean, p.getMfaLastStep(), now.getEpochSecond());
            if (step >= 0) {
                p.setMfaLastStep(step);
                ok = true;
            }
        } else if (clean.length() == 10) {
            String h = crypto.hmac("mfa-recovery|" + clean);
            if (p.getMfaRecoveryHashes() != null && p.getMfaRecoveryHashes().remove(h)) {
                ok = true;
                usedRecovery = true;
            }
        }
        if (!ok) {
            p.setMfaFailedAttempts(p.getMfaFailedAttempts() + 1);
            boolean locked = p.getMfaFailedAttempts() >= MAX_ATTEMPTS;
            if (locked) {
                p.setMfaLockedUntil(now.plus(LOCK));
                p.setMfaFailedAttempts(0);
            }
            save(p);
            if (locked) {
                events.record(user, "MFA_LOCKED", SecurityEvent.Severity.CRITICO,
                        "Segundo fator bloqueado por 15 min após " + MAX_ATTEMPTS + " códigos incorretos (" + user.displayName() + ")");
                notifications.notify(user.officeId(), user.uid(), "SECURITY", "Tentativas de acesso bloqueadas",
                        "Houve " + MAX_ATTEMPTS + " códigos de verificação incorretos na sua conta. Se não foi você, troque a senha.", "/seguranca");
                throw new BusinessException("MFA_LOCKED", "Muitas tentativas incorretas. Tente novamente em 15 min.");
            }
            events.record(user, "MFA_FAILED", SecurityEvent.Severity.ATENCAO, "Código de verificação incorreto (" + user.displayName() + ")");
            throw new BusinessException("MFA_INVALID_CODE", "Código inválido. Restam " + (MAX_ATTEMPTS - p.getMfaFailedAttempts()) + " tentativa(s).");
        }
        p.setMfaFailedAttempts(0);
        p.setMfaLockedUntil(null);
        save(p);
        String sid = SessionService.currentSessionId();
        if (sid != null) {
            sessions.markMfaVerified(user.officeId(), sid);
        }
        if (usedRecovery) {
            int left = p.getMfaRecoveryHashes().size();
            events.record(user, "MFA_RECOVERY_USED", SecurityEvent.Severity.ATENCAO,
                    "Código de recuperação usado por " + user.displayName() + " (restam " + left + ")");
        }
    }

    public void disable(AuthenticatedUser user, String code) {
        UserProfile p = me(user);
        if (!p.isMfaEnabled()) {
            throw new BusinessException("MFA_NOT_ENABLED", "A autenticação em dois fatores não está ativa.");
        }
        if (SecurityPolicyService.mfaRequired(policies.get(user.officeId()), user.role(), user.permissions())) {
            throw new BusinessException("MFA_REQUIRED_BY_POLICY", "A política do escritório exige a autenticação em dois fatores para o seu perfil.");
        }
        verify(user, code);
        clear(p);
        save(p);
        events.record(user, "MFA_DISABLED", SecurityEvent.Severity.ATENCAO, "Autenticação em dois fatores desativada por " + user.displayName());
        audit.record(user, "MFA_DISABLED", "user", user.uid(), "2FA desativado");
    }

    public Enabled regenerateRecovery(AuthenticatedUser user, String code) {
        verify(user, code);
        UserProfile p = me(user);
        List<String> codes = newRecoveryCodes(p);
        save(p);
        events.record(user, "MFA_RECOVERY_REGENERATED", SecurityEvent.Severity.INFO, "Novos códigos de recuperação gerados por " + user.displayName());
        return new Enabled(codes);
    }

    /** Administração: remove o 2FA de um integrante que perdeu o aparelho e encerra as sessões dele. */
    public void reset(AuthenticatedUser admin, String uid) {
        UserProfile p = users.findById(uid).orElseThrow(() -> new NotFoundException("Integrante"));
        if (!admin.requireOffice().equals(p.getOfficeId())) {
            throw new NotFoundException("Integrante");
        }
        if (uid.equals(admin.uid())) {
            throw new ForbiddenException("ACCESS_DENIED", "Peça a outro administrador para redefinir o seu segundo fator.");
        }
        clear(p);
        p.setTokensRevokedAt(Instant.now());
        p.setUpdatedBy(admin.uid());
        save(p);
        events.record(admin.officeId(), "MFA_RESET", SecurityEvent.Severity.CRITICO, "2FA de " + p.getDisplayName() + " redefinido por "
                + admin.displayName() + "; sessões encerradas", uid, p.getDisplayName(), java.util.Map.of("by", admin.uid()));
        audit.record(admin, "MFA_RESET", "user", uid, "2FA redefinido: " + p.getEmail());
        notifications.notify(admin.officeId(), uid, "SECURITY", "Autenticação em dois fatores redefinida",
                "O administrador " + admin.displayName() + " redefiniu o seu segundo fator. Configure-o novamente ao entrar.", "/seguranca");
    }

    private static void clear(UserProfile p) {
        p.setMfaEnabled(false);
        p.setMfaSecretEnc(null);
        p.setMfaPendingSecretEnc(null);
        p.setMfaEnabledAt(null);
        p.setMfaRecoveryHashes(new ArrayList<>());
        p.setMfaFailedAttempts(0);
        p.setMfaLockedUntil(null);
        p.setMfaLastStep(0);
    }

    private List<String> newRecoveryCodes(UserProfile p) {
        List<String> codes = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < 10; j++) {
                sb.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
            }
            String c = sb.toString();
            codes.add(c.substring(0, 5) + "-" + c.substring(5));
            hashes.add(crypto.hmac("mfa-recovery|" + c));
        }
        p.setMfaRecoveryHashes(hashes);
        return codes;
    }
}
