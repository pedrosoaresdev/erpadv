package br.com.erpadv.services.integrations;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.firebase.CryptoService;
import br.com.erpadv.models.IntegrationProvider;
import br.com.erpadv.models.IntegrationRun;
import br.com.erpadv.models.IntegrationSetting;
import br.com.erpadv.repositories.IntegrationRepositories;
import br.com.erpadv.security.AuthenticatedUser;
import com.google.cloud.firestore.Query;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Configurações dos conectores, segredos cifrados e histórico de sincronizações da Central de Integrações.
 */
@Service
public class IntegrationService {

    /** Campos não secretos aceitos em cada conector (o resto é ignorado). */
    private static final Map<IntegrationProvider, Set<String>> CONFIG_KEYS = Map.of(
            IntegrationProvider.DATAJUD, Set.of(),
            IntegrationProvider.DJEN, Set.of(),
            IntegrationProvider.SMTP, Set.of("host", "port", "username", "fromAddress", "fromName", "starttls"),
            IntegrationProvider.WHATSAPP, Set.of("phoneNumberId", "businessAccountId", "displayNumber"),
            IntegrationProvider.GOOGLE_CALENDAR, Set.of(),
            IntegrationProvider.ZAPSIGN, Set.of("sandbox"));

    public record ConnectorStatus(IntegrationProvider provider, String label, String category, String availability, String description,
                                  boolean enabled, boolean autoSync, String state, String stateLabel, Map<String, String> config,
                                  List<String> configuredSecrets, List<String> secretKeys, Instant lastRunAt, Instant lastSuccessAt,
                                  String lastError, Instant lastErrorAt, String webhookPath) {
    }

    public record SettingsRequest(boolean enabled, boolean autoSync, Map<String, String> config, Map<String, String> secrets) {
    }

    private final IntegrationRepositories.Settings settings;
    private final IntegrationRepositories.Runs runs;
    private final CryptoService crypto;
    private final AuditService audit;
    private final String defaultDataJudKey;
    private final boolean smtpFromEnvironment;
    private final boolean googleOAuthConfigured;

    public IntegrationService(IntegrationRepositories.Settings settings, IntegrationRepositories.Runs runs, CryptoService crypto,
                              AuditService audit, @Value("${app.integrations.datajud.api-key:}") String defaultDataJudKey,
                              @Value("${spring.mail.host:}") String smtpHost,
                              @Value("${app.integrations.google.client-id:}") String googleClientId) {
        this.settings = settings;
        this.runs = runs;
        this.crypto = crypto;
        this.audit = audit;
        this.defaultDataJudKey = defaultDataJudKey;
        this.smtpFromEnvironment = smtpHost != null && !smtpHost.isBlank();
        this.googleOAuthConfigured = googleClientId != null && !googleClientId.isBlank();
    }

    // ------------------------------------------------------------------ consulta

    public List<ConnectorStatus> catalog(AuthenticatedUser user) {
        String officeId = user.requireOffice();
        Map<String, IntegrationSetting> saved = new HashMap<>();
        settings.list(settings.collection(officeId), 50).forEach(s -> saved.put(s.getProvider().name(), s));
        List<ConnectorStatus> out = new ArrayList<>();
        for (IntegrationProvider p : IntegrationProvider.values()) {
            out.add(status(officeId, p, saved.get(p.name())));
        }
        return out;
    }

    private ConnectorStatus status(String officeId, IntegrationProvider p, IntegrationSetting s) {
        boolean enabled = s == null ? defaultOn(p) : s.isEnabled();
        boolean autoSync = s == null ? defaultAutoSync(p) : s.isAutoSync();
        List<String> configured = s == null ? List.of() : new ArrayList<>(s.getSecretsEnc().keySet());
        String state;
        if (p.availability() == IntegrationProvider.Availability.SEM_API) {
            state = "SEM_API";
        } else if (!isConfigured(p, s)) {
            state = "NAO_CONFIGURADO";
        } else if (!enabled) {
            state = "DESATIVADO";
        } else if (s != null && s.getLastErrorAt() != null && (s.getLastSuccessAt() == null || s.getLastErrorAt().isAfter(s.getLastSuccessAt()))) {
            state = "ERRO";
        } else {
            state = "ATIVO";
        }
        String label = switch (state) {
            case "SEM_API" -> "Sem API pública — importação assistida";
            case "NAO_CONFIGURADO" -> "Não configurado";
            case "DESATIVADO" -> "Desativado";
            case "ERRO" -> "Com erro";
            default -> "Ativo";
        };
        String webhook = switch (p) {
            case WHATSAPP -> "/api/hooks/whatsapp/" + officeId;
            case ZAPSIGN -> "/api/hooks/zapsign/" + officeId;
            case GOOGLE_CALENDAR -> "/api/hooks/google/callback";
            default -> null;
        };
        return new ConnectorStatus(p, p.label(), p.category(), p.availability().name(), p.description(), enabled,
                autoSync, state, label, s == null ? Map.of() : s.getConfig(), configured, p.secretKeys(),
                s == null ? null : s.getLastRunAt(), s == null ? null : s.getLastSuccessAt(), s == null ? null : s.getLastError(),
                s == null ? null : s.getLastErrorAt(), webhook);
    }

