package br.com.erpadv.services.integrations;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.models.IntegrationProvider;
import br.com.erpadv.models.IntegrationRun;
import br.com.erpadv.models.LegalCase;
import br.com.erpadv.models.Publication;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.repositories.CaseRepository;
import br.com.erpadv.repositories.DeadlineRepository;
import br.com.erpadv.repositories.IntegrationRepositories;
import br.com.erpadv.repositories.UserRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Permission;
import br.com.erpadv.services.FinanceService;
import br.com.erpadv.services.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.cloud.firestore.Query;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Publicações do DJEN (Diário de Justiça Eletrônico Nacional / Comunica PJe): consulta oficial pelo número da OAB de
 * cada advogado do escritório, vínculo automático ao processo pelo número CNJ e criação de prazo a partir da
 * publicação. API pública, sem credenciais.
 */
@Service
public class PublicationService {

    private static final Set<String> UFS = Set.of("AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA", "MT", "MS", "MG", "PA", "PB", "PR",
            "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SE", "SP", "TO");
    private static final Pattern UF = Pattern.compile("\\b([A-Za-z]{2})\\b");
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 10;

    public record Oab(String number, String uf) {
    }

    public record PublicationResponse(String id, long externalId, LocalDate availableDate, String court, String kind, String documentType,
                                      String organ, String className, String text, String caseNumber, String link, String oab,
                                      String lawyerUid, String lawyerName, String caseId, String clientName, Publication.Status status,
                                      String deadlineId, Instant readAt, String readByName) {
        static PublicationResponse of(Publication p) {
            return new PublicationResponse(p.getId(), p.getExternalId(), p.getAvailableDate(), p.getCourt(), p.getKind(), p.getDocumentType(),
                    p.getOrgan(), p.getClassName(), p.getText(), p.getCaseNumber(), p.getLink(), p.getOab(), p.getLawyerUid(), p.getLawyerName(),
                    p.getCaseId(), p.getClientName(), p.getStatus(), p.getDeadlineId(), p.getReadAt(), p.getReadByName());
        }
    }

    private final IntegrationRepositories.Publications publications;
    private final UserRepository users;
    private final CaseRepository cases;
    private final DeadlineRepository deadlines;
    private final IntegrationService integrations;
    private final NotificationService notifications;
    private final AuditService audit;
    private final String baseUrl;

