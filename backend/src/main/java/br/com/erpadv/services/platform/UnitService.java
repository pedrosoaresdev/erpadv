package br.com.erpadv.services.platform;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.ConflictException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.Address;
import br.com.erpadv.models.Unit;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.repositories.CaseRepository;
import br.com.erpadv.repositories.FirestoreRepository;
import br.com.erpadv.repositories.UserRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.UserProfileCache;
import br.com.erpadv.validation.BrazilianDocuments;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Unidades e filiais: cadastro, integrantes vinculados e processos de cada unidade. */
@Service
public class UnitService {

    public record UnitRequest(String name, String cnpj, String phone, String email, Address address, String managerUid, boolean active) {
    }

    public record UnitView(String id, String name, String cnpj, String phone, String email, Address address, String managerUid,
                           String managerName, boolean active, long members, long cases, List<String> memberUids) {
    }

    @Repository
    public static class Units extends FirestoreRepository<Unit> {
        public Units(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, Unit.class, "units");
        }

        public void delete(String officeId, String id) {
            Futures.await(collection(officeId).document(id).delete());
        }
    }

    private final Units units;
    private final UserRepository users;
    private final UserProfileCache profiles;
    private final CaseRepository cases;
    private final AuditService audit;

    public UnitService(Units units, UserRepository users, UserProfileCache profiles, CaseRepository cases, AuditService audit) {
        this.units = units;
        this.users = users;
        this.profiles = profiles;
        this.cases = cases;
        this.audit = audit;
    }

    public List<UnitView> list(AuthenticatedUser user) {
        String officeId = user.requireOffice();
        List<UserProfile> staff = users.activeStaff(officeId);
        return units.list(units.collection(officeId), 200).stream()
                .sorted(Comparator.comparing(Unit::getName, String.CASE_INSENSITIVE_ORDER))
                .map(u -> new UnitView(u.getId(), u.getName(), u.getCnpj(), u.getPhone(), u.getEmail(), u.getAddress(), u.getManagerUid(),
                        u.getManagerName(), u.isActive(), staff.stream().filter(p -> u.getId().equals(p.getUnitId())).count(),
                        cases.count(cases.collection(officeId).whereEqualTo("unitId", u.getId())),
                        staff.stream().filter(p -> u.getId().equals(p.getUnitId())).map(UserProfile::getId).toList()))
                .toList();
    }

    /** Integrantes ativos da unidade (usado no filtro do dashboard). */
    public List<String> memberUids(String officeId, String unitId) {
        return users.activeStaff(officeId).stream().filter(p -> unitId.equals(p.getUnitId())).map(UserProfile::getId).toList();
    }

    public Unit get(String officeId, String id) {
        return units.findById(officeId, id).orElseThrow(() -> new NotFoundException("Unidade"));
    }

    public Unit save(AuthenticatedUser user, String id, UnitRequest req) {
        String officeId = user.requireOffice();
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty() || name.length() > 120) {
            throw new BusinessException("NAME_REQUIRED", "Informe o nome da unidade (até 120 caracteres).");
        }
        boolean duplicate = units.list(units.collection(officeId), 200).stream()
                .anyMatch(u -> u.getName().equalsIgnoreCase(name) && !u.getId().equals(id));
        if (duplicate) {
            throw new ConflictException("DUPLICATE_NAME", "Já existe uma unidade com esse nome.");
        }
        if (req.cnpj() != null && !req.cnpj().isBlank() && !BrazilianDocuments.isValidCnpj(req.cnpj())) {
            throw new BusinessException("INVALID_CNPJ", "CNPJ inválido.");
        }
        Unit u = id == null ? new Unit() : get(officeId, id);
        Map<String, Object> before = id == null ? null : audit.snapshot(u);
        if (id == null) {
            u.setId(UUID.randomUUID().toString());
            u.setCreatedAt(Instant.now());
            u.setCreatedBy(user.uid());
        }
        u.setName(name);
        u.setCnpj(req.cnpj() == null || req.cnpj().isBlank() ? null : BrazilianDocuments.format(req.cnpj()));
        u.setPhone(blank(req.phone()));
        u.setEmail(blank(req.email()));
        u.setAddress(req.address());
        u.setActive(req.active());
        if (req.managerUid() != null && !req.managerUid().isBlank()) {
            UserProfile m = users.findInOffice(officeId, req.managerUid()).orElseThrow(() -> new NotFoundException("Responsável"));
            u.setManagerUid(m.getId());
            u.setManagerName(m.getDisplayName());
        } else {
            u.setManagerUid(null);
            u.setManagerName(null);
        }
        u.setUpdatedAt(Instant.now());
        u.setUpdatedBy(user.uid());
        units.save(officeId, u);
        if (id == null) {
            audit.record(user, "UNIT_CREATED", "unit", u.getId(), "Unidade criada: " + name);
        } else {
            audit.recordChange(user, "UNIT_UPDATED", "unit", u.getId(), "Unidade atualizada: " + name, Map.of(), before, u);
        }
        return u;
    }

    /** Define quem trabalha na unidade (cada integrante pertence a no máximo uma unidade). */
    public UnitView setMembers(AuthenticatedUser user, String id, List<String> uids) {
        String officeId = user.requireOffice();
        Unit u = get(officeId, id);
        Set<String> wanted = uids == null ? Set.of() : Set.copyOf(uids);
        for (UserProfile p : users.activeStaff(officeId)) {
            boolean in = wanted.contains(p.getId());
            boolean was = id.equals(p.getUnitId());
            if (in != was) {
                p.setUnitId(in ? id : null);
                p.setUnitName(in ? u.getName() : null);
                p.setUpdatedAt(Instant.now());
                users.save(p);
                profiles.evict(p.getId());
            }
        }
        audit.record(user, "UNIT_MEMBERS_UPDATED", "unit", id, "Integrantes da unidade " + u.getName() + ": " + wanted.size());
        return list(user).stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
    }

    public void delete(AuthenticatedUser user, String id) {
        String officeId = user.requireOffice();
        Unit u = get(officeId, id);
        if (cases.count(cases.collection(officeId).whereEqualTo("unitId", id)) > 0 || !memberUids(officeId, id).isEmpty()) {
            throw new ConflictException("UNIT_IN_USE", "A unidade tem processos ou integrantes vinculados. Desative-a em vez de excluir.");
        }
        units.delete(officeId, id);
        audit.record(user, "UNIT_DELETED", "unit", id, "Unidade excluída: " + u.getName());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