    private boolean isConfigured(IntegrationProvider p, IntegrationSetting s) {
        return switch (p) {
            case DJEN -> true;
            case DATAJUD -> (defaultDataJudKey != null && !defaultDataJudKey.isBlank()) || (s != null && s.getSecretsEnc().containsKey("apiKey"));
            case SMTP -> smtpFromEnvironment || (s != null && notBlank(s.getConfig().get("host")));
            case GOOGLE_CALENDAR -> googleOAuthConfigured;
            case WHATSAPP -> s != null && notBlank(s.getConfig().get("phoneNumberId")) && s.getSecretsEnc().containsKey("accessToken");
            case ZAPSIGN -> s != null && s.getSecretsEnc().containsKey("apiToken");
            default -> false;
        };
    }

    /** Conector pronto para uso (configurado e ativado — DataJud/DJEN/SMTP do ambiente ficam ativos por padrão). */
    public boolean active(String officeId, IntegrationProvider p) {
        IntegrationSetting s = setting(officeId, p);
        boolean enabled = s == null ? defaultOn(p) : s.isEnabled();
        return enabled && isConfigured(p, s);
    }

    /** Sem configuração salva: consultas públicas, SMTP do ambiente e Google (OAuth do servidor) ficam ativos. */
    private boolean defaultOn(IntegrationProvider p) {
        return p == IntegrationProvider.DATAJUD || p == IntegrationProvider.DJEN
                || (p == IntegrationProvider.SMTP && smtpFromEnvironment) || p == IntegrationProvider.GOOGLE_CALENDAR;
    }

    private static boolean defaultAutoSync(IntegrationProvider p) {
        return p == IntegrationProvider.DJEN || p == IntegrationProvider.DATAJUD;
    }

    public boolean autoSync(String officeId, IntegrationProvider p) {
        IntegrationSetting s = setting(officeId, p);
        return s == null ? defaultAutoSync(p) : s.isAutoSync();
    }

    public IntegrationSetting setting(String officeId, IntegrationProvider p) {
        return settings.findById(officeId, p.name()).orElse(null);
    }

    public String config(String officeId, IntegrationProvider p, String key) {
        IntegrationSetting s = setting(officeId, p);
        return s == null ? null : s.getConfig().get(key);
    }

    /** Segredo decifrado (somente uso interno no servidor). */
    public String secret(String officeId, IntegrationProvider p, String key) {
        IntegrationSetting s = setting(officeId, p);
        String enc = s == null ? null : s.getSecretsEnc().get(key);
        return enc == null ? null : crypto.decrypt(enc);
    }

    public String dataJudKey(String officeId) {
        String own = secret(officeId, IntegrationProvider.DATAJUD, "apiKey");
        return notBlank(own) ? own : defaultDataJudKey;
    }

    // ------------------------------------------------------------------ alteração

