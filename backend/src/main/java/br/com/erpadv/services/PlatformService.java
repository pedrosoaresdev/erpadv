package br.com.erpadv.services;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.dto.PlatformDtos.AdminUserRequest;
import br.com.erpadv.dto.PlatformDtos.CreateOfficeRequest;
import br.com.erpadv.dto.PlatformDtos.OfficeCreated;
import br.com.erpadv.dto.PlatformDtos.OfficeDetail;
import br.com.erpadv.dto.PlatformDtos.OfficeSummary;
import br.com.erpadv.dto.PlatformDtos.OfficeUser;
import br.com.erpadv.dto.PlatformDtos.PlatformOverview;
import br.com.erpadv.dto.PlatformDtos.PlatformStats;
import br.com.erpadv.dto.PlatformDtos.UpdateOfficeRequest;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.ConflictException;
import br.com.erpadv.exceptions.DataAccessFailure;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.firebase.CryptoService;
import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.firebase.SearchTokens;
import br.com.erpadv.models.Office;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.models.UserStatus;
import br.com.erpadv.repositories.OfficeRepository;
import br.com.erpadv.repositories.UserRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.OfficeStatusCache;
import br.com.erpadv.security.Role;
import br.com.erpadv.security.UserProfileCache;
import br.com.erpadv.validation.BrazilianDocuments;
import br.com.erpadv.validation.PasswordPolicy;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.AggregateQuerySnapshot;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.WriteBatch;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Painel master da plataforma: o desenvolvedor cria escritórios (tenants) já com o advogado
 * administrador (nome, CPF, OAB, e-mail e senha), ajusta plano/limite de usuários, suspende e
 * reativa escritórios e redefine o acesso dos administradores.
 */
@Service
public class PlatformService {

    private static final Logger log = LoggerFactory.getLogger(PlatformService.class);

    private final Firestore db;
    private final FirestoreMapper mapper;
    private final FirebaseAuth firebaseAuth;
    private final OfficeRepository offices;
    private final UserRepository users;
    private final CryptoService crypto;
    private final UserProfileCache profileCache;
    private final OfficeStatusCache officeStatus;
    private final EmployeeService employees;
    private final AuditService audit;

    public PlatformService(Firestore db, FirestoreMapper mapper, FirebaseAuth firebaseAuth, OfficeRepository offices,
                           UserRepository users, CryptoService crypto, UserProfileCache profileCache,
                           OfficeStatusCache officeStatus, EmployeeService employees, AuditService audit) {
        this.db = db;
        this.mapper = mapper;
        this.firebaseAuth = firebaseAuth;
        this.offices = offices;
        this.users = users;
        this.crypto = crypto;
        this.profileCache = profileCache;
        this.officeStatus = officeStatus;
        this.employees = employees;
        this.audit = audit;
    }

