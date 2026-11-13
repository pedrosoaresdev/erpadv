package br.com.erpadv.services;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.config.AppProperties;
import br.com.erpadv.dto.AuthDtos.CreateOfficeRequest;
import br.com.erpadv.dto.AuthDtos.MeResponse;
import br.com.erpadv.dto.AuthDtos.OfficeCreatedResponse;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.ConflictException;
import br.com.erpadv.exceptions.DataAccessFailure;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.SearchTokens;
import br.com.erpadv.models.Office;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.models.UserStatus;
import br.com.erpadv.repositories.OfficeRepository;
import br.com.erpadv.repositories.UserRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Role;
import br.com.erpadv.security.UserProfileCache;
import br.com.erpadv.validation.BrazilianDocuments;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.WriteBatch;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/** Sessão do usuário, perfil corrente e criação do escritório (primeiro acesso). */
@Service
public class AuthService {

    private final Firestore db;
    private final FirestoreMapper mapper;
    private final FirebaseAuth firebaseAuth;
    private final UserRepository users;
    private final OfficeRepository offices;
    private final UserProfileCache profileCache;
    private final AuditService audit;
    private final AppProperties properties;

    public AuthService(Firestore db, FirestoreMapper mapper, FirebaseAuth firebaseAuth, UserRepository users,
                       OfficeRepository offices, UserProfileCache profileCache, AuditService audit,
                       AppProperties properties) {
        this.db = db;
        this.mapper = mapper;
        this.firebaseAuth = firebaseAuth;
        this.users = users;
        this.offices = offices;
        this.profileCache = profileCache;
        this.audit = audit;
        this.properties = properties;
    }

    public MeResponse me(AuthenticatedUser user) {
        boolean verificationRequired = properties.security().requireEmailVerified() && !user.emailVerified();
        boolean platformAdmin = user.has(br.com.erpadv.security.Permission.PLATFORM_ADMIN);
        if (!user.hasOffice()) {
            if (user.role() == Role.MASTER) {
                return new MeResponse(user.uid(), user.email(), user.name(), user.emailVerified(), false, false,
                        null, "Plataforma ERPAdv", Role.MASTER.name(), Role.MASTER.label(), null,
                        user.permissions().stream().map(Enum::name).sorted().toList(), true, null);
            }
            return new MeResponse(user.uid(), user.email(), user.name(), user.emailVerified(), true,
                    verificationRequired, null, null, null, null, null, java.util.List.of(), false, null);
        }
        String officeName = offices.findById(user.officeId()).map(Office::getName).orElse(null);
        var profile = users.findById(user.uid());
        String name = profile.map(UserProfile::getDisplayName).orElse(user.name());
        String customRole = profile.map(UserProfile::getCustomRoleName).orElse(null);
        return new MeResponse(user.uid(), user.email(), name, user.emailVerified(), false, verificationRequired,
                user.officeId(), officeName, user.role().name(), user.role().label(), user.clientId(),
                user.permissions().stream().map(Enum::name).sorted().toList(), platformAdmin, customRole);
    }

    /** Chamado pelo front logo após o login: registra o acesso e ativa contas convidadas. */
    public void registerSession(AuthenticatedUser user) {
        if (!user.hasOffice()) {
            return;
        }
        users.findById(user.uid()).ifPresent(profile -> {
            Instant now = Instant.now();
            profile.setLastLoginAt(now);
            if (profile.getStatus() == UserStatus.INVITED) {
                profile.setStatus(UserStatus.ACTIVE);
                profile.setActivatedAt(now);
            }
            users.save(profile);
            profileCache.evict(user.uid());
        });
        audit.record(user, "LOGIN", "user", user.uid(), "Acesso ao sistema");
    }

