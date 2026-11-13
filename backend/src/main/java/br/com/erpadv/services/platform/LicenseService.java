package br.com.erpadv.services.platform;

import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.AppModule;
import br.com.erpadv.models.Office;
import br.com.erpadv.models.Plan;
import br.com.erpadv.repositories.OfficeRepository;
import br.com.erpadv.security.Permission;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Licença de cada escritório: plano contratado, módulos opcionais liberados, limites de usuários e de armazenamento. Os
 * módulos fora do plano (ou desligados pelo escritório) têm as permissões retiradas de todos os integrantes — no servidor.
 */
@Service
public class LicenseService {

    private static final Logger log = LoggerFactory.getLogger(LicenseService.class);
    private static final long CACHE_MS = 30_000;

    public record License(String planCode, String planName, Set<AppModule> planModules, Set<AppModule> modules, Integer maxUsers,
                          Integer maxStorageMb) {
    }

    private record Cached(License license, long at) {
    }

    /** Planos na coleção global {@code plans} (fora dos escritórios). */
    @Repository
    public static class Plans {
        private final Firestore db;
        private final FirestoreMapper mapper;

        public Plans(Firestore db, FirestoreMapper mapper) {
            this.db = db;
            this.mapper = mapper;
        }

        public List<Plan> all() {
            List<Plan> out = new ArrayList<>();
            for (QueryDocumentSnapshot d : Futures.await(db.collection("plans").get()).getDocuments()) {
                out.add(mapper.fromSnapshot(d, Plan.class));
            }
            out.sort(Comparator.comparingInt(Plan::getSortOrder).thenComparing(Plan::getCode));
            return out;
        }

        public Optional<Plan> find(String code) {
            if (code == null || code.isBlank()) {
                return Optional.empty();
            }
            var snap = Futures.await(db.collection("plans").document(code).get());
            return snap.exists() ? Optional.of(mapper.fromSnapshot(snap, Plan.class)) : Optional.empty();
        }

        public Plan save(Plan p) {
            p.setId(p.getCode());
            Futures.await(db.collection("plans").document(p.getCode()).set(mapper.toMap(p)));
            return p;
        }
    }

    private final Plans plans;
    private final OfficeRepository offices;
    private final Firestore db;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public LicenseService(Plans plans, OfficeRepository offices, Firestore db) {
        this.plans = plans;
        this.offices = offices;
        this.db = db;
    }

    /**
     * Catálogo de planos; na primeira consulta cria os três planos de referência (sem preço — a plataforma define os valores
     * antes de cobrar).
     */
    public List<Plan> plans() {
        List<Plan> all = plans.all();
        if (!all.isEmpty()) {
            return all;
        }
        plans.save(seed("ESSENCIAL", "Essencial", "Clientes, processos, prazos, agenda, documentos, editor jurídico, relatórios e LGPD.",
                3, 2048, List.of(AppModule.RELATORIOS, AppModule.LGPD), 0, 1, false));
        plans.save(seed("PROFISSIONAL", "Profissional", "Tudo do Essencial mais financeiro, honorários, contratos, quadros e tarefas.",
                10, 10240, List.of(AppModule.FINANCEIRO, AppModule.CONTRATOS, AppModule.QUADROS, AppModule.RELATORIOS, AppModule.LGPD), 14, 2, true));
        plans.save(seed("ENTERPRISE", "Enterprise", "Todos os módulos, inclusive CRM comercial, usuários ilimitados e mais armazenamento.",
                null, 51200, List.of(AppModule.values()), 14, 3, false));
        return plans.all();
    }

    private static Plan seed(String code, String name, String description, Integer users, int storageMb, List<AppModule> modules,
                             int trialDays, int order, boolean highlight) {
        Plan p = new Plan();
        p.setCode(code);
        p.setName(name);
        p.setDescription(description);
        p.setMaxUsers(users);
        p.setMaxStorageMb(storageMb);
        p.setModules(new ArrayList<>(modules.stream().map(Enum::name).toList()));
        p.setTrialDays(trialDays);
        p.setSortOrder(order);
        p.setHighlight(highlight);
        p.setActive(true);
        p.setCreatedAt(Instant.now());
        return p;
    }

    public Optional<Plan> plan(String code) {
        plans();
        return plans.find(code);
    }

    public Plan savePlan(Plan p) {
        Plan saved = plans.save(p);
        cache.clear();
        return saved;
    }

