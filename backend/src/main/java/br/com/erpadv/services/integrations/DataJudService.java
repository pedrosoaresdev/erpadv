package br.com.erpadv.services.integrations;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.ForbiddenException;
import br.com.erpadv.models.CaseMovement;
import br.com.erpadv.models.IntegrationProvider;
import br.com.erpadv.models.IntegrationRun;
import br.com.erpadv.models.LegalCase;
import br.com.erpadv.models.MovementType;
import br.com.erpadv.repositories.CaseMovementRepository;
import br.com.erpadv.repositories.CaseRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Permission;
import br.com.erpadv.services.CaseService;
import br.com.erpadv.services.CourtCalendarService;
import br.com.erpadv.services.NotificationService;
import br.com.erpadv.validation.BrazilianDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Consulta processual oficial do CNJ (API Pública do DataJud). Importa dados básicos e movimentações dos processos
 * pelo número CNJ, sem captura de tela nem credenciais judiciais. Processos em segredo de justiça não são expostos
 * pela API. Documentação: https://datajud-wiki.cnj.jus.br/api-publica/
 */
@Service
public class DataJudService {

    private static final String[] UF_BY_TR = {"", "ac", "al", "ap", "am", "ba", "ce", "dft", "es", "go", "ma", "mt", "ms", "mg", "pa", "pb",
            "pr", "pe", "pi", "rj", "rn", "rs", "ro", "rr", "sc", "se", "sp", "to"};
    private static final DateTimeFormatter AJUIZAMENTO = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    /** Pausa entre consultas na sincronização em lote (respeito aos limites de acesso). */
    private static final long PAUSE_MS = 400;
    private static final int MAX_PER_RUN = 300;

    public record SyncResult(String caseId, String status, int newMovements, String message) {
    }

    private final CaseRepository cases;
    private final CaseMovementRepository movements;
    private final CaseService caseService;
    private final IntegrationService integrations;
    private final NotificationService notifications;
    private final AuditService audit;
    private final String baseUrl;