    public OfficeCreatedResponse createOffice(AuthenticatedUser user, CreateOfficeRequest req) {
        if (user.role() == Role.MASTER) {
            throw new BusinessException("PLATFORM_ACCOUNT", "Conta da plataforma: crie escritórios pelo painel master.");
        }
        if (properties.security().requireEmailVerified() && !user.emailVerified()) {
            throw new BusinessException("EMAIL_NOT_VERIFIED", "Confirme seu e-mail antes de criar o escritório.");
        }
        var existing = users.findById(user.uid());
        if (existing.isPresent() && existing.get().getOfficeId() != null) {
            // Idempotência: perfil já criado mas claims não aplicados (ex.: falha anterior).
            UserProfile profile = existing.get();
            if (user.hasOffice()) {
                throw new ConflictException("ALREADY_ONBOARDED", "Usuário já pertence a um escritório.");
            }
            applyClaims(profile.getId(), profile.getOfficeId(), profile.getRole());
            String name = offices.findById(profile.getOfficeId()).map(Office::getName).orElse(req.name());
            return new OfficeCreatedResponse(profile.getOfficeId(), name, true);
        }

        Instant now = Instant.now();
        DocumentReference officeRef = offices.newRef();
        Office office = new Office();
        office.setId(officeRef.getId());
        office.setOfficeId(officeRef.getId());
        office.setName(req.name().trim());
        office.setNameLower(SearchTokens.normalize(req.name()));
        office.setCnpj(req.cnpj() == null || req.cnpj().isBlank() ? null : BrazilianDocuments.format(req.cnpj()));
        office.setEmail(req.email());
        office.setPhone(req.phone());
        office.setCity(req.city());
        office.setState(req.state());
        office.setOwnerUid(user.uid());
        office.setStatus("ACTIVE");
        // novo escritório: avaliação gratuita com todos os módulos; depois a plataforma define o plano contratado
        office.setPlan(trialPlan);
        office.setSubscriptionStatus("TRIAL");
        office.setBillingCycle("MENSAL");
        office.setGraceDays(10);
        office.setTrialEndsAt(java.time.LocalDate.now(br.com.erpadv.services.CourtCalendarService.ZONE).plusDays(trialDays));
        office.setCreatedAt(now);
        office.setUpdatedAt(now);
        office.setCreatedBy(user.uid());

        UserProfile profile = new UserProfile();
        profile.setId(user.uid());
        profile.setOfficeId(office.getId());
        profile.setEmail(user.email());
        profile.setDisplayName(req.responsibleName().trim());
        profile.setDisplayNameLower(SearchTokens.normalize(req.responsibleName()));
        profile.setRole(Role.SOCIO_ADMIN);
        profile.setStatus(UserStatus.ACTIVE);
        profile.setOabNumber(req.oabNumber());
        profile.setPosition("Sócio administrador");
        profile.setActivatedAt(now);
        profile.setLastLoginAt(now);
        profile.setCreatedAt(now);
        profile.setUpdatedAt(now);
        profile.setCreatedBy(user.uid());

        WriteBatch batch = db.batch();
        batch.create(officeRef, mapper.toMap(office));
        batch.create(users.ref(user.uid()), mapper.toMap(profile));
        Futures.await(batch.commit());

        applyClaims(user.uid(), office.getId(), Role.SOCIO_ADMIN);
        profileCache.evict(user.uid());
        AuthenticatedUser actor = new AuthenticatedUser(user.uid(), user.email(), profile.getDisplayName(),
                user.emailVerified(), office.getId(), Role.SOCIO_ADMIN, null, java.util.Set.of());
        audit.record(actor, office.getId(), "OFFICE_CREATED", "office", office.getId(),
                "Escritório criado: " + office.getName(), Map.of("ownerUid", user.uid()));
        return new OfficeCreatedResponse(office.getId(), office.getName(), true);
    }

    void applyClaims(String uid, String officeId, Role role) {
        try {
            firebaseAuth.setCustomUserClaims(uid, Map.of("officeId", officeId, "role", role.name()));
        } catch (FirebaseAuthException e) {
            throw new DataAccessFailure("Não foi possível atualizar as credenciais do usuário.", e);
        }
    }

    @org.springframework.beans.factory.annotation.Value("${app.billing.trial-plan:ENTERPRISE}")
    private String trialPlan;
    @org.springframework.beans.factory.annotation.Value("${app.billing.trial-days:14}")
    private int trialDays;
}