    public ConnectorStatus save(AuthenticatedUser user, IntegrationProvider p, SettingsRequest req) {
        String officeId = user.requireOffice();
        if (p.availability() == IntegrationProvider.Availability.SEM_API) {
            throw new BusinessException("NO_PUBLIC_API", p.label() + " não oferece API pública para escritórios.");
        }
        IntegrationSetting s = settings.findById(officeId, p.name()).orElseGet(() -> {
            IntegrationSetting n = new IntegrationSetting();
            n.setId(p.name());
            n.setProvider(p);
            n.setCreatedAt(Instant.now());
            n.setCreatedBy(user.uid());
            return n;
        });
        s.setEnabled(req.enabled());
        s.setAutoSync(req.autoSync());
        Map<String, String> cfg = new LinkedHashMap<>();
        Set<String> allowed = CONFIG_KEYS.getOrDefault(p, Set.of());
        if (req.config() != null) {
            req.config().forEach((k, v) -> {
                if (allowed.contains(k) && v != null && !v.isBlank()) {
                    cfg.put(k, v.trim().length() > 300 ? v.trim().substring(0, 300) : v.trim());
                }
            });
        }
        s.setConfig(cfg);
        List<String> changedSecrets = new ArrayList<>();
        if (req.secrets() != null) {
            for (var e : req.secrets().entrySet()) {
                if (!p.secretKeys().contains(e.getKey()) || e.getValue() == null) {
                    continue;
                }
                if (e.getValue().isBlank()) {
                    continue; // em branco = manter o atual
                }
                if ("__remove__".equals(e.getValue())) {
                    s.getSecretsEnc().remove(e.getKey());
                } else {
                    s.getSecretsEnc().put(e.getKey(), crypto.encrypt(e.getValue().trim()));
                }
                changedSecrets.add(e.getKey());
            }
        }
        s.setUpdatedAt(Instant.now());
        s.setUpdatedBy(user.uid());
        settings.save(officeId, s);
        audit.record(user, "INTEGRATION_UPDATED", "integration", p.name(), "Integração " + p.label() + (req.enabled() ? " ativada" : " desativada"),
                Map.of("secretsChanged", String.join(",", changedSecrets), "autoSync", String.valueOf(req.autoSync())));
        return status(officeId, p, s);
    }

    // ------------------------------------------------------------------ execuções

    public IntegrationRun start(String officeId, IntegrationProvider p, String trigger, String byName) {
        IntegrationRun r = new IntegrationRun();
        r.setId(UUID.randomUUID().toString());
        r.setProvider(p);
        r.setTrigger(trigger);
        r.setStartedAt(Instant.now());
        r.setCreatedAt(r.getStartedAt());
        r.setTriggeredByName(byName);
        runs.save(officeId, r);
        return r;
    }

    public void finish(String officeId, IntegrationRun r, String message) {
        r.setFinishedAt(Instant.now());
        r.setStatus(r.getErrors() == 0 ? IntegrationRun.Status.SUCESSO : r.getProcessed() > r.getErrors() ? IntegrationRun.Status.PARCIAL
                : IntegrationRun.Status.ERRO);
        r.setMessage(message);
        if (r.getDetails().size() > 50) {
            r.setDetails(new ArrayList<>(r.getDetails().subList(0, 50)));
        }
        runs.save(officeId, r);
        IntegrationSetting s = settings.findById(officeId, r.getProvider().name()).orElseGet(() -> {
            IntegrationSetting n = new IntegrationSetting();
            n.setId(r.getProvider().name());
            n.setProvider(r.getProvider());
            n.setEnabled(true);
            n.setAutoSync(r.getProvider() == IntegrationProvider.DJEN || r.getProvider() == IntegrationProvider.DATAJUD);
            n.setCreatedAt(Instant.now());
            return n;
        });
        s.setLastRunAt(r.getFinishedAt());
        if (r.getStatus() == IntegrationRun.Status.ERRO) {
            s.setLastError(message);
            s.setLastErrorAt(r.getFinishedAt());
        } else {
            s.setLastSuccessAt(r.getFinishedAt());
            if (r.getStatus() == IntegrationRun.Status.SUCESSO) {
                s.setLastError(null);
            }
        }
        s.setUpdatedAt(Instant.now());
        settings.save(officeId, s);
    }

    /** Erro pontual de um conector (ex.: envio de e-mail recusado) para o alerta de indisponibilidade. */
    public void recordError(String officeId, IntegrationProvider p, String message) {
        settings.findById(officeId, p.name()).ifPresent(s -> {
            s.setLastError(message);
            s.setLastErrorAt(Instant.now());
            settings.save(officeId, s);
        });
    }

    public void recordSuccess(String officeId, IntegrationProvider p) {
        settings.findById(officeId, p.name()).ifPresent(s -> {
            s.setLastSuccessAt(Instant.now());
            settings.save(officeId, s);
        });
    }

    public List<IntegrationRun> runs(AuthenticatedUser user, IntegrationProvider p) {
        Query q = runs.collection(user.requireOffice());
        q = p == null ? q.orderBy("startedAt", Query.Direction.DESCENDING) : q.whereEqualTo("provider", p.name());
        return runs.list(q, 100).stream()
                .sorted(java.util.Comparator.comparing(IntegrationRun::getStartedAt, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .limit(50).toList();
    }

    public static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    @SuppressWarnings("unused")
    private static List<IntegrationProvider> all() {
        return Arrays.asList(IntegrationProvider.values());
    }
}