    public DataJudService(CaseRepository cases, CaseMovementRepository movements, CaseService caseService, IntegrationService integrations,
                          NotificationService notifications, AuditService audit,
                          @Value("${app.integrations.datajud.base-url:https://api-publica.datajud.cnj.jus.br}") String baseUrl) {
        this.cases = cases;
        this.movements = movements;
        this.caseService = caseService;
        this.integrations = integrations;
        this.notifications = notifications;
        this.audit = audit;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** Índice do DataJud a partir do número CNJ (J = segmento da Justiça, TR = tribunal). */
    public static String alias(String cnj) {
        String d = BrazilianDocuments.digits(cnj);
        if (d == null || d.length() != 20) {
            return null;
        }
        int j = d.charAt(13) - '0';
        int tr = Integer.parseInt(d.substring(14, 16));
        return switch (j) {
            case 8 -> tr >= 1 && tr < UF_BY_TR.length ? "tj" + UF_BY_TR[tr] : null;
            case 4 -> tr >= 1 && tr <= 6 ? "trf" + tr : null;
            case 5 -> tr == 0 ? "tst" : tr <= 24 ? "trt" + tr : null;
            case 6 -> tr >= 1 && tr < UF_BY_TR.length ? "tre-" + (tr == 7 ? "df" : UF_BY_TR[tr]) : null;
            case 3 -> "stj";
            case 7 -> "stm";
            case 9 -> switch (tr) {
                case 13 -> "tjmmg";
                case 21 -> "tjmrs";
                case 26 -> "tjmsp";
                default -> null;
            };
            default -> null;
        };
    }

    // ------------------------------------------------------------------ sincronização

    public SyncResult syncCase(AuthenticatedUser user, String caseId) {
        LegalCase c = caseService.load(user, caseId);
        if (!user.has(Permission.CASES_WRITE) || (c.isRestricted() && !user.has(Permission.CRIMINAL_WRITE))) {
            throw new ForbiddenException("ACCESS_DENIED", "Sem permissão para atualizar este processo.");
        }
        if (!integrations.active(user.officeId(), IntegrationProvider.DATAJUD)) {
            throw new BusinessException("INTEGRATION_DISABLED", "A integração com o DataJud está desativada ou sem chave de acesso.");
        }
        IntegrationRun run = integrations.start(user.officeId(), IntegrationProvider.DATAJUD, "MANUAL", user.displayName());
        SyncResult r = sync(user.officeId(), c, run);
        integrations.finish(user.officeId(), run, r.message());
        audit.record(user, "CASE_SYNCED", "case", caseId, "Sincronização DataJud: " + r.message(),
                Map.of("status", r.status(), "newMovements", String.valueOf(r.newMovements())));
        return r;
    }

    /** Sincroniza os processos ativos com número CNJ (manual pela Central ou automático pelo job diário). */
    public IntegrationRun syncAll(String officeId, String trigger, String byName) {
        IntegrationRun run = integrations.start(officeId, IntegrationProvider.DATAJUD, trigger, byName);
        List<LegalCase> list = cases.list(cases.collection(officeId), 5000).stream()
                .filter(c -> c.getCnjNumber() != null && c.getStatus() != null && c.getStatus().isActive())
                .sorted(java.util.Comparator.comparing(LegalCase::getIntegrationSyncedAt, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                .limit(MAX_PER_RUN).toList();
        int newMoves = 0;
        for (LegalCase c : list) {
            SyncResult r = sync(officeId, c, run);
            newMoves += r.newMovements();
            if (r.status().equals("ERRO")) {
                // serviço fora do ar: interrompe para não insistir
                if (r.message().contains("indisponível") || r.message().contains("Credencial")) {
                    run.getDetails().add("Sincronização interrompida: " + r.message());
                    break;
                }
            }
            try {
                Thread.sleep(PAUSE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        integrations.finish(officeId, run, run.getProcessed() + " processo(s) consultado(s), " + newMoves + " nova(s) movimentação(ões)"
                + (run.getErrors() > 0 ? ", " + run.getErrors() + " com erro" : ""));
        return run;
    }

    private SyncResult sync(String officeId, LegalCase c, IntegrationRun run) {
        run.setProcessed(run.getProcessed() + 1);
        String label = c.getCnjNumber() == null ? c.getId() : c.getCnjNumber();
        String index = alias(c.getCnjNumber());
        if (index == null) {
            return mark(officeId, c, run, "ERRO", "Número CNJ ausente ou de tribunal não atendido pelo DataJud.", 0, label);
        }
        JsonNode hits;
        try {
            JsonNode body = HttpJson.post(baseUrl + "/api_publica_" + index + "/_search",
                    Map.of("Authorization", "APIKey " + integrations.dataJudKey(officeId)),
                    Map.of("query", Map.of("match", Map.of("numeroProcesso", BrazilianDocuments.digits(c.getCnjNumber()))), "size", 10));
            hits = body.path("hits").path("hits");
        } catch (HttpJson.ExternalServiceException e) {
            return mark(officeId, c, run, "ERRO", e.getMessage(), 0, label);
        }
        if (!hits.isArray() || hits.isEmpty()) {
            return mark(officeId, c, run, "NAO_ENCONTRADO", "Processo não encontrado no DataJud (pode estar em segredo de justiça ou ainda não indexado).", 0, label);
        }
        Set<String> known = new HashSet<>();
        movements.list(movements.collection(officeId).whereEqualTo("caseId", c.getId()), 5000)
                .forEach(m -> { if (m.getExternalId() != null) known.add(m.getExternalId()); });
        List<CaseMovement> fresh = new ArrayList<>();
        boolean updated = false;
        for (JsonNode hit : hits) {
            JsonNode src = hit.path("_source");
            updated |= fillCase(c, src, index);
            String grau = src.path("grau").asText("");
            for (JsonNode mv : src.path("movimentos")) {
                String key = sha(index + "|" + grau + "|" + mv.path("codigo").asText() + "|" + mv.path("dataHora").asText() + "|" + mv.path("nome").asText());
                if (!known.add(key)) {
                    continue;
                }
                fresh.add(movement(c, mv, src, grau, key));
            }
        }
        for (CaseMovement m : fresh) {
            movements.save(officeId, m);
        }
        if (!fresh.isEmpty()) {
            c.setLastMovementAt(Instant.now());
            if (c.getResponsibleUid() != null) {
                notifications.notify(officeId, c.getResponsibleUid(), "CASE_MOVEMENT",
                        fresh.size() + " nova(s) movimentação(ões) no DataJud", label + " — " + fresh.get(fresh.size() - 1).getTitle(),
                        "/processos/" + c.getId());
            }
        }
        run.setCreated(run.getCreated() + fresh.size());
        if (updated) {
            run.setUpdated(run.getUpdated() + 1);
        }
        return mark(officeId, c, run, "OK", fresh.isEmpty() ? "Sem movimentações novas." : fresh.size() + " movimentação(ões) importada(s).",
                fresh.size(), label);
    }

    private SyncResult mark(String officeId, LegalCase c, IntegrationRun run, String status, String message, int moves, String label) {
        c.setIntegrationSyncedAt(Instant.now());
        c.setIntegrationStatus(status);
        c.setIntegrationError("ERRO".equals(status) ? message : null);
        c.setIntegrationSource("DataJud (CNJ)");
        cases.save(officeId, c);
        if ("ERRO".equals(status)) {
            run.setErrors(run.getErrors() + 1);
            run.getDetails().add(label + ": " + message);
        } else if ("NAO_ENCONTRADO".equals(status)) {
            run.getDetails().add(label + ": não encontrado");
        }
        return new SyncResult(c.getId(), status, moves, message);
    }

    /** Completa somente campos vazios do processo (nunca sobrescreve o que o escritório cadastrou). */
    private static boolean fillCase(LegalCase c, JsonNode src, String index) {
        boolean changed = false;
        if (blank(c.getCourt())) {
            c.setCourt(index.toUpperCase(Locale.ROOT));
            changed = true;
        }
        String orgao = src.path("orgaoJulgador").path("nome").asText(null);
        if (blank(c.getJudgingBody()) && orgao != null) {
            c.setJudgingBody(orgao);
            changed = true;
        }
        if (blank(c.getCourtDivision()) && orgao != null) {
            c.setCourtDivision(orgao);
            changed = true;
        }
        String classe = src.path("classe").path("nome").asText(null);
        if (blank(c.getProceduralClass()) && classe != null) {
            c.setProceduralClass(classe);
            changed = true;
        }
        JsonNode assuntos = src.path("assuntos");
        if (blank(c.getSubject()) && assuntos.isArray() && !assuntos.isEmpty()) {
            JsonNode a = assuntos.get(0).isArray() ? assuntos.get(0).get(0) : assuntos.get(0);
            if (a != null && a.hasNonNull("nome")) {
                c.setSubject(a.get("nome").asText());
                changed = true;
            }
        }
        String ajuizamento = src.path("dataAjuizamento").asText(null);
        if (c.getDistributionDate() == null && ajuizamento != null && ajuizamento.length() >= 8) {
            try {
                c.setDistributionDate(ajuizamento.length() >= 14 ? LocalDateTime.parse(ajuizamento.substring(0, 14), AJUIZAMENTO).toLocalDate()
                        : LocalDate.parse(ajuizamento.substring(0, 10)));
                changed = true;
            } catch (RuntimeException ignored) {
                // formato inesperado: mantém em branco
            }
        }
        if (blank(c.getInstance())) {
            String grau = src.path("grau").asText("");
            c.setInstance(switch (grau) {
                case "G1" -> "1ª instância";
                case "G2" -> "2ª instância";
                case "JE" -> "Juizado Especial";
                case "TR" -> "Turma Recursal";
                case "SUP" -> "Tribunal Superior";
                default -> null;
            });
            changed = c.getInstance() != null || changed;
        }
        return changed;
    }

    private static CaseMovement movement(LegalCase c, JsonNode mv, JsonNode src, String grau, String key) {
        String nome = mv.path("nome").asText("Movimentação");
        List<String> complementos = new ArrayList<>();
        for (JsonNode comp : mv.path("complementosTabelados")) {
            String n = comp.path("nome").asText(null);
            if (n != null && !n.isBlank()) {
                complementos.add(n);
            }
        }
        CaseMovement m = new CaseMovement();
        m.setCaseId(c.getId());
        m.setType(type(nome));
        m.setImportant(m.getType() != MovementType.MOVIMENTACAO);
        LocalDate date;
        Instant at;
        try {
            at = OffsetDateTime.parse(mv.path("dataHora").asText()).toInstant();
        } catch (RuntimeException e) {
            at = Instant.now();
        }
        date = LocalDate.ofInstant(at, CourtCalendarService.ZONE);
        m.setDate(date);
        String title = complementos.isEmpty() ? nome : nome + " — " + String.join(", ", complementos);
        m.setTitle(title.length() > 300 ? title.substring(0, 300) : title);
        String orgao = mv.path("orgaoJulgador").path("nome").asText(src.path("orgaoJulgador").path("nome").asText(""));
        m.setDescription("Importado da API Pública do DataJud (CNJ) · " + src.path("sistema").path("nome").asText("") + " · " + grau
                + (orgao.isBlank() ? "" : " · " + orgao) + " · código TPU " + mv.path("codigo").asText());
        m.setSource("DATAJUD");
        m.setExternalId(key);
        m.setAuthorName("DataJud (CNJ)");
        m.setCreatedAt(Instant.now());
        m.setSortKey(date + "T" + String.format("%013d", at.toEpochMilli()));
        return m;
    }

    /** Tipo pela nomenclatura da Tabela Processual Unificada (sem alterar a situação do processo automaticamente). */
    static MovementType type(String nome) {
        String n = nome.toLowerCase(Locale.ROOT);
        if (n.startsWith("despacho") || n.startsWith("mero expediente")) {
            return MovementType.DESPACHO;
        }
        if (n.startsWith("decisão") || n.contains("liminar") || n.contains("antecipação de tutela") || n.contains("tutela")) {
            return MovementType.DECISAO;
        }
        if (n.contains("trânsito em julgado")) {
            return MovementType.TRANSITO_JULGADO;
        }
        if (n.startsWith("audiência") || n.contains("audiência")) {
            return MovementType.AUDIENCIA;
        }
        if (n.startsWith("suspensão") || n.startsWith("sobrestamento")) {
            return MovementType.SUSPENSAO;
        }
        if (n.startsWith("procedência") || n.startsWith("improcedência") || n.startsWith("extinção") || n.startsWith("homologação")
                || n.startsWith("julgamento") || n.contains("resolução do mérito")) {
            return MovementType.SENTENCA;
        }
        if (n.contains("acórdão") || n.startsWith("provimento") || n.startsWith("não-provimento") || n.startsWith("não provimento")) {
            return MovementType.ACORDAO;
        }
        return MovementType.MOVIMENTACAO;
    }

    private static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