    public PublicationService(IntegrationRepositories.Publications publications, UserRepository users, CaseRepository cases,
                              DeadlineRepository deadlines, IntegrationService integrations, NotificationService notifications,
                              AuditService audit, @Value("${app.integrations.djen.base-url:https://comunicaapi.pje.jus.br}") String baseUrl) {
        this.publications = publications;
        this.users = users;
        this.cases = cases;
        this.deadlines = deadlines;
        this.integrations = integrations;
        this.notifications = notifications;
        this.audit = audit;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** Extrai número e UF da OAB cadastrada ("PE 12345", "12.345/PE", "OAB/SP 111222"). */
    public static Oab parseOab(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String number = raw.replaceAll("\\D", "");
        String uf = null;
        Matcher m = UF.matcher(raw.replace("OAB", " ").replace("oab", " ").replaceAll("[^A-Za-z]+", " "));
        while (m.find()) {
            if (UFS.contains(m.group(1).toUpperCase())) {
                uf = m.group(1).toUpperCase();
                break;
            }
        }
        if (uf == null) {
            String letters = raw.replaceAll("[^A-Za-z]", "").toUpperCase();
            if (letters.length() >= 2 && UFS.contains(letters.substring(letters.length() - 2))) {
                uf = letters.substring(letters.length() - 2);
            }
        }
        return number.isEmpty() || uf == null ? null : new Oab(number.replaceFirst("^0+", ""), uf);
    }

    // ------------------------------------------------------------------ sincronização

    public IntegrationRun sync(String officeId, LocalDate from, LocalDate to, String trigger, String byName) {
        if (from == null || to == null || to.isBefore(from) || to.isAfter(from.plusDays(31))) {
            throw new BusinessException("INVALID_RANGE", "Consulte no máximo 31 dias por vez.");
        }
        IntegrationRun run = integrations.start(officeId, IntegrationProvider.DJEN, trigger, byName);
        int fresh = 0;
        int lawyers = 0;
        for (UserProfile u : users.activeStaff(officeId)) {
            Oab oab = parseOab(u.getOabNumber());
            if (oab == null) {
                continue;
            }
            lawyers++;
            try {
                fresh += fetchLawyer(officeId, u, oab, from, to, run);
            } catch (HttpJson.ExternalServiceException e) {
                run.setErrors(run.getErrors() + 1);
                run.getDetails().add(u.getDisplayName() + " (OAB " + oab.uf() + " " + oab.number() + "): " + e.getMessage());
                if (e.status() == 0 || e.status() >= 500) {
                    break;
                }
            }
        }
        if (lawyers == 0) {
            run.getDetails().add("Nenhum integrante com OAB cadastrada (Equipe → editar → Nº OAB).");
        }
        integrations.finish(officeId, run, lawyers + " advogado(s) consultado(s), " + fresh + " publicação(ões) nova(s)"
                + (run.getErrors() > 0 ? ", " + run.getErrors() + " erro(s)" : ""));
        return run;
    }

    private int fetchLawyer(String officeId, UserProfile u, Oab oab, LocalDate from, LocalDate to, IntegrationRun run) {
        int fresh = 0;
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode body = HttpJson.get(baseUrl + "/api/v1/comunicacao?numeroOab=" + oab.number() + "&ufOab=" + oab.uf()
                    + "&dataDisponibilizacaoInicio=" + from + "&dataDisponibilizacaoFim=" + to + "&pagina=" + page + "&itensPorPagina=" + PAGE_SIZE, Map.of());
            JsonNode items = body.path("items");
            for (JsonNode it : items) {
                run.setProcessed(run.getProcessed() + 1);
                if (upsert(officeId, u, oab, it)) {
                    fresh++;
                    run.setCreated(run.getCreated() + 1);
                }
            }
            int count = body.path("count").asInt(0);
            if (items.size() < PAGE_SIZE || page * PAGE_SIZE >= count) {
                break;
            }
            pause();
        }
        if (fresh > 0) {
            notifications.notify(officeId, u.getId(), "PUBLICATION", fresh + " nova(s) publicação(ões) no DJEN",
                    "Confira e registre os prazos em Prazos → Publicações.", "/central-judicial?aba=publicacoes");
        }
        pause();
        return fresh;
    }

    private boolean upsert(String officeId, UserProfile u, Oab oab, JsonNode it) {
        long id = it.path("id").asLong();
        if (id == 0) {
            return false;
        }
        String docId = "djen-" + id;
        if (publications.findById(officeId, docId).isPresent()) {
            return false;
        }
        if (!it.path("ativo").asBoolean(true) || !it.path("data_cancelamento").isNull() && !it.path("data_cancelamento").isMissingNode()) {
            return false;
        }
        Publication p = new Publication();
        p.setId(docId);
        p.setExternalId(id);
        p.setHash(it.path("hash").asText(null));
        try {
            p.setAvailableDate(LocalDate.parse(it.path("data_disponibilizacao").asText()));
        } catch (RuntimeException e) {
            p.setAvailableDate(FinanceService.today());
        }
        p.setCourt(it.path("siglaTribunal").asText(null));
        p.setKind(it.path("tipoComunicacao").asText(null));
        p.setDocumentType(it.path("tipoDocumento").asText(null));
        p.setOrgan(it.path("nomeOrgao").asText(null));
        p.setClassName(it.path("nomeClasse").asText(null));
        String text = it.path("texto").asText("");
        p.setText(text.length() > 20000 ? text.substring(0, 20000) : text);
        p.setCaseNumber(it.path("numeroprocessocommascara").asText(null));
        p.setCaseNumberDigits(it.path("numero_processo").asText(null));
        String link = it.path("link").asText(null);
        p.setLink(link != null && link.startsWith("https://") ? link : null);
        p.setOab(oab.uf() + " " + oab.number());
        p.setLawyerUid(u.getId());
        p.setLawyerName(u.getDisplayName());
        if (p.getCaseNumber() != null) {
            cases.list(cases.collection(officeId).whereEqualTo("cnjNumber", p.getCaseNumber()), 1).stream().findFirst().ifPresent(c -> {
                p.setCaseId(c.getId());
                p.setClientName(c.getClientName());
                p.setRestricted(c.isRestricted());
            });
        }
        p.setCreatedAt(Instant.now());
        publications.save(officeId, p);
        return true;
    }

