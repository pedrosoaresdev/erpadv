package br.com.erpadv.services;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.dto.DocumentDtos.DocumentMeta;
import br.com.erpadv.dto.DocumentDtos.DocumentResponse;
import br.com.erpadv.dto.LegalEditorDtos.CreateRequest;
import br.com.erpadv.dto.LegalEditorDtos.DraftResponse;
import br.com.erpadv.dto.LegalEditorDtos.DraftSummary;
import br.com.erpadv.dto.LegalEditorDtos.FieldInfo;
import br.com.erpadv.dto.LegalEditorDtos.FieldsResponse;
import br.com.erpadv.dto.LegalEditorDtos.TemplateRequest;
import br.com.erpadv.dto.LegalEditorDtos.TemplateResponse;
import br.com.erpadv.dto.LegalEditorDtos.UpdateRequest;
import br.com.erpadv.dto.LegalEditorDtos.VersionResponse;
import br.com.erpadv.dto.LegalEditorDtos.VersionSummary;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.firebase.CryptoService;
import br.com.erpadv.firebase.SearchTokens;
import br.com.erpadv.models.Address;
import br.com.erpadv.models.Client;
import br.com.erpadv.models.ClientType;
import br.com.erpadv.models.LegalCase;
import br.com.erpadv.models.LegalDocKind;
import br.com.erpadv.models.LegalDraft;
import br.com.erpadv.models.LegalDraftVersion;
import br.com.erpadv.models.LegalTemplate;
import br.com.erpadv.models.Letterhead;
import br.com.erpadv.models.Office;
import br.com.erpadv.models.StoredDocument;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.repositories.LegalEditorRepositories;
import br.com.erpadv.repositories.OfficeRepository;
import br.com.erpadv.repositories.UserRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Permission;
import br.com.erpadv.services.generation.HtmlDocument;
import br.com.erpadv.services.generation.LegalTemplates;
import br.com.erpadv.services.generation.ReportExporter;
import br.com.erpadv.validation.BrazilianDocuments;
import com.google.cloud.firestore.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Editor Jurídico: modelos do sistema e do escritório, campos automáticos (cliente, processo, escritório e
 * advogado), histórico de versões, rascunho/final e exportação PDF/DOCX — inclusive para a central documental.
 */
