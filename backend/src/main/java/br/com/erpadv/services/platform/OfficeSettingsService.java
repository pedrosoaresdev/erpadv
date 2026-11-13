package br.com.erpadv.services.platform;

import br.com.erpadv.audit.AuditLog;
import br.com.erpadv.audit.AuditService;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.firebase.FileInspector;
import br.com.erpadv.firebase.SearchTokens;
import br.com.erpadv.firebase.StorageGateway;
import br.com.erpadv.models.Address;
import br.com.erpadv.models.AppModule;
import br.com.erpadv.models.Office;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.repositories.CaseRepository;
import br.com.erpadv.repositories.ClientRepository;
import br.com.erpadv.repositories.DocumentRepositories;
import br.com.erpadv.repositories.LegalEditorRepositories;
import br.com.erpadv.repositories.OfficeRepository;
import br.com.erpadv.repositories.UserRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.validation.BrazilianDocuments;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configurações do escritório: dados cadastrais, logotipo, módulos em uso, uso do sistema (equipe, armazenamento, registros,
 * atividades recentes) e roteiro de implantação para novos escritórios.
 */
@Service
public class OfficeSettingsService {

    private static final long MAX_LOGO = 1024L * 1024;

    public record SettingsRequest(String name, String cnpj, String email, String phone, String website, String oabRegistration,
                                  Address address) {
    }

    public record ModuleView(String code, String label, boolean inPlan, boolean enabled) {
    }

    public record Settings(String id, String name, String cnpj, String email, String phone, String website, String oabRegistration,
                           Address address, boolean hasLogo, Instant logoUpdatedAt, List<ModuleView> modules, String planCode,
                           String planName, String subscriptionStatus, java.time.LocalDate trialEndsAt) {
    }

    public record Usage(long users, Integer maxUsers, long storageBytes, Integer maxStorageMb, long clients, long cases, long documents,
                        List<AuditLog> recentActivity) {
    }

    public record Step(String key, String label, boolean done, String link) {
    }

    public record Onboarding(boolean dismissed, boolean complete, List<Step> steps) {
    }

    private final OfficeRepository offices;
    private final UserRepository users;
    private final ClientRepository clients;
    private final CaseRepository cases;
    private final DocumentRepositories.Documents documents;
    private final LegalEditorRepositories.Letterheads letterheads;
    private final StorageGateway storage;
    private final LicenseService license;
    private final AuditService audit;

    public OfficeSettingsService(OfficeRepository offices, UserRepository users, ClientRepository clients, CaseRepository cases,
                                 DocumentRepositories.Documents documents, LegalEditorRepositories.Letterheads letterheads,
                                 StorageGateway storage, LicenseService license, AuditService audit) {
        this.offices = offices;
        this.users = users;
        this.clients = clients;
        this.cases = cases;
        this.documents = documents;
        this.letterheads = letterheads;
        this.storage = storage;
        this.license = license;
        this.audit = audit;
    }

    private Office office(AuthenticatedUser user) {
        return offices.findById(user.requireOffice()).orElseThrow(() -> new NotFoundException("Escritório"));
    }

    public Settings get(AuthenticatedUser user) {
        Office o = office(user);
        LicenseService.License l = license.license(o.getId());
        List<ModuleView> modules = new ArrayList<>();
        for (AppModule m : AppModule.values()) {
            modules.add(new ModuleView(m.name(), m.label(), l.planModules().contains(m), l.modules().contains(m)));
        }
        return new Settings(o.getId(), o.getName(), o.getCnpj(), o.getEmail(), o.getPhone(), o.getWebsite(), o.getOabRegistration(),
                o.getAddress(), o.getLogoPath() != null, o.getLogoUpdatedAt(), modules, l.planCode(), l.planName(), o.getSubscriptionStatus(), o.getTrialEndsAt());
    }

    public Settings update(AuthenticatedUser user, SettingsRequest req) {
        Office o = office(user);
        Map<String, Object> before = audit.snapshot(o);
        String name = clean(req.name(), 160);
        if (name == null) {
            throw new BusinessException("NAME_REQUIRED", "Informe o nome do escritório.");
        }
        String cnpj = clean(req.cnpj(), 20);
        if (cnpj != null && !BrazilianDocuments.isValidCnpj(cnpj)) {
            throw new BusinessException("INVALID_CNPJ", "CNPJ inválido.");
        }
        String email = clean(req.email(), 150);
        if (email != null && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new BusinessException("INVALID_EMAIL", "E-mail inválido.");
        }
        String site = clean(req.website(), 200);
        if (site != null && !site.matches("^https?://[^\\s]+$")) {
            throw new BusinessException("INVALID_URL", "Informe o site com http:// ou https://.");
        }
        o.setName(name);
        o.setNameLower(SearchTokens.normalize(name));
        o.setCnpj(cnpj == null ? null : BrazilianDocuments.format(cnpj));
        o.setEmail(email);
        o.setPhone(clean(req.phone(), 30));
        o.setWebsite(site);
        o.setOabRegistration(clean(req.oabRegistration(), 60));
        Address a = req.address();
        o.setAddress(a);
        if (a != null) {
            o.setCity(clean(a.getCity(), 80));
            o.setState(a.getState() == null ? null : a.getState().trim().toUpperCase().substring(0, Math.min(2, a.getState().trim().length())));
        }
        o.setUpdatedAt(Instant.now());
        o.setUpdatedBy(user.uid());
        offices.save(o);
        audit.recordChange(user, "OFFICE_UPDATED", "office", o.getId(), "Dados do escritório atualizados", Map.of(), before, o);
        return get(user);
    }