    public License license(String officeId) {
        long now = System.currentTimeMillis();
        Cached c = cache.get(officeId);
        if (c != null && now - c.at() < CACHE_MS) {
            return c.license();
        }
        Office office = offices.findById(officeId).orElse(null);
        License l = compute(office);
        cache.put(officeId, new Cached(l, now));
        return l;
    }

    License compute(Office office) {
        if (office != null && office.getSubscriptionStatus() == null) {
            // escritório anterior ao licenciamento: mantém tudo o que já usava até a plataforma configurar a assinatura
            return new License(office.getPlan(), null, EnumSet.allOf(AppModule.class), withoutDisabled(office, EnumSet.allOf(AppModule.class)),
                    office.getMaxUsers(), office.getMaxStorageMb());
        }
        Plan plan = office == null ? null : plan(office.getPlan()).orElse(null);
        Set<AppModule> planModules = EnumSet.noneOf(AppModule.class);
        if (plan == null) {
            planModules.addAll(EnumSet.allOf(AppModule.class));
        } else {
            for (String m : plan.getModules()) {
                try {
                    planModules.add(AppModule.valueOf(m));
                } catch (IllegalArgumentException ignored) {
                    // módulo removido do catálogo
                }
            }
        }
        Set<AppModule> active = withoutDisabled(office, planModules);
        Integer users = office != null && office.getMaxUsers() != null ? office.getMaxUsers() : plan == null ? null : plan.getMaxUsers();
        Integer storage = office != null && office.getMaxStorageMb() != null ? office.getMaxStorageMb() : plan == null ? null : plan.getMaxStorageMb();
        return new License(plan == null ? office == null ? null : office.getPlan() : plan.getCode(), plan == null ? null : plan.getName(),
                planModules, active, users, storage);
    }

    private static Set<AppModule> withoutDisabled(Office office, Set<AppModule> modules) {
        Set<AppModule> active = modules.isEmpty() ? EnumSet.noneOf(AppModule.class) : EnumSet.copyOf(modules);
        if (office != null && office.getDisabledModules() != null) {
            office.getDisabledModules().forEach(m -> active.removeIf(x -> x.name().equals(m)));
        }
        return active;
    }

    public void evict(String officeId) {
        cache.remove(officeId);
    }

    /** Retira as permissões dos módulos não licenciados. Falha na leitura da licença não bloqueia o acesso. */
    public Set<Permission> restrict(String officeId, Set<Permission> permissions) {
        if (officeId == null || permissions.isEmpty()) {
            return permissions;
        }
        try {
            License l = license(officeId);
            EnumSet<Permission> out = EnumSet.copyOf(permissions);
            for (AppModule m : AppModule.values()) {
                if (!l.modules().contains(m)) {
                    out.removeAll(m.permissions());
                }
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("Licença indisponível para {}: permissões mantidas", officeId, e);
            return permissions;
        }
    }

    // ------------------------------------------------------------------ armazenamento

    public long storageUsed(String officeId) {
        Office o = offices.findById(officeId).orElse(null);
        if (o == null) {
            return 0;
        }
        if (o.getStorageBytes() != null) {
            return Math.max(0, o.getStorageBytes());
        }
        // primeira consulta: soma as versões já existentes e passa a manter o contador
        long total = 0;
        for (QueryDocumentSnapshot d : Futures.await(offices.ref(officeId).collection("document_versions").select("size").get()).getDocuments()) {
            Long size = d.getLong("size");
            total += size == null ? 0 : size;
        }
        Futures.await(offices.ref(officeId).update("storageBytes", total));
        return total;
    }

    /** Recusa o envio que ultrapassaria o armazenamento do plano. */
    public void assertStorage(String officeId, long addBytes) {
        Integer max = license(officeId).maxStorageMb();
        if (max == null) {
            return;
        }
        long used = storageUsed(officeId);
        if (used + addBytes > (long) max * 1024 * 1024) {
            throw new BusinessException("STORAGE_LIMIT", "Armazenamento do plano esgotado (" + (used / (1024 * 1024)) + " de " + max
                    + " MB). Exclua arquivos da lixeira ou fale com o suporte para ampliar.");
        }
    }

    public void addStorage(String officeId, long delta) {
        try {
            storageUsed(officeId);
            Futures.await(offices.ref(officeId).update("storageBytes", FieldValue.increment(delta)));
        } catch (RuntimeException e) {
            log.warn("Não foi possível atualizar o armazenamento de {}", officeId, e);
        }
    }

    /** Limite de usuários vigente (override do escritório ou do plano). */
    public Integer maxUsers(Office office) {
        return compute(office).maxUsers();
    }
}