@Service
public class LegalEditorService {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", PT_BR);
    private static final Pattern FIELD = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.]+)\\s*}}");
    private static final Pattern MISSING = Pattern.compile("<span class=\"field\"[^>]*>\\[([A-Za-z0-9_.]+)]</span>");

    /** Catálogo de campos automáticos exibido no editor ("Inserir campo"). */
    public static final List<FieldInfo> CATALOG = List.of(
            new FieldInfo("cliente.nome", "Nome", "Cliente"),
            new FieldInfo("cliente.qualificacao", "Qualificação (nacionalidade, estado civil, profissão, RG)", "Cliente"),
            new FieldInfo("cliente.tipoDocumento", "Tipo de documento (CPF/CNPJ)", "Cliente"),
            new FieldInfo("cliente.documento", "CPF/CNPJ", "Cliente"),
            new FieldInfo("cliente.rg", "RG", "Cliente"),
            new FieldInfo("cliente.nacionalidade", "Nacionalidade", "Cliente"),
            new FieldInfo("cliente.estadoCivil", "Estado civil", "Cliente"),
            new FieldInfo("cliente.profissao", "Profissão", "Cliente"),
            new FieldInfo("cliente.endereco", "Endereço completo", "Cliente"),
            new FieldInfo("cliente.email", "E-mail", "Cliente"),
            new FieldInfo("cliente.telefone", "Telefone", "Cliente"),
            new FieldInfo("cliente.representante", "Representante legal (PJ)", "Cliente"),
            new FieldInfo("processo.numero", "Número CNJ", "Processo"),
            new FieldInfo("processo.vara", "Vara / órgão", "Processo"),
            new FieldInfo("processo.comarca", "Comarca", "Processo"),
            new FieldInfo("processo.tribunal", "Tribunal", "Processo"),
            new FieldInfo("processo.classe", "Classe processual", "Processo"),
            new FieldInfo("processo.assunto", "Assunto", "Processo"),
            new FieldInfo("processo.parteContraria", "Parte contrária", "Processo"),
            new FieldInfo("processo.valorCausa", "Valor da causa", "Processo"),
            new FieldInfo("escritorio.nome", "Nome do escritório", "Escritório"),
            new FieldInfo("escritorio.cnpj", "CNPJ", "Escritório"),
            new FieldInfo("escritorio.cidade", "Cidade", "Escritório"),
            new FieldInfo("escritorio.uf", "UF", "Escritório"),
            new FieldInfo("escritorio.email", "E-mail", "Escritório"),
            new FieldInfo("escritorio.telefone", "Telefone", "Escritório"),
            new FieldInfo("advogado.nome", "Nome", "Advogado"),
            new FieldInfo("advogado.oab", "OAB", "Advogado"),
            new FieldInfo("advogado.email", "E-mail", "Advogado"),
            new FieldInfo("data.hoje", "Data de hoje (por extenso)", "Data"),
            new FieldInfo("data.cidade", "Cidade (local da assinatura)", "Data"));

    private final LegalEditorRepositories.Templates templates;
    private final LegalEditorRepositories.Drafts drafts;
    private final LegalEditorRepositories.Versions versions;
    private final ClientService clients;
    private final CaseService cases;
    private final OfficeRepository offices;
    private final UserRepository users;
    private final CryptoService crypto;
    private final DocumentService documents;
    private final AuditService audit;
    private final LegalEditorRepositories.Letterheads letterheads;
    private final br.com.erpadv.firebase.StorageGateway storage;

    /** Limite do arquivo do papel timbrado (imagem ou PDF). */
    static final long LETTERHEAD_MAX_BYTES = 10L * 1024 * 1024;

    public LegalEditorService(LegalEditorRepositories.Templates templates, LegalEditorRepositories.Drafts drafts,
                              LegalEditorRepositories.Versions versions, ClientService clients, CaseService cases,
                              OfficeRepository offices, UserRepository users, CryptoService crypto, DocumentService documents,
                              AuditService audit, LegalEditorRepositories.Letterheads letterheads,
                              br.com.erpadv.firebase.StorageGateway storage) {
        this.letterheads = letterheads;
        this.storage = storage;
        this.templates = templates;
        this.drafts = drafts;
        this.versions = versions;
        this.clients = clients;
        this.cases = cases;
        this.offices = offices;
        this.users = users;
        this.crypto = crypto;
        this.documents = documents;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ modelos

    public List<TemplateResponse> templates(AuthenticatedUser user) {
        List<TemplateResponse> out = new ArrayList<>();
        for (LegalTemplates.SystemTemplate t : LegalTemplates.all()) {
            out.add(new TemplateResponse(t.id(), t.name(), t.kind(), t.kind().label(), t.description(), true, t.content(), null, "ERPAdv"));
        }
        for (LegalTemplate t : templates.list(templates.collection(user.requireOffice()).orderBy("nameLower"), 300)) {
            out.add(toResponse(t));
        }
        return out;
    }

    private static TemplateResponse toResponse(LegalTemplate t) {
        return new TemplateResponse(t.getId(), t.getName(), t.getKind(), t.getKind().label(), t.getDescription(), false, t.getContent(),
                t.getUpdatedAt(), t.getCreatedByName());
    }

    public TemplateResponse saveTemplate(AuthenticatedUser user, String id, TemplateRequest req) {
        String officeId = user.requireOffice();
        LegalTemplate t = id == null ? new LegalTemplate()
                : templates.findById(officeId, id).orElseThrow(() -> new NotFoundException("Modelo"));
        t.setName(req.name().trim());
        t.setNameLower(SearchTokens.normalize(req.name()));
        t.setKind(req.kind());
        t.setDescription(blank(req.description()) ? null : req.description().trim());
        t.setContent(clean(req.content()));
        Instant now = Instant.now();
        if (id == null) {
            t.setCreatedAt(now);
            t.setCreatedBy(user.uid());
            t.setCreatedByName(user.displayName());
        }
        t.setUpdatedAt(now);
        t.setUpdatedBy(user.uid());
        templates.save(officeId, t);
        audit.record(user, id == null ? "LEGAL_TEMPLATE_CREATED" : "LEGAL_TEMPLATE_UPDATED", "legal_template", t.getId(),
                "Modelo do editor jurídico: " + t.getName());
        return toResponse(t);
    }

    public void deleteTemplate(AuthenticatedUser user, String id) {
        LegalTemplate t = templates.findById(user.requireOffice(), id).orElseThrow(() -> new NotFoundException("Modelo"));
        templates.delete(user.officeId(), id);
        audit.record(user, "LEGAL_TEMPLATE_DELETED", "legal_template", id, "Modelo excluído: " + t.getName());
    }

    // ------------------------------------------------------------------ campos automáticos

    public FieldsResponse fields(AuthenticatedUser user, String clientId, String caseId, String lawyerUid) {
        return new FieldsResponse(CATALOG, values(user, clientId, caseId, lawyerUid));
    }

    /**
     * Valores dos campos. Respeita permissões: processos restritos exigem CRIMINAL_READ e CPF/CNPJ/RG
     * completos exigem CLIENTS_SENSITIVE_READ (sem a permissão, o campo fica pendente para preenchimento manual).
     */
    Map<String, String> values(AuthenticatedUser user, String clientId, String caseId, String lawyerUid) {
        Map<String, String> v = new LinkedHashMap<>();
        String officeId = user.requireOffice();
        LegalCase lc = blank(caseId) ? null : cases.load(user, caseId);
        String effectiveClient = !blank(clientId) ? clientId : lc == null ? null : lc.getClientId();
        if (!blank(effectiveClient)) {
            Client cl = clients.load(user, effectiveClient);
            boolean sensitive = user.has(Permission.CLIENTS_SENSITIVE_READ);
            v.put("cliente.nome", cl.getName());
            v.put("cliente.tipoDocumento", cl.getType() == ClientType.PJ ? "CNPJ" : "CPF");
            v.put("cliente.documento", sensitive && cl.getDocumentEnc() != null ? BrazilianDocuments.format(crypto.decrypt(cl.getDocumentEnc())) : null);
            String rg = sensitive && cl.getRgEnc() != null ? crypto.decrypt(cl.getRgEnc()) : null;
            v.put("cliente.rg", rg);
            v.put("cliente.nacionalidade", lower(cl.getNationality()));
            v.put("cliente.estadoCivil", lower(cl.getMaritalStatus()));
            v.put("cliente.profissao", lower(cl.getProfession()));
            v.put("cliente.qualificacao", cl.getType() == ClientType.PJ ? "pessoa jurídica de direito privado"
                    + (blank(cl.getLegalRepresentative()) ? "" : ", neste ato representada por " + cl.getLegalRepresentative()
                    + (blank(cl.getLegalRepresentativeRole()) ? "" : " (" + cl.getLegalRepresentativeRole() + ")"))
                    : emptyToNull(String.join(", ", Stream.of(lower(cl.getNationality()), lower(cl.getMaritalStatus()), lower(cl.getProfession()),
                    rg == null ? null : "portador(a) do RG nº " + rg).filter(Objects::nonNull).toList())));
            v.put("cliente.endereco", address(cl.getAddress()));
            v.put("cliente.email", cl.getEmail());
            v.put("cliente.telefone", cl.getPhone());
            v.put("cliente.representante", cl.getLegalRepresentative());
        }
        if (lc != null) {
            v.put("processo.numero", lc.getCnjNumber());
            v.put("processo.vara", lc.getCourtDivision());
            v.put("processo.comarca", lc.getDistrict());
            v.put("processo.tribunal", lc.getCourt());
            v.put("processo.classe", lc.getProceduralClass());
            v.put("processo.assunto", lc.getSubject());
            v.put("processo.parteContraria", lc.getOpposingParty());
            v.put("processo.valorCausa", lc.getClaimValueCents() == null ? null
                    : "R$ " + String.format(PT_BR, "%,.2f", lc.getClaimValueCents() / 100.0));
        }
        Office office = offices.findById(officeId).orElse(null);
        if (office != null) {
            v.put("escritorio.nome", office.getName());
            v.put("escritorio.cnpj", office.getCnpj());
            v.put("escritorio.cidade", office.getCity());
            v.put("escritorio.uf", office.getState());
            v.put("escritorio.email", office.getEmail());
            v.put("escritorio.telefone", office.getPhone());
            v.put("data.cidade", office.getCity());
        }
        String lawyer = !blank(lawyerUid) ? lawyerUid : lc != null && lc.getResponsibleUid() != null ? lc.getResponsibleUid() : user.uid();
        users.findInOffice(officeId, lawyer).or(() -> users.findInOffice(officeId, user.uid())).ifPresent(u -> {
            v.put("advogado.nome", u.getDisplayName());
            v.put("advogado.oab", u.getOabNumber());
            v.put("advogado.email", u.getEmail());
            v.put("advogado.uid", u.getId());
        });
        v.put("data.hoje", FinanceService.today().format(LONG_DATE));
        return v;
    }

    /** Substitui os campos por valores escapados; pendentes ficam destacados como {@code [grupo.campo]}. */
    static String render(String html, Map<String, String> values) {
        Matcher m = FIELD.matcher(html == null ? "" : html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = values.get(key);
            String replacement = value == null || value.isBlank()
                    ? "<span class=\"field\">[" + key + "]</span>"
                    : HtmlDocument.escape(value);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static List<String> missing(String html) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = MISSING.matcher(html == null ? "" : html);
        while (m.find()) {
            out.add(m.group(1));
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ documentos

    public List<DraftSummary> list(AuthenticatedUser user, LegalDraft.Status status, String clientId, String caseId, String search) {
        String token = blank(search) ? null : SearchTokens.queryToken(search);
        Query q = drafts.collection(user.requireOffice());
        if (!blank(caseId)) {
            q = q.whereEqualTo("caseId", caseId);
        } else if (!blank(clientId)) {
            q = q.whereEqualTo("clientId", clientId);
        } else {
            q = q.orderBy("updatedAt", Query.Direction.DESCENDING);
        }
        return drafts.list(q, 500).stream()
                .filter(d -> !d.isRestricted() || user.has(Permission.CRIMINAL_READ))
                .filter(d -> status == null || status == d.getStatus())
                .filter(d -> blank(clientId) || clientId.equals(d.getClientId()))
                .filter(d -> token == null || d.getSearchTokens().contains(token))
                .sorted(Comparator.comparing(LegalDraft::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(DraftSummary::of)
                .toList();
    }

    public DraftResponse get(AuthenticatedUser user, String id) {
        LegalDraft d = load(user, id);
        return DraftResponse.of(d, missing(d.getContent()));
    }

    public DraftResponse create(AuthenticatedUser user, CreateRequest req) {
        String officeId = user.requireOffice();
        String templateId = blank(req.templateId()) ? "sys-branco" : req.templateId();
        String templateName;
        String content;
        LegalDocKind kind;
        var system = LegalTemplates.find(templateId);
        if (system.isPresent()) {
            templateName = system.get().name();
            content = system.get().content();
            kind = system.get().kind();
        } else {
            LegalTemplate t = templates.findById(officeId, templateId).orElseThrow(() -> new NotFoundException("Modelo"));
            templateName = t.getName();
            content = t.getContent();
            kind = t.getKind();
        }
        if (req.kind() != null) {
            kind = req.kind();
        }
        Map<String, String> values = values(user, req.clientId(), req.caseId(), req.lawyerUid());
        LegalDraft d = new LegalDraft();
        d.setId(UUID.randomUUID().toString());
        d.setTitle(blank(req.title()) ? kind.label() + (values.get("cliente.nome") == null ? "" : " — " + values.get("cliente.nome"))
                : req.title().trim());
        d.setKind(kind);
        d.setTemplateId(templateId);
        d.setTemplateName(templateName);
        link(user, d, req.clientId(), req.caseId());
        d.setLawyerUid(values.get("advogado.uid"));
        d.setLawyerName(values.get("advogado.nome"));
        d.setContent(clean(render(content, values)));
        String letterheadId = req.letterheadId() == null ? defaultLetterheadId(officeId)
                : req.letterheadId().isBlank() ? null : requireLetterhead(officeId, req.letterheadId()).getId();
        d.setLetterheadId(letterheadId);
        // Com papel timbrado, a identificação do escritório já está impressa na folha.
        d.setHeader(letterheadId == null ? defaultHeader(values) : null);
        d.setFooter(letterheadId == null ? defaultFooter(values) : null);
        d.setPageNumbers(true);
        Instant now = Instant.now();
        d.setCreatedAt(now);
        d.setCreatedBy(user.uid());
        d.setCreatedByName(user.displayName());
        touch(user, d);
        snapshot(user, d, "Criado a partir do modelo “" + templateName + "”");
        drafts.save(officeId, d);
        audit.record(user, "LEGAL_DOC_CREATED", "legal_document", d.getId(), "Documento criado no editor: " + d.getTitle(),
                Map.of("template", templateName));
        return DraftResponse.of(d, missing(d.getContent()));
    }

    public DraftResponse update(AuthenticatedUser user, String id, UpdateRequest req) {
        LegalDraft d = load(user, id);
        requireDraft(d);
        String content = clean(req.content());
        d.setTitle(req.title().trim());
        d.setKind(req.kind());
        d.setContent(content);
        d.setHeader(trimToNull(req.header()));
        d.setFooter(trimToNull(req.footer()));
        d.setPageNumbers(req.pageNumbers());
        if (req.letterheadId() != null) {
            d.setLetterheadId(req.letterheadId().isBlank() ? null : requireLetterhead(user.officeId(), req.letterheadId()).getId());
        }
        if (!Objects.equals(emptyToNull(req.clientId()), d.getClientId()) || !Objects.equals(emptyToNull(req.caseId()), d.getCaseId())) {
            link(user, d, req.clientId(), req.caseId());
        }
        touch(user, d);
        drafts.save(user.officeId(), d);
        return DraftResponse.of(d, missing(d.getContent()));
    }

    /** Registra uma versão no histórico (instantâneo do conteúdo atual). */
    public DraftResponse saveVersion(AuthenticatedUser user, String id, String note) {
        LegalDraft d = load(user, id);
        requireDraft(d);
        snapshot(user, d, blank(note) ? null : note.trim());
        touch(user, d);
        drafts.save(user.officeId(), d);
        audit.record(user, "LEGAL_DOC_VERSION", "legal_document", id, "Versão " + d.getVersion() + " registrada: " + d.getTitle());
        return DraftResponse.of(d, missing(d.getContent()));
    }

    public List<VersionSummary> versions(AuthenticatedUser user, String id) {
        load(user, id);
        return versions.list(versions.collection(user.officeId()).whereEqualTo("draftId", id), 500).stream()
                .sorted(Comparator.comparingInt(LegalDraftVersion::getVersion).reversed())
                .map(v -> new VersionSummary(v.getId(), v.getVersion(), v.getTitle(), v.getStatus(), v.getNote(), v.getCreatedByName(),
                        v.getCreatedAt(), HtmlDocument.plainText(v.getContent()).split("\\s+").length))
                .toList();
    }

    public VersionResponse version(AuthenticatedUser user, String id, int number) {
        load(user, id);
        return VersionResponse.of(findVersion(user, id, number));
    }

    public DraftResponse restoreVersion(AuthenticatedUser user, String id, int number) {
        LegalDraft d = load(user, id);
        requireDraft(d);
        LegalDraftVersion v = findVersion(user, id, number);
        d.setTitle(v.getTitle());
        d.setContent(v.getContent());
        d.setHeader(v.getHeader());
        d.setFooter(v.getFooter());
        d.setPageNumbers(v.isPageNumbers());
        snapshot(user, d, "Restaurado da versão " + number);
        touch(user, d);
        drafts.save(user.officeId(), d);
        audit.record(user, "LEGAL_DOC_RESTORED", "legal_document", id, "Versão " + number + " restaurada: " + d.getTitle());
        return DraftResponse.of(d, missing(d.getContent()));
    }

    public DraftResponse finalizeDraft(AuthenticatedUser user, String id) {
        LegalDraft d = load(user, id);
        requireDraft(d);
        d.setStatus(LegalDraft.Status.FINAL);
        d.setFinalizedAt(Instant.now());
        d.setFinalizedByName(user.displayName());
        snapshot(user, d, "Versão final");
        touch(user, d);
        drafts.save(user.officeId(), d);
        audit.record(user, "LEGAL_DOC_FINALIZED", "legal_document", id, "Documento finalizado: " + d.getTitle(),
                Map.of("version", String.valueOf(d.getVersion()), "pendingFields", String.valueOf(missing(d.getContent()).size())));
        return DraftResponse.of(d, missing(d.getContent()));
    }

    public DraftResponse reopen(AuthenticatedUser user, String id) {
        LegalDraft d = load(user, id);
        if (d.getStatus() != LegalDraft.Status.FINAL) {
            throw new BusinessException("NOT_FINAL", "O documento já está em rascunho.");
        }
        d.setStatus(LegalDraft.Status.RASCUNHO);
        d.setFinalizedAt(null);
        d.setFinalizedByName(null);
        touch(user, d);
        drafts.save(user.officeId(), d);
        audit.record(user, "LEGAL_DOC_REOPENED", "legal_document", id, "Documento reaberto para edição: " + d.getTitle());
        return DraftResponse.of(d, missing(d.getContent()));
    }

    public DraftResponse duplicate(AuthenticatedUser user, String id) {
        LegalDraft src = load(user, id);
        LegalDraft d = new LegalDraft();
        d.setId(UUID.randomUUID().toString());
        d.setTitle(("Cópia de " + src.getTitle()).substring(0, Math.min(200, ("Cópia de " + src.getTitle()).length())));
        d.setKind(src.getKind());
        d.setTemplateId(src.getTemplateId());
        d.setTemplateName(src.getTemplateName());
        d.setClientId(src.getClientId());
        d.setClientName(src.getClientName());
        d.setCaseId(src.getCaseId());
        d.setCaseNumber(src.getCaseNumber());
        d.setRestricted(src.isRestricted());
        d.setLawyerUid(src.getLawyerUid());
        d.setLawyerName(src.getLawyerName());
        d.setContent(src.getContent());
        d.setHeader(src.getHeader());
        d.setFooter(src.getFooter());
        d.setPageNumbers(src.isPageNumbers());
        d.setLetterheadId(src.getLetterheadId());
        d.setCreatedAt(Instant.now());
        d.setCreatedBy(user.uid());
        d.setCreatedByName(user.displayName());
        touch(user, d);
        snapshot(user, d, "Cópia de “" + src.getTitle() + "” (v" + src.getVersion() + ")");
        drafts.save(user.officeId(), d);
        audit.record(user, "LEGAL_DOC_CREATED", "legal_document", d.getId(), "Documento duplicado: " + src.getTitle());
        return DraftResponse.of(d, missing(d.getContent()));
    }

    public void delete(AuthenticatedUser user, String id) {
        LegalDraft d = load(user, id);
        requireDraft(d);
        for (LegalDraftVersion v : versions.list(versions.collection(user.officeId()).whereEqualTo("draftId", id), 500)) {
            versions.delete(user.officeId(), v.getId());
        }
        drafts.delete(user.officeId(), id);
        audit.record(user, "LEGAL_DOC_DELETED", "legal_document", id, "Rascunho excluído: " + d.getTitle(),
                Map.of("versions", String.valueOf(d.getVersion())));
    }

    // ------------------------------------------------------------------ exportação

    public ReportExporter.Exported export(AuthenticatedUser user, String id, String format) {
        LegalDraft d = load(user, id);
        ReportExporter.Exported file = render(d, format);
        audit.record(user, "LEGAL_DOC_EXPORTED", "legal_document", id, "Documento exportado (" + format + "): " + d.getTitle());
        return file;
    }

    private ReportExporter.Exported render(LegalDraft d, String format) {
        HtmlDocument.Layout layout = new HtmlDocument.Layout(d.getHeader(), d.getFooter(), d.isPageNumbers(),
                background(d.getOfficeId(), d.getLetterheadId(), "docx".equalsIgnoreCase(format)));
        String base = fileBase(d.getTitle());
        if ("docx".equalsIgnoreCase(format)) {
            return new ReportExporter.Exported(HtmlDocument.toDocx(d.getContent(), layout), base + ".docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        }
        return new ReportExporter.Exported(HtmlDocument.toPdf(d.getContent(), layout), base + ".pdf", ReportExporter.PDF);
    }

    /** Gera o arquivo e o guarda na central documental (nova versão se já tiver sido salvo antes). */
    public DocumentResponse saveToCentral(AuthenticatedUser user, String id, String format) {
        LegalDraft d = load(user, id);
        ReportExporter.Exported file = render(d, format);
        String note = (d.getStatus() == LegalDraft.Status.FINAL ? "Versão final" : "Rascunho") + " v" + d.getVersion() + " do editor jurídico";
        DocumentResponse result = null;
        if (d.getStoredDocumentId() != null) {
            try {
                result = documents.newVersion(user, d.getStoredDocumentId(), file.bytes(), file.fileName(), note);
            } catch (NotFoundException e) {
                result = null;
            } catch (BusinessException e) {
                if ("SAME_CONTENT".equals(e.getCode())) {
                    result = documents.get(user, d.getStoredDocumentId());
                } else if (!"DOCUMENT_ARCHIVED".equals(e.getCode())) {
                    throw e;
                }
            }
        }
        if (result == null) {
            DocumentMeta meta = new DocumentMeta(d.getTitle(), "Gerado no Editor Jurídico (" + d.getKind().label() + ")", d.getKind().category(),
                    d.getCaseId() == null ? d.getClientId() : null, d.getCaseId(), false, false);
            StoredDocument stored = documents.store(user, meta, file.bytes(), file.fileName(), null);
            result = DocumentResponse.of(stored, user.uid());
        }
        d.setStoredDocumentId(result.id());
        drafts.save(user.officeId(), d);
        audit.record(user, "LEGAL_DOC_STORED", "legal_document", id, "Documento salvo na central documental: " + d.getTitle(),
                Map.of("documentId", result.id(), "format", "docx".equalsIgnoreCase(format) ? "docx" : "pdf"));
        return result;
    }

    // ------------------------------------------------------------------ papel timbrado

    public List<br.com.erpadv.dto.LegalEditorDtos.LetterheadResponse> letterheads(AuthenticatedUser user) {
        return letterheads.list(letterheads.collection(user.requireOffice()).orderBy("name"), 100).stream()
                .map(br.com.erpadv.dto.LegalEditorDtos.LetterheadResponse::of).toList();
    }

    /**
     * Novo papel timbrado: imagem A4 obrigatória (PNG/JPEG — para PDFs o navegador gera a imagem da 1ª página)
     * e PDF original opcional, usado como fundo vetorial na exportação em PDF.
     */
    public br.com.erpadv.dto.LegalEditorDtos.LetterheadResponse createLetterhead(AuthenticatedUser user,
                                                                               br.com.erpadv.dto.LegalEditorDtos.LetterheadMeta meta,
                                                                               byte[] image, String imageName, byte[] pdf, String pdfName) {
        String officeId = user.requireOffice();
        var imageFile = br.com.erpadv.firebase.FileInspector.inspect(image, imageName, LETTERHEAD_MAX_BYTES,
                EnumSet.of(br.com.erpadv.firebase.FileInspector.FileKind.PNG, br.com.erpadv.firebase.FileInspector.FileKind.JPEG));
        Letterhead l = new Letterhead();
        l.setId(UUID.randomUUID().toString());
        String base = "offices/" + officeId + "/letterheads/" + l.getId() + "/";
        l.setImagePath(base + "fundo." + imageFile.kind().extension);
        l.setImageContentType(imageFile.kind().contentType);
        storage.put(l.getImagePath(), image, imageFile.kind().contentType, Map.of("officeId", officeId, "letterheadId", l.getId()));
        if (pdf != null && pdf.length > 0) {
            var pdfFile = br.com.erpadv.firebase.FileInspector.inspect(pdf, pdfName, LETTERHEAD_MAX_BYTES,
                    EnumSet.of(br.com.erpadv.firebase.FileInspector.FileKind.PDF));
            try (var reader = new com.lowagie.text.pdf.PdfReader(pdf)) {
                if (reader.getNumberOfPages() < 1) {
                    throw new BusinessException("LETTERHEAD_INVALID", "O PDF do papel timbrado não tem páginas.");
                }
            } catch (java.io.IOException | RuntimeException e) {
                if (e instanceof BusinessException be) {
                    throw be;
                }
                throw new BusinessException("LETTERHEAD_INVALID", "Não foi possível ler o PDF do papel timbrado (protegido ou corrompido).");
            }
            l.setPdfPath(base + "original.pdf");
            storage.put(l.getPdfPath(), pdf, pdfFile.kind().contentType, Map.of("officeId", officeId, "letterheadId", l.getId()));
        }
        applyMeta(l, meta);
        l.setCreatedAt(Instant.now());
        l.setCreatedBy(user.uid());
        l.setCreatedByName(user.displayName());
        l.setUpdatedAt(l.getCreatedAt());
        l.setUpdatedBy(user.uid());
        if (meta.isDefault() || letterheads.list(letterheads.collection(officeId), 1).isEmpty()) {
            makeDefault(officeId, l);
        }
        letterheads.save(officeId, l);
        audit.record(user, "LETTERHEAD_CREATED", "letterhead", l.getId(), "Papel timbrado adicionado: " + l.getName());
        return br.com.erpadv.dto.LegalEditorDtos.LetterheadResponse.of(l);
    }

    public br.com.erpadv.dto.LegalEditorDtos.LetterheadResponse updateLetterhead(AuthenticatedUser user, String id,
                                                                               br.com.erpadv.dto.LegalEditorDtos.LetterheadMeta meta) {
        String officeId = user.requireOffice();
        Letterhead l = requireLetterhead(officeId, id);
        applyMeta(l, meta);
        if (meta.isDefault()) {
            makeDefault(officeId, l);
        } else {
            l.setPrimary(false);
        }
        l.setUpdatedAt(Instant.now());
        l.setUpdatedBy(user.uid());
        letterheads.save(officeId, l);
        audit.record(user, "LETTERHEAD_UPDATED", "letterhead", id, "Papel timbrado atualizado: " + l.getName());
        return br.com.erpadv.dto.LegalEditorDtos.LetterheadResponse.of(l);
    }

    /** Exclui o papel timbrado; documentos que o usavam passam a sair em folha branca (o conteúdo não muda). */
    public void deleteLetterhead(AuthenticatedUser user, String id) {
        String officeId = user.requireOffice();
        Letterhead l = requireLetterhead(officeId, id);
        for (String path : java.util.Arrays.asList(l.getImagePath(), l.getPdfPath())) {
            if (path != null) {
                try {
                    storage.delete(path);
                } catch (RuntimeException ignored) {
                    // arquivo já ausente
                }
            }
        }
        letterheads.delete(officeId, id);
        for (LegalDraft d : drafts.list(drafts.collection(officeId).whereEqualTo("letterheadId", id), 500)) {
            d.setLetterheadId(null);
            drafts.save(officeId, d);
        }
        audit.record(user, "LETTERHEAD_DELETED", "letterhead", id, "Papel timbrado excluído: " + l.getName());
    }

    public br.com.erpadv.dto.DocumentDtos.FileContent letterheadImage(AuthenticatedUser user, String id) {
        Letterhead l = requireLetterhead(user.requireOffice(), id);
        return new br.com.erpadv.dto.DocumentDtos.FileContent(storage.get(l.getImagePath()), "papel-timbrado", l.getImageContentType());
    }

    private void applyMeta(Letterhead l, br.com.erpadv.dto.LegalEditorDtos.LetterheadMeta meta) {
        if (meta.marginTop() + meta.marginBottom() > 220) {
            throw new BusinessException("LETTERHEAD_MARGINS", "As margens superior e inferior deixam pouco espaço para o texto.");
        }
        l.setName(meta.name().trim());
        l.setMarginTop(meta.marginTop());
        l.setMarginBottom(meta.marginBottom());
        l.setMarginLeft(meta.marginLeft());
        l.setMarginRight(meta.marginRight());
    }

    private void makeDefault(String officeId, Letterhead l) {
        for (Letterhead other : letterheads.list(letterheads.collection(officeId).whereEqualTo("primary", true), 50)) {
            if (!other.getId().equals(l.getId())) {
                other.setPrimary(false);
                letterheads.save(officeId, other);
            }
        }
        l.setPrimary(true);
    }

    private String defaultLetterheadId(String officeId) {
        return letterheads.list(letterheads.collection(officeId).whereEqualTo("primary", true), 1).stream()
                .findFirst().map(Letterhead::getId).orElse(null);
    }

    private Letterhead requireLetterhead(String officeId, String id) {
        return letterheads.findById(officeId, id).orElseThrow(() -> new NotFoundException("Papel timbrado"));
    }

    /** Fundo para exportação; papel excluído = folha branca. No DOCX usa sempre a imagem. */
    private HtmlDocument.Background background(String officeId, String letterheadId, boolean docx) {
        if (letterheadId == null || officeId == null) {
            return null;
        }
        Letterhead l = letterheads.findById(officeId, letterheadId).orElse(null);
        if (l == null) {
            return null;
        }
        byte[] pdf = !docx && l.getPdfPath() != null ? storage.get(l.getPdfPath()) : null;
        byte[] image = pdf == null ? storage.get(l.getImagePath()) : null;
        return new HtmlDocument.Background(image, pdf, l.getMarginTop(), l.getMarginBottom(), l.getMarginLeft(), l.getMarginRight());
    }

    // ------------------------------------------------------------------ helpers

    LegalDraft load(AuthenticatedUser user, String id) {
        LegalDraft d = drafts.findById(user.requireOffice(), id).orElseThrow(() -> new NotFoundException("Documento"));
        if (d.isRestricted() && !user.has(Permission.CRIMINAL_READ)) {
            throw new NotFoundException("Documento");
        }
        return d;
    }

    private LegalDraftVersion findVersion(AuthenticatedUser user, String id, int number) {
        return versions.list(versions.collection(user.officeId()).whereEqualTo("draftId", id).whereEqualTo("version", number), 1)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Versão"));
    }

    private static void requireDraft(LegalDraft d) {
        if (d.getStatus() == LegalDraft.Status.FINAL) {
            throw new BusinessException("DOCUMENT_FINAL", "Documento finalizado: reabra-o para editar (a versão final permanece no histórico).");
        }
    }

    private void link(AuthenticatedUser user, LegalDraft d, String clientId, String caseId) {
        d.setClientId(null);
        d.setClientName(null);
        d.setCaseId(null);
        d.setCaseNumber(null);
        d.setRestricted(false);
        if (!blank(caseId)) {
            LegalCase lc = cases.load(user, caseId);
            d.setCaseId(lc.getId());
            d.setCaseNumber(lc.getCnjNumber());
            d.setClientId(lc.getClientId());
            d.setClientName(lc.getClientName());
            d.setRestricted(lc.isRestricted() || lc.isConfidential());
        } else if (!blank(clientId)) {
            Client cl = clients.load(user, clientId);
            d.setClientId(cl.getId());
            d.setClientName(cl.getName());
        }
    }

    private void snapshot(AuthenticatedUser user, LegalDraft d, String note) {
        d.setVersion(d.getVersion() + 1);
        LegalDraftVersion v = new LegalDraftVersion();
        v.setId(UUID.randomUUID().toString());
        v.setDraftId(d.getId());
        v.setVersion(d.getVersion());
        v.setTitle(d.getTitle());
        v.setContent(d.getContent());
        v.setHeader(d.getHeader());
        v.setFooter(d.getFooter());
        v.setPageNumbers(d.isPageNumbers());
        v.setStatus(d.getStatus());
        v.setNote(note);
        v.setCreatedAt(Instant.now());
        v.setCreatedBy(user.uid());
        v.setCreatedByName(user.displayName());
        versions.save(user.officeId(), v);
    }

    private static void touch(AuthenticatedUser user, LegalDraft d) {
        d.setTitleLower(SearchTokens.normalize(d.getTitle()));
        Set<String> tokens = new LinkedHashSet<>(SearchTokens.forText(d.getTitle()));
        tokens.addAll(SearchTokens.forText(d.getClientName()));
        tokens.addAll(SearchTokens.forText(d.getCaseNumber()));
        if (d.getKind() != null) {
            tokens.addAll(SearchTokens.forText(d.getKind().label()));
        }
        d.setSearchTokens(new ArrayList<>(tokens));
        d.setUpdatedAt(Instant.now());
        d.setUpdatedBy(user.uid());
        d.setUpdatedByName(user.displayName());
    }

    private static String clean(String html) {
        String clean = HtmlDocument.sanitize(html);
        if (clean.length() > HtmlDocument.MAX_HTML) {
            throw new BusinessException("DOCUMENT_TOO_LARGE", "O documento excede o tamanho máximo do editor. Divida-o em partes.");
        }
        return clean;
    }

    private static String defaultHeader(Map<String, String> v) {
        String name = v.get("escritorio.nome");
        if (name == null) {
            return null;
        }
        String second = String.join(" · ", Stream.of(v.get("escritorio.cnpj") == null ? null : "CNPJ " + v.get("escritorio.cnpj"),
                v.get("escritorio.cidade") == null ? null : v.get("escritorio.cidade") + (v.get("escritorio.uf") == null ? "" : "/" + v.get("escritorio.uf")))
                .filter(Objects::nonNull).toList());
        return second.isBlank() ? name.toUpperCase(PT_BR) : name.toUpperCase(PT_BR) + "\n" + second;
    }

    private static String defaultFooter(Map<String, String> v) {
        String line = String.join(" · ", Stream.of(v.get("escritorio.email"), v.get("escritorio.telefone")).filter(s -> !blank(s)).toList());
        return line.isBlank() ? null : line;
    }

    static String fileBase(String title) {
        String base = SearchTokens.normalize(title).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return (base.isBlank() ? "documento" : base.length() > 80 ? base.substring(0, 80) : base);
    }

    private static String address(Address a) {
        if (a == null) {
            return null;
        }
        String line = String.join(", ", Stream.of(a.getStreet(), a.getNumber(), a.getComplement(), a.getNeighborhood())
                .filter(s -> !blank(s)).toList());
        String city = a.getCity() == null ? "" : a.getCity() + (a.getState() == null ? "" : "/" + a.getState());
        String result = String.join(", ", Stream.of(line, city, a.getZipCode() == null ? null : "CEP " + a.getZipCode())
                .filter(s -> !blank(s)).toList());
        return result.isBlank() ? null : result;
    }

    private static String lower(String s) {
        return blank(s) ? null : s.trim().toLowerCase(PT_BR);
    }

    private static String trimToNull(String s) {
        return blank(s) ? null : s.trim();
    }

    private static String emptyToNull(String s) {
        return blank(s) ? null : s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