    // ------------------------------------------------------------------ logotipo

    public Settings uploadLogo(AuthenticatedUser user, byte[] bytes, String fileName) {
        Office o = office(user);
        FileInspector.Inspection file = FileInspector.inspect(bytes, fileName == null ? "logo" : fileName, MAX_LOGO,
                EnumSet.of(FileInspector.FileKind.PNG, FileInspector.FileKind.JPEG));
        String path = "offices/" + o.getId() + "/branding/logo." + file.kind().extension;
        if (o.getLogoPath() != null && !o.getLogoPath().equals(path)) {
            try {
                storage.delete(o.getLogoPath());
            } catch (RuntimeException ignored) {
                // logotipo anterior já ausente
            }
        }
        storage.put(path, bytes, file.kind().contentType, Map.of("officeId", o.getId()));
        o.setLogoPath(path);
        o.setLogoContentType(file.kind().contentType);
        o.setLogoUpdatedAt(Instant.now());
        offices.save(o);
        audit.record(user, "OFFICE_LOGO_UPDATED", "office", o.getId(), "Logotipo do escritório atualizado");
        return get(user);
    }

    public record Logo(byte[] bytes, String contentType) {
    }

    public Logo logo(AuthenticatedUser user) {
        Office o = office(user);
        if (o.getLogoPath() == null) {
            throw new NotFoundException("Logotipo");
        }
        return new Logo(storage.get(o.getLogoPath()), o.getLogoContentType());
    }

    public Settings deleteLogo(AuthenticatedUser user) {
        Office o = office(user);
        if (o.getLogoPath() != null) {
            try {
                storage.delete(o.getLogoPath());
            } catch (RuntimeException ignored) {
                // já ausente
            }
        }
        o.setLogoPath(null);
        o.setLogoContentType(null);
        o.setLogoUpdatedAt(Instant.now());
        offices.save(o);
        audit.record(user, "OFFICE_LOGO_REMOVED", "office", o.getId(), "Logotipo do escritório removido");
        return get(user);
    }

    // ------------------------------------------------------------------ módulos

    /** O escritório pode desligar módulos do plano que não usa (somem do menu e as permissões deixam de valer). */
    public Settings modules(AuthenticatedUser user, List<String> enabled) {
        Office o = office(user);
        LicenseService.License l = license.license(o.getId());
        Set<String> on = enabled == null ? Set.of() : Set.copyOf(enabled);
        List<String> disabled = new ArrayList<>();
        for (AppModule m : l.planModules()) {
            if (!on.contains(m.name())) {
                disabled.add(m.name());
            }
        }
        Map<String, Object> before = audit.snapshot(o);
        o.setDisabledModules(disabled);
        o.setUpdatedAt(Instant.now());
        offices.save(o);
        license.evict(o.getId());
        audit.recordChange(user, "OFFICE_MODULES_UPDATED", "office", o.getId(), "Módulos em uso atualizados", Map.of(), before, o);
        return get(user);
    }

    // ------------------------------------------------------------------ uso do sistema

    public Usage usage(AuthenticatedUser user) {
        String officeId = user.requireOffice();
        LicenseService.License l = license.license(officeId);
        List<AuditLog> recent = audit.list(officeId, null, null, null, null, 15).items();
        return new Usage(users.countSeats(officeId), l.maxUsers(), license.storageUsed(officeId), l.maxStorageMb(),
                clients.count(clients.collection(officeId)), cases.count(cases.collection(officeId)),
                documents.count(documents.collection(officeId).whereEqualTo("deleted", false)), recent);
    }

    // ------------------------------------------------------------------ implantação

    public Onboarding onboarding(AuthenticatedUser user) {
        Office o = office(user);
        String officeId = o.getId();
        UserProfile me = users.findById(user.uid()).orElse(null);
        List<Step> steps = List.of(
                new Step("DADOS", "Completar os dados do escritório (CNPJ e endereço)", o.getCnpj() != null && o.getAddress() != null, "/configuracoes"),
                new Step("LOGO", "Enviar o logotipo", o.getLogoPath() != null, "/configuracoes"),
                new Step("TIMBRADO", "Cadastrar o papel timbrado", letterheads.count(letterheads.collection(officeId)) > 0, "/editor-juridico"),
                new Step("EQUIPE", "Convidar a equipe", users.countSeats(officeId) > 1, "/equipe"),
                new Step("CLIENTE", "Cadastrar o primeiro cliente", clients.count(clients.collection(officeId)) > 0, "/clientes"),
                new Step("PROCESSO", "Cadastrar o primeiro processo", cases.count(cases.collection(officeId)) > 0, "/processos"),
                new Step("SEGURANCA", "Ativar a verificação em duas etapas", me != null && me.isMfaEnabled(), "/seguranca"));
        boolean complete = steps.stream().allMatch(Step::done);
        return new Onboarding(o.isOnboardingDismissed(), complete, steps);
    }

    public Onboarding dismissOnboarding(AuthenticatedUser user) {
        Office o = office(user);
        o.setOnboardingDismissed(true);
        offices.save(o);
        return onboarding(user);
    }

    private static String clean(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