    public PlatformOverview overview() {
        List<Office> all = offices.all(1000);
        // Contagens de licenças em paralelo (uma agregação por escritório).
        List<ApiFuture<AggregateQuerySnapshot>> seatFutures = new ArrayList<>();
        for (Office o : all) {
            seatFutures.add(db.collection("users").whereEqualTo("officeId", o.getId())
                    .whereIn("status", List.of(UserStatus.ACTIVE.name(), UserStatus.INVITED.name())).count().get());
        }
        List<OfficeSummary> summaries = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            summaries.add(summary(all.get(i), Futures.await(seatFutures.get(i)).getCount()));
        }
        long suspended = all.stream().filter(o -> OfficeStatusCache.SUSPENDED.equals(o.getStatus())).count();
        PlatformStats stats = new PlatformStats(all.size(), all.size() - suspended, suspended,
                users.countAll(null), users.countAll(UserStatus.ACTIVE));
        return new PlatformOverview(stats, summaries);
    }

    public OfficeDetail detail(String officeId) {
        Office office = load(officeId);
        List<UserProfile> members = users.allInOffice(officeId);
        List<OfficeUser> people = members.stream()
                .filter(u -> u.getRole() != Role.CLIENTE)
                .sorted(Comparator.comparing((UserProfile u) -> u.getRole() == Role.SOCIO_ADMIN ? 0 : 1)
                        .thenComparing(u -> u.getDisplayNameLower() == null ? "" : u.getDisplayNameLower()))
                .map(u -> officeUser(u, office))
                .toList();
        long seats = members.stream().filter(u -> u.getRole() != Role.CLIENTE
                && (u.getStatus() == UserStatus.ACTIVE || u.getStatus() == UserStatus.INVITED)).count();
        DocumentReference ref = offices.ref(officeId);
        var clients = ref.collection("clients").count().get();
        var cases = ref.collection("cases").count().get();
        var contracts = ref.collection("contracts").count().get();
        var boards = ref.collection("boards").count().get();
        var leads = ref.collection("leads").count().get();
        return new OfficeDetail(summary(office, seats), people,
                Futures.await(clients).getCount(), Futures.await(cases).getCount(),
                Futures.await(contracts).getCount(), Futures.await(boards).getCount(), Futures.await(leads).getCount());
    }

    public OfficeCreated createOffice(AuthenticatedUser master, CreateOfficeRequest req) {
        AdminUserRequest admin = req.admin();
        String cpf = validCpf(admin.cpf());
        PasswordPolicy.check(admin.password());
        String email = admin.email().trim().toLowerCase(Locale.ROOT);
        String uid = createAuthUser(email, admin.fullName().trim(), admin.password());

        Instant now = Instant.now();
        DocumentReference officeRef = offices.newRef();
        Office office = new Office();
        office.setId(officeRef.getId());
        office.setOfficeId(officeRef.getId());
        applyOfficeFields(office, req.name(), req.cnpj(), req.email(), req.phone(), req.city(), req.state(),
                req.plan(), req.maxUsers());
        office.setOwnerUid(uid);
        office.setStatus("ACTIVE");
        office.setCreatedByPlatform(true);
        office.setCreatedAt(now);
        office.setUpdatedAt(now);
        office.setCreatedBy(master.uid());

        UserProfile profile = adminProfile(uid, office.getId(), email, admin, cpf, master, now);
        WriteBatch batch = db.batch();
        batch.create(officeRef, mapper.toMap(office));
        batch.create(users.ref(uid), mapper.toMap(profile));
        try {
            Futures.await(batch.commit());
            setClaims(uid, office.getId(), Role.SOCIO_ADMIN);
        } catch (RuntimeException e) {
            rollbackAuthUser(uid);
            throw e;
        }
        audit.record(master, office.getId(), "OFFICE_CREATED_BY_PLATFORM", "office", office.getId(),
                "Escritório criado pela plataforma: " + office.getName(), Map.of("adminUid", uid, "plan", office.getPlan()));
        audit.record(master, office.getId(), "EMPLOYEE_CREATED", "user", uid,
                "Administrador cadastrado pela plataforma: " + email, Map.of("role", Role.SOCIO_ADMIN.name()));
        return new OfficeCreated(summary(office, 1), officeUser(profile, office));
    }

    public OfficeSummary update(AuthenticatedUser master, String officeId, UpdateOfficeRequest req) {
        Office office = load(officeId);
        applyOfficeFields(office, req.name(), req.cnpj(), req.email(), req.phone(), req.city(), req.state(),
                req.plan(), req.maxUsers());
        office.setUpdatedAt(Instant.now());
        office.setUpdatedBy(master.uid());
        offices.save(office);
        audit.record(master, officeId, "OFFICE_UPDATED_BY_PLATFORM", "office", officeId,
                "Dados do escritório atualizados pela plataforma", Map.of("plan", office.getPlan(),
                        "maxUsers", office.getMaxUsers() == null ? "ilimitado" : office.getMaxUsers().toString()));
        return summary(office, users.countSeats(officeId));
    }

    public OfficeSummary suspend(AuthenticatedUser master, String officeId, String reason) {
        Office office = load(officeId);
        if (OfficeStatusCache.SUSPENDED.equals(office.getStatus())) {
            throw new BusinessException("ALREADY_SUSPENDED", "Escritório já está suspenso.");
        }
        Instant now = Instant.now();
        office.setStatus(OfficeStatusCache.SUSPENDED);
        office.setSuspendedAt(now);
        office.setSuspendedReason(reason == null || reason.isBlank() ? null : reason.trim());
        office.setUpdatedAt(now);
        office.setUpdatedBy(master.uid());
        offices.save(office);
        officeStatus.evict(officeId);
        audit.record(master, officeId, "OFFICE_SUSPENDED", "office", officeId, "Escritório suspenso pela plataforma",
                Map.of("reason", reason == null ? "" : reason));
        return summary(office, users.countSeats(officeId));
    }

    public OfficeSummary reactivate(AuthenticatedUser master, String officeId) {
        Office office = load(officeId);
        if (!OfficeStatusCache.SUSPENDED.equals(office.getStatus())) {
            throw new BusinessException("NOT_SUSPENDED", "Escritório não está suspenso.");
        }
        office.setStatus("ACTIVE");
        office.setSuspendedAt(null);
        office.setSuspendedReason(null);
        office.setUpdatedAt(Instant.now());
        office.setUpdatedBy(master.uid());
        offices.save(office);
        officeStatus.evict(officeId);
        audit.record(master, officeId, "OFFICE_REACTIVATED", "office", officeId, "Escritório reativado pela plataforma",
                Map.of());
        return summary(office, users.countSeats(officeId));
    }

    /** Cadastra mais um sócio administrador num escritório existente. */
    public OfficeUser addAdmin(AuthenticatedUser master, String officeId, AdminUserRequest admin) {
        Office office = load(officeId);
        String cpf = validCpf(admin.cpf());
        PasswordPolicy.check(admin.password());
        if (users.existsCpfInOffice(officeId, crypto.hmac(cpf))) {
            throw new ConflictException("CPF_IN_USE", "Já existe um usuário com este CPF no escritório.");
        }
        employees.assertSeat(office);
        String email = admin.email().trim().toLowerCase(Locale.ROOT);
        String uid = createAuthUser(email, admin.fullName().trim(), admin.password());
        UserProfile profile = adminProfile(uid, officeId, email, admin, cpf, master, Instant.now());
        try {
            Futures.await(users.ref(uid).create(mapper.toMap(profile)));
            setClaims(uid, officeId, Role.SOCIO_ADMIN);
        } catch (RuntimeException e) {
            rollbackAuthUser(uid);
            throw e;
        }
        audit.record(master, officeId, "EMPLOYEE_CREATED", "user", uid,
                "Administrador cadastrado pela plataforma: " + email, Map.of("role", Role.SOCIO_ADMIN.name()));
        return officeUser(profile, office);
    }

    public void setPassword(AuthenticatedUser master, String officeId, String uid, String password) {
        UserProfile profile = users.findInOffice(officeId, uid).orElseThrow(() -> new NotFoundException("Usuário"));
        PasswordPolicy.check(password);
        try {
            firebaseAuth.updateUser(new UserRecord.UpdateRequest(uid).setPassword(password));
            firebaseAuth.revokeRefreshTokens(uid);
        } catch (FirebaseAuthException e) {
            throw new DataAccessFailure("Falha ao definir a senha no provedor de identidade.", e);
        }
        profile.setTokensRevokedAt(Instant.now().plusSeconds(1));
        profile.setUpdatedAt(Instant.now());
        profile.setUpdatedBy(master.uid());
        users.save(profile);
        profileCache.evict(uid);
        audit.record(master, officeId, "EMPLOYEE_PASSWORD_SET", "user", uid,
                "Senha redefinida pela plataforma: " + profile.getEmail(), Map.of());
    }

    public String resetLink(AuthenticatedUser master, String officeId, String uid) {
        UserProfile profile = users.findInOffice(officeId, uid).orElseThrow(() -> new NotFoundException("Usuário"));
        String link;
        try {
            link = employees.publicLink(firebaseAuth.generatePasswordResetLink(profile.getEmail()));
        } catch (FirebaseAuthException e) {
            throw new DataAccessFailure("Falha ao gerar link de redefinição de senha.", e);
        }
        audit.record(master, officeId, "EMPLOYEE_ACCESS_RESET", "user", uid,
                "Link de redefinição gerado pela plataforma: " + profile.getEmail(), Map.of());
        return link;
    }

    // ------------------------------------------------------------------ helpers

    private Office load(String officeId) {
        return offices.findById(officeId).orElseThrow(() -> new NotFoundException("Escritório"));
    }

    private void applyOfficeFields(Office office, String name, String cnpj, String email, String phone, String city,
                                   String state, String plan, Integer maxUsers) {
        office.setName(name.trim());
        office.setNameLower(SearchTokens.normalize(name));
        office.setCnpj(cnpj == null || cnpj.isBlank() ? null : BrazilianDocuments.format(cnpj));
        office.setEmail(blankToNull(email));
        office.setPhone(blankToNull(phone));
        office.setCity(blankToNull(city));
        office.setState(blankToNull(state));
        office.setPlan(plan);
        office.setMaxUsers(maxUsers);
    }

    private UserProfile adminProfile(String uid, String officeId, String email, AdminUserRequest admin, String cpf,
                                     AuthenticatedUser master, Instant now) {
        UserProfile profile = new UserProfile();
        profile.setId(uid);
        profile.setOfficeId(officeId);
        profile.setEmail(email);
        profile.setDisplayName(admin.fullName().trim());
        profile.setDisplayNameLower(SearchTokens.normalize(admin.fullName()));
        profile.setRole(Role.SOCIO_ADMIN);
        profile.setStatus(UserStatus.INVITED);
        profile.setOabNumber(admin.oabNumber().trim().toUpperCase(Locale.ROOT));
        profile.setPhone(blankToNull(admin.phone()));
        profile.setPosition("Sócio administrador");
        profile.setCpfEnc(crypto.encrypt(cpf));
        profile.setCpfHash(crypto.hmac(cpf));
        profile.setCpfMasked(BrazilianDocuments.mask(cpf));
        profile.setInvitedAt(now);
        profile.setInvitedBy(master.uid());
        profile.setCreatedAt(now);
        profile.setUpdatedAt(now);
        profile.setCreatedBy(master.uid());
        return profile;
    }

    static String validCpf(String raw) {
        String cpf = BrazilianDocuments.digits(raw);
        if (cpf.length() != 11 || !BrazilianDocuments.isValidCpf(cpf)) {
            throw new BusinessException("INVALID_CPF", "CPF inválido.");
        }
        return cpf;
    }

    private String createAuthUser(String email, String displayName, String password) {
        try {
            return firebaseAuth.createUser(new UserRecord.CreateRequest()
                    .setEmail(email)
                    .setPassword(password)
                    .setDisplayName(displayName)
                    .setEmailVerified(true)
                    .setDisabled(false)).getUid();
        } catch (FirebaseAuthException e) {
            if (e.getAuthErrorCode() == AuthErrorCode.EMAIL_ALREADY_EXISTS) {
                throw new ConflictException("EMAIL_IN_USE", "Este e-mail já possui conta na plataforma.");
            }
            throw new DataAccessFailure("Falha ao criar usuário no provedor de identidade.", e);
        }
    }

    private void rollbackAuthUser(String uid) {
        try {
            firebaseAuth.deleteUser(uid);
        } catch (FirebaseAuthException | RuntimeException e) {
            log.error("Falha ao desfazer criação do usuário {} no Firebase Auth", uid, e);
        }
    }

    private void setClaims(String uid, String officeId, Role role) {
        try {
            firebaseAuth.setCustomUserClaims(uid, Map.of("officeId", officeId, "role", role.name()));
        } catch (FirebaseAuthException e) {
            throw new DataAccessFailure("Falha ao definir credenciais do usuário.", e);
        }
    }

    private static OfficeSummary summary(Office o, long seats) {
        return new OfficeSummary(o.getId(), o.getName(), o.getCnpj(), o.getEmail(), o.getPhone(), o.getCity(),
                o.getState(), o.getPlan(), o.getStatus(), o.getMaxUsers(), o.getCreatedAt(), o.getSuspendedAt(),
                o.getSuspendedReason(), Boolean.TRUE.equals(o.getCreatedByPlatform()), seats);
    }

    private static OfficeUser officeUser(UserProfile u, Office office) {
        return new OfficeUser(u.getId(), u.getDisplayName(), u.getEmail(), u.getRole(),
                u.getRole() == null ? null : u.getRole().label(), u.getCustomRoleName(), u.getStatus(),
                u.getOabNumber(), u.getCpfMasked(), u.getId().equals(office.getOwnerUid()),
                u.getCreatedAt(), u.getLastLoginAt());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