    private static void pause() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ consulta e tratamento

    public List<PublicationResponse> list(AuthenticatedUser user, Publication.Status status, String lawyerUid, String caseId,
                                          LocalDate from, LocalDate to) {
        String officeId = user.requireOffice();
        LocalDate end = to == null ? FinanceService.today() : to;
        LocalDate start = from == null ? end.minusDays(30) : from;
        Query q = publications.collection(officeId).whereGreaterThanOrEqualTo("availableDate", start.toString())
                .whereLessThanOrEqualTo("availableDate", end.toString());
        return publications.list(q, 2000).stream()
                .filter(p -> !p.isRestricted() || user.has(Permission.CRIMINAL_READ))
                .filter(p -> status == null || status == p.getStatus())
                .filter(p -> lawyerUid == null || lawyerUid.isBlank() || lawyerUid.equals(p.getLawyerUid()))
                .filter(p -> caseId == null || caseId.isBlank() || caseId.equals(p.getCaseId()))
                .sorted(Comparator.comparing(Publication::getAvailableDate).reversed().thenComparing(Publication::getExternalId))
                .map(PublicationResponse::of).toList();
    }

    public PublicationResponse setStatus(AuthenticatedUser user, String id, Publication.Status status) {
        Publication p = load(user, id);
        p.setStatus(status);
        if (p.getReadAt() == null && status != Publication.Status.NOVA) {
            p.setReadAt(Instant.now());
            p.setReadByName(user.displayName());
        }
        p.setUpdatedAt(Instant.now());
        p.setUpdatedBy(user.uid());
        publications.save(user.officeId(), p);
        return PublicationResponse.of(p);
    }

    /** Vincula o prazo criado a partir da publicação. */
    public PublicationResponse linkDeadline(AuthenticatedUser user, String id, String deadlineId) {
        Publication p = load(user, id);
        deadlines.findById(user.officeId(), deadlineId).orElseThrow(() -> new NotFoundException("Prazo"));
        p.setDeadlineId(deadlineId);
        p.setStatus(Publication.Status.PRAZO_CRIADO);
        if (p.getReadAt() == null) {
            p.setReadAt(Instant.now());
            p.setReadByName(user.displayName());
        }
        publications.save(user.officeId(), p);
        audit.record(user, "PUBLICATION_DEADLINE", "publication", id, "Prazo criado a partir da publicação " + p.getCaseNumber());
        return PublicationResponse.of(p);
    }

    /** Vincula manualmente a um processo do escritório (quando o número não bateu automaticamente). */
    public PublicationResponse linkCase(AuthenticatedUser user, String id, String caseId) {
        Publication p = load(user, id);
        LegalCase c = cases.findById(user.officeId(), caseId).orElseThrow(() -> new NotFoundException("Processo"));
        if (c.isRestricted() && !user.has(Permission.CRIMINAL_READ)) {
            throw new NotFoundException("Processo");
        }
        p.setCaseId(c.getId());
        p.setClientName(c.getClientName());
        p.setRestricted(c.isRestricted());
        publications.save(user.officeId(), p);
        return PublicationResponse.of(p);
    }

    private Publication load(AuthenticatedUser user, String id) {
        Publication p = publications.findById(user.requireOffice(), id).orElseThrow(() -> new NotFoundException("Publicação"));
        if (p.isRestricted() && !user.has(Permission.CRIMINAL_READ)) {
            throw new NotFoundException("Publicação");
        }
        return p;
    }

    @SuppressWarnings("unused")
    private static List<String> none() {
        return new ArrayList<>();
    }
}
