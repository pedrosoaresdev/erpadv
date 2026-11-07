package br.com.erpadv.services;

import br.com.erpadv.audit.AuditLog;
import br.com.erpadv.audit.AuditService;
import br.com.erpadv.dto.ContractDtos.ContractRequest;
import br.com.erpadv.dto.ContractDtos.ContractResponse;
import br.com.erpadv.dto.ContractDtos.FieldInfo;
import br.com.erpadv.dto.ContractDtos.GenerateResponse;
import br.com.erpadv.dto.ContractDtos.StageRequest;
import br.com.erpadv.dto.ContractDtos.TemplateRequest;
import br.com.erpadv.dto.ContractDtos.TemplateResponse;
import br.com.erpadv.dto.CursorPage;
import br.com.erpadv.dto.DocumentDtos.DocumentMeta;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.firebase.CryptoService;
import br.com.erpadv.firebase.SearchTokens;
import br.com.erpadv.models.Address;
import br.com.erpadv.models.Client;
import br.com.erpadv.models.ClientType;
import br.com.erpadv.models.Contract;
import br.com.erpadv.models.ContractEnums.ContractStatus;
import br.com.erpadv.models.ContractEnums.FeeModel;
import br.com.erpadv.models.ContractTemplate;
import br.com.erpadv.models.DocumentCategory;
import br.com.erpadv.models.FinanceCategory;
import br.com.erpadv.models.FinancialTransaction;
import br.com.erpadv.models.LegalCase;
import br.com.erpadv.models.Office;
import br.com.erpadv.models.StoredDocument;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.repositories.ClientRepository;
import br.com.erpadv.repositories.ContractRepositories;
import br.com.erpadv.repositories.OfficeRepository;
import br.com.erpadv.repositories.UserRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Permission;
import br.com.erpadv.services.generation.DocumentExporter;
import br.com.erpadv.services.generation.SystemTemplates;
import br.com.erpadv.services.generation.TemplateRenderer;
import br.com.erpadv.services.generation.ValorExtenso;
import br.com.erpadv.validation.BrazilianDocuments;
import com.google.cloud.firestore.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Contratos e honorários: cadastro, modelos com campos dinâmicos, geração de minuta, exportação PDF/DOCX,
 * contrato assinado, ativação (gera contas a receber), faturamento por etapas e de êxito.
 */
@Service
public class ContractService {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", PT_BR);
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ContractRepositories.Contracts contracts;
    private final ContractRepositories.Templates templates;
    private final ClientRepository clients;
    private final UserRepository users;
    private final OfficeRepository offices;
    private final CaseService cases;
    private final FinanceService finance;
    private final DocumentService documents;
    private final CryptoService crypto;
    private final AuditService audit;

    public ContractService(ContractRepositories.Contracts contracts, ContractRepositories.Templates templates,
                           ClientRepository clients, UserRepository users, OfficeRepository offices, CaseService cases,
                           FinanceService finance, DocumentService documents, CryptoService crypto, AuditService audit) {
        this.contracts = contracts;
        this.templates = templates;
        this.clients = clients;
        this.users = users;
        this.offices = offices;
        this.cases = cases;
        this.finance = finance;
        this.documents = documents;
        this.crypto = crypto;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ consultas

    public CursorPage<ContractResponse> list(AuthenticatedUser user, ContractStatus status, String clientId,
                                             String search, boolean expiring, String cursor, int size) {
        LocalDate today = FinanceService.today();
        Query q = contracts.collection(user.requireOffice());
        if (expiring) {
            q = q.whereEqualTo("status", ContractStatus.ATIVO.name())
                    .whereGreaterThanOrEqualTo("endDate", today.toString())
                    .whereLessThanOrEqualTo("endDate", today.plusDays(30).toString());
            return contracts.page(q, "endDate", Query.Direction.ASCENDING, cursor, size)
                    .map(c -> ContractResponse.of(c, today, false));
        }
        if (status != null) {
            q = q.whereEqualTo("status", status.name());
        }
        if (clientId != null && !clientId.isBlank()) {
            q = q.whereEqualTo("clientId", clientId);
        }
        if (search != null && !search.isBlank()) {
            String token = search.toUpperCase(Locale.ROOT).startsWith("CT-")
                    ? "n:" + search.trim().toUpperCase(Locale.ROOT) : SearchTokens.queryToken(search);
            if (token == null) {
                return new CursorPage<>(List.of(), null);
            }
            q = q.whereArrayContains("searchTokens", token);
        }
        return contracts.page(q, "createdAt", Query.Direction.DESCENDING, cursor, size)
                .map(c -> ContractResponse.of(c, today, false));
    }

    public ContractResponse get(AuthenticatedUser user, String id) {
        return ContractResponse.of(load(user, id), FinanceService.today(), true);
    }

    public CursorPage<AuditLog> history(AuthenticatedUser user, String id, String cursor, int size) {
        load(user, id);
        return audit.list(user.officeId(), "contract", id, null, cursor, size);
    }

    // ------------------------------------------------------------------ cadastro

    public ContractResponse create(AuthenticatedUser user, ContractRequest req) {
        String officeId = user.requireOffice();
        Contract c = new Contract();
        c.setNumber(contracts.nextNumber(officeId));
        c.setStatus(ContractStatus.RASCUNHO);
        apply(user, c, req, true);
        Instant now = Instant.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        c.setCreatedBy(user.uid());
        c.setUpdatedBy(user.uid());
        contracts.save(officeId, c);
        audit.record(user, "CONTRACT_CREATED", "contract", c.getId(), c.getNumber() + " — " + c.getTitle(),
                Map.of("feeModel", c.getFeeModel().name(), "clientId", c.getClientId()));
        return ContractResponse.of(c, FinanceService.today(), true);
    }

    public ContractResponse update(AuthenticatedUser user, String id, ContractRequest req) {
        Contract c = load(user, id);
        if (c.getStatus() == ContractStatus.CANCELADO || c.getStatus() == ContractStatus.ENCERRADO) {
            throw new BusinessException("CONTRACT_CLOSED", "Contrato encerrado ou cancelado não pode ser alterado.");
        }
        apply(user, c, req, c.getStatus() == ContractStatus.RASCUNHO);
        touch(user, c);
        contracts.save(user.officeId(), c);
        audit.record(user, "CONTRACT_UPDATED", "contract", id, "Contrato atualizado: " + c.getNumber());
        return ContractResponse.of(c, FinanceService.today(), true);
    }

    /** Ativa o contrato e gera as contas a receber dos honorários fixos/parcelados/mensais. */
    public ContractResponse activate(AuthenticatedUser user, String id) {
        Contract c = load(user, id);
        if (c.getStatus() != ContractStatus.RASCUNHO) {
            throw new BusinessException("CONTRACT_NOT_DRAFT", "Somente contratos em rascunho podem ser ativados.");
        }
        List<FinancialTransaction> items = new ArrayList<>();
        switch (c.getFeeModel()) {
            case FIXO -> items.add(receivable(c, FinanceCategory.HONORARIOS, c.getAmountCents(), c.getFirstDueDate(),
                    "Honorários — " + c.getNumber()));
            case PARCELADO -> {
                int n = c.getInstallments();
                long base = c.getAmountCents() / n;
                long rest = c.getAmountCents() - base * n;
                for (int i = 0; i < n; i++) {
                    FinancialTransaction t = receivable(c, FinanceCategory.HONORARIOS, i == 0 ? base + rest : base,
                            c.getFirstDueDate().plusMonths(i), "Honorários — " + c.getNumber() + " (parcela " + (i + 1) + "/" + n + ")");
                    t.setGroupId(c.getId());
                    t.setInstallmentNumber(i + 1);
                    t.setInstallmentCount(n);
                    items.add(t);
                }
            }
            case MENSAL -> {
                for (int i = 0; i < c.getMonths(); i++) {
                    FinancialTransaction t = receivable(c, FinanceCategory.CONTRATO, c.getMonthlyCents(),
                            c.getFirstDueDate().plusMonths(i), "Partido mensal — " + c.getNumber());
                    t.setGroupId(c.getId());
                    t.setInstallmentNumber(i + 1);
                    t.setInstallmentCount(c.getMonths());
                    t.setRecurrent(true);
                    items.add(t);
                }
            }
            case ETAPAS, EXITO -> { }
        }
        finance.createForContract(user, items);
        c.setStatus(ContractStatus.ATIVO);
        c.setActivatedAt(Instant.now());
        touch(user, c);
        contracts.save(user.officeId(), c);
        audit.record(user, "CONTRACT_ACTIVATED", "contract", id,
                c.getNumber() + " ativado — " + items.size() + " conta(s) a receber gerada(s)");
        return ContractResponse.of(c, FinanceService.today(), true);
    }

    public ContractResponse billStage(AuthenticatedUser user, String id, String stageId) {
        Contract c = requireActive(user, id);
        Contract.FeeStage stage = c.getStages().stream().filter(s -> s.getId().equals(stageId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Etapa"));
        if (stage.isBilled()) {
            throw new BusinessException("STAGE_BILLED", "Etapa já faturada.");
        }
        LocalDate due = stage.getDueDate() == null || stage.getDueDate().isBefore(FinanceService.today())
                ? FinanceService.today().plusDays(10) : stage.getDueDate();
        FinancialTransaction t = receivable(c, FinanceCategory.HONORARIOS, stage.getAmountCents(), due,
                "Honorários — " + c.getNumber() + " · etapa: " + stage.getDescription());
        finance.createForContract(user, List.of(t));
        stage.setBilled(true);
        stage.setTransactionId(t.getId());
        touch(user, c);
        contracts.save(user.officeId(), c);
        audit.record(user, "CONTRACT_STAGE_BILLED", "contract", id, "Etapa faturada: " + stage.getDescription());
        return ContractResponse.of(c, FinanceService.today(), true);
    }

    public ContractResponse registerSuccess(AuthenticatedUser user, String id, long obtainedCents, LocalDate dueDate, String note) {
        Contract c = requireActive(user, id);
        if (c.getSuccessPercentBp() == null) {
            throw new BusinessException("NO_SUCCESS_FEE", "O contrato não prevê honorários de êxito.");
        }
        if (c.isSuccessBilled()) {
            throw new BusinessException("SUCCESS_BILLED", "Honorários de êxito já faturados.");
        }
        long amount = successAmount(obtainedCents, c.getSuccessPercentBp());
        if (amount <= 0) {
            throw new BusinessException("INVALID_AMOUNT", "Valor de êxito resultou em zero.");
        }
        FinancialTransaction t = receivable(c, FinanceCategory.HONORARIOS_EXITO, amount, dueDate,
                "Honorários de êxito — " + c.getNumber() + " (" + percent(c.getSuccessPercentBp()) + ")");
        t.setNotes("Proveito econômico: " + FinanceService.money(obtainedCents) + (note == null || note.isBlank() ? "" : " · " + note.trim()));
        finance.createForContract(user, List.of(t));
        c.setSuccessBilled(true);
        c.setSuccessTransactionId(t.getId());
        touch(user, c);
        contracts.save(user.officeId(), c);
        audit.record(user, "CONTRACT_SUCCESS_BILLED", "contract", id, "Êxito faturado: " + FinanceService.money(amount),
                Map.of("obtained", FinanceService.money(obtainedCents)));
        return ContractResponse.of(c, FinanceService.today(), true);
    }

    public ContractResponse close(AuthenticatedUser user, String id) {
        Contract c = requireActive(user, id);
        c.setStatus(ContractStatus.ENCERRADO);
        c.setClosedAt(Instant.now());
        touch(user, c);
        contracts.save(user.officeId(), c);
        audit.record(user, "CONTRACT_CLOSED", "contract", id, "Contrato encerrado: " + c.getNumber());
        return ContractResponse.of(c, FinanceService.today(), true);
    }

    public ContractResponse cancel(AuthenticatedUser user, String id, String reason) {
        Contract c = load(user, id);
        if (c.getStatus() == ContractStatus.CANCELADO || c.getStatus() == ContractStatus.ENCERRADO) {
            throw new BusinessException("CONTRACT_CLOSED", "Contrato já encerrado ou cancelado.");
        }
        int canceled = c.getStatus() == ContractStatus.ATIVO
                ? finance.cancelOpenByContract(user, id, "Contrato " + c.getNumber() + " cancelado: " + reason.trim()) : 0;
        c.setStatus(ContractStatus.CANCELADO);
        c.setCancelReason(reason.trim());
        c.setClosedAt(Instant.now());
        touch(user, c);
        contracts.save(user.officeId(), c);
        audit.record(user, "CONTRACT_CANCELED", "contract", id, "Contrato cancelado: " + c.getNumber(),
                Map.of("reason", reason.trim(), "receivablesCanceled", String.valueOf(canceled)));
        return ContractResponse.of(c, FinanceService.today(), true);
    }

    // ------------------------------------------------------------------ minuta e exportação

    public GenerateResponse generate(AuthenticatedUser user, String id, String templateId) {
        Contract c = load(user, id);
        ContractTemplate template = template(user, templateId);
        TemplateRenderer.Rendered rendered = TemplateRenderer.render(template.getBody(), values(user, c));
        c.setBody(rendered.text());
        c.setTemplateId(templateId);
        c.setBodyGeneratedAt(Instant.now());
        touch(user, c);
        contracts.save(user.officeId(), c);
        audit.record(user, "CONTRACT_DRAFT_GENERATED", "contract", id, "Minuta gerada a partir de: " + template.getName(),
                Map.of("missing", String.join(",", rendered.missing())));
        return new GenerateResponse(rendered.text(), rendered.missing());
    }

    public ContractResponse saveBody(AuthenticatedUser user, String id, String body) {
        Contract c = load(user, id);
        c.setBody(body);
        touch(user, c);
        contracts.save(user.officeId(), c);
        audit.record(user, "CONTRACT_DRAFT_EDITED", "contract", id, "Minuta editada: " + c.getNumber());
        return ContractResponse.of(c, FinanceService.today(), true);
    }

    public record Exported(byte[] bytes, String fileName, String contentType) {
    }

    public Exported export(AuthenticatedUser user, String id, String format) {
        Contract c = load(user, id);
        if (c.getBody() == null || c.getBody().isBlank()) {
            throw new BusinessException("NO_DRAFT", "Gere ou escreva a minuta antes de exportar.");
        }
        boolean docx = "docx".equalsIgnoreCase(format);
        byte[] bytes = docx ? DocumentExporter.toDocx(c.getBody())
                : DocumentExporter.toPdf(c.getBody(), c.getNumber());
        audit.record(user, "CONTRACT_EXPORTED", "contract", id, c.getNumber() + " exportado em " + (docx ? "DOCX" : "PDF"));
        return new Exported(bytes, c.getNumber() + (docx ? ".docx" : ".pdf"),
                docx ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document" : "application/pdf");
    }

    /** Exporta a minuta e arquiva no repositório de documentos do cliente/processo. */
    public String exportToDocuments(AuthenticatedUser user, String id, String format) {
        Exported file = export(user, id, format);
        Contract c = load(user, id);
        StoredDocument d = documents.store(user, new DocumentMeta("Minuta " + c.getNumber() + " — " + c.getTitle(),
                "Gerada pelo sistema a partir do contrato " + c.getNumber(), DocumentCategory.CONTRATO,
                c.getClientId(), c.getCaseId(), false, false), file.bytes(), file.fileName(), c.getId());
        return d.getId();
    }

    public ContractResponse uploadSigned(AuthenticatedUser user, String id, byte[] bytes, String name) {
        Contract c = load(user, id);
        StoredDocument d = documents.store(user, new DocumentMeta("Contrato " + c.getNumber() + " assinado",
                c.getTitle(), DocumentCategory.CONTRATO, c.getClientId(), c.getCaseId(), false, false), bytes, name, c.getId());
        c.setSignedDocumentId(d.getId());
        c.setSignedAt(Instant.now());
        touch(user, c);
        contracts.save(user.officeId(), c);
        audit.record(user, "CONTRACT_SIGNED_UPLOADED", "contract", id, "Contrato assinado anexado (documento " + d.getId() + ")");
        return ContractResponse.of(c, FinanceService.today(), true);
    }

    // ------------------------------------------------------------------ modelos

    public List<TemplateResponse> templates(AuthenticatedUser user) {
        List<ContractTemplate> office = templates.list(templates.collection(user.requireOffice()).orderBy("name"), 200);
        return Stream.concat(SystemTemplates.all().stream(), office.stream()).map(TemplateResponse::of).toList();
    }

    public TemplateResponse saveTemplate(AuthenticatedUser user, String id, TemplateRequest req) {
        String officeId = user.requireOffice();
        ContractTemplate t;
        if (id == null) {
            t = new ContractTemplate();
            t.setCreatedAt(Instant.now());
            t.setCreatedBy(user.uid());
        } else {
            if (id.startsWith("system:")) {
                throw new BusinessException("SYSTEM_TEMPLATE", "Modelos do sistema não podem ser alterados; duplique-o.");
            }
            t = templates.findById(officeId, id).orElseThrow(() -> new NotFoundException("Modelo"));
        }
        t.setName(req.name().trim());
        t.setType(req.type());
        t.setDescription(req.description());
        t.setBody(req.body());
        t.setActive(req.active());
        t.setSystem(false);
        t.setUpdatedAt(Instant.now());
        t.setUpdatedBy(user.uid());
        templates.save(officeId, t);
        audit.record(user, id == null ? "TEMPLATE_CREATED" : "TEMPLATE_UPDATED", "contract_template", t.getId(), t.getName());
        return TemplateResponse.of(t);
    }

    public void deleteTemplate(AuthenticatedUser user, String id) {
        if (id.startsWith("system:")) {
            throw new BusinessException("SYSTEM_TEMPLATE", "Modelos do sistema não podem ser excluídos.");
        }
        ContractTemplate t = templates.findById(user.requireOffice(), id).orElseThrow(() -> new NotFoundException("Modelo"));
        templates.delete(user.officeId(), id);
        audit.record(user, "TEMPLATE_DELETED", "contract_template", id, t.getName());
    }

    public static List<FieldInfo> fieldCatalog() {
        return List.of(
                new FieldInfo("cliente.nome", "Nome / razão social"), new FieldInfo("cliente.tipoDocumento", "CPF ou CNPJ"),
                new FieldInfo("cliente.documento", "Número do CPF/CNPJ (exige permissão de dados sensíveis)"),
                new FieldInfo("cliente.qualificacao", "Estado civil, profissão e RG"), new FieldInfo("cliente.rg", "RG"),
                new FieldInfo("cliente.endereco", "Endereço completo"), new FieldInfo("cliente.email", "E-mail"),
                new FieldInfo("cliente.telefone", "Telefone"), new FieldInfo("escritorio.nome", "Nome do escritório"),
                new FieldInfo("escritorio.cnpj", "CNPJ do escritório"), new FieldInfo("escritorio.cidade", "Cidade"),
                new FieldInfo("escritorio.uf", "UF"), new FieldInfo("advogado.nome", "Advogado responsável"),
                new FieldInfo("advogado.oab", "OAB do responsável"), new FieldInfo("processo.numero", "Número CNJ"),
                new FieldInfo("processo.vara", "Vara"), new FieldInfo("processo.comarca", "Comarca"),
                new FieldInfo("processo.tribunal", "Tribunal"), new FieldInfo("processo.parteContraria", "Parte contrária"),
                new FieldInfo("contrato.numero", "Número do contrato"), new FieldInfo("contrato.objeto", "Objeto"),
                new FieldInfo("contrato.modeloHonorarios", "Modalidade de honorários"),
                new FieldInfo("contrato.valor", "Valor total (R$)"), new FieldInfo("contrato.valorExtenso", "Valor por extenso"),
                new FieldInfo("contrato.parcelas", "Quantidade de parcelas"), new FieldInfo("contrato.valorParcela", "Valor da parcela"),
                new FieldInfo("contrato.primeiroVencimento", "1º vencimento"), new FieldInfo("contrato.mensalidade", "Mensalidade"),
                new FieldInfo("contrato.mensalidadeExtenso", "Mensalidade por extenso"), new FieldInfo("contrato.meses", "Meses"),
                new FieldInfo("contrato.etapas", "Etapas e valores"), new FieldInfo("contrato.percentualExito", "Percentual de êxito"),
                new FieldInfo("contrato.baseExito", "Base do êxito"), new FieldInfo("contrato.inicio", "Início da vigência"),
                new FieldInfo("contrato.termino", "Término da vigência"), new FieldInfo("data.hoje", "Data por extenso"),
                new FieldInfo("data.cidade", "Cidade do escritório"));
    }

    // ------------------------------------------------------------------ helpers

    public Contract load(AuthenticatedUser user, String id) {
        Contract c = contracts.findById(user.requireOffice(), id).orElseThrow(() -> new NotFoundException("Contrato"));
        if (c.getCaseId() != null) {
            cases.load(user, c.getCaseId()); // respeita a restrição criminal/sigilo do processo
        }
        return c;
    }

    private Contract requireActive(AuthenticatedUser user, String id) {
        Contract c = load(user, id);
        if (c.getStatus() != ContractStatus.ATIVO) {
            throw new BusinessException("CONTRACT_NOT_ACTIVE", "Operação disponível apenas para contratos ativos.");
        }
        return c;
    }

    private ContractTemplate template(AuthenticatedUser user, String templateId) {
        if (templateId.startsWith("system:")) {
            return SystemTemplates.all().stream().filter(t -> t.getId().equals(templateId)).findFirst()
                    .orElseThrow(() -> new NotFoundException("Modelo"));
        }
        return templates.findById(user.requireOffice(), templateId).orElseThrow(() -> new NotFoundException("Modelo"));
    }

    private void apply(AuthenticatedUser user, Contract c, ContractRequest req, boolean feesEditable) {
        String officeId = user.officeId();
        Client client = clients.findById(officeId, req.clientId())
                .orElseThrow(() -> new BusinessException("INVALID_CLIENT", "Cliente não encontrado."));
        c.setClientId(client.getId());
        c.setClientName(client.getName());
        c.setCaseId(null);
        c.setCaseNumber(null);
        if (req.caseId() != null && !req.caseId().isBlank()) {
            LegalCase lc = cases.load(user, req.caseId());
            if (!client.getId().equals(lc.getClientId())) {
                throw new BusinessException("CASE_CLIENT_MISMATCH", "O processo pertence a outro cliente.");
            }
            c.setCaseId(lc.getId());
            c.setCaseNumber(lc.getCnjNumber());
        }
        UserProfile responsible = users.findInOffice(officeId, req.responsibleUid())
                .filter(u -> u.getRole() != null && u.getRole().isLawyer())
                .orElseThrow(() -> new BusinessException("INVALID_RESPONSIBLE", "O responsável deve ser um advogado do escritório."));
        c.setResponsibleUid(responsible.getId());
        c.setResponsibleName(responsible.getDisplayName());
        c.setTitle(req.title().trim());
        c.setType(req.type());
        c.setObject(req.object().trim());
        c.setStartDate(req.startDate());
        if (req.endDate() != null && req.endDate().isBefore(req.startDate())) {
            throw new BusinessException("INVALID_PERIOD", "O término deve ser posterior ao início.");
        }
        c.setEndDate(req.endDate());
        if (feesEditable) {
            applyFees(c, req);
        } else {
            Contract probe = new Contract();
            applyFees(probe, req);
            if (!feeSignature(probe).equals(feeSignature(c))) {
                throw new BusinessException("FEES_LOCKED",
                        "Os honorários de um contrato ativo não podem ser alterados. Registre um aditivo (novo contrato) ou cancele este.");
            }
        }
        Set<String> tokens = new LinkedHashSet<>();
        tokens.add("n:" + c.getNumber());
        tokens.addAll(SearchTokens.forText(c.getTitle()));
        tokens.addAll(SearchTokens.forText(c.getClientName()));
        c.setSearchTokens(new ArrayList<>(tokens));
    }

    private static void applyFees(Contract c, ContractRequest req) {
        FeeModel model = req.feeModel();
        c.setFeeModel(model);
        c.setAmountCents(null);
        c.setInstallments(null);
        c.setFirstDueDate(null);
        c.setMonthlyCents(null);
        c.setMonths(null);
        switch (model) {
            case FIXO, PARCELADO -> {
                if (req.amountCents() == null || req.firstDueDate() == null) {
                    throw new BusinessException("FEES_REQUIRED", "Informe o valor e o primeiro vencimento dos honorários.");
                }
                int n = model == FeeModel.FIXO ? 1 : req.installments() == null ? 0 : req.installments();
                if (model == FeeModel.PARCELADO && n < 2) {
                    throw new BusinessException("FEES_REQUIRED", "Informe ao menos 2 parcelas.");
                }
                c.setAmountCents(req.amountCents());
                c.setInstallments(n);
                c.setFirstDueDate(req.firstDueDate());
            }
            case MENSAL -> {
                if (req.monthlyCents() == null || req.months() == null || req.firstDueDate() == null) {
                    throw new BusinessException("FEES_REQUIRED", "Informe mensalidade, quantidade de meses e primeiro vencimento.");
                }
                c.setMonthlyCents(req.monthlyCents());
                c.setMonths(req.months());
                c.setFirstDueDate(req.firstDueDate());
            }
            case ETAPAS -> {
                if (req.stages() == null || req.stages().isEmpty()) {
                    throw new BusinessException("FEES_REQUIRED", "Cadastre ao menos uma etapa de honorários.");
                }
            }
            case EXITO -> {
                if (req.successPercentBp() == null) {
                    throw new BusinessException("FEES_REQUIRED", "Informe o percentual de êxito.");
                }
            }
        }
        List<Contract.FeeStage> stages = new ArrayList<>();
        if (model == FeeModel.ETAPAS) {
            Map<String, Contract.FeeStage> previous = new LinkedHashMap<>();
            c.getStages().forEach(s -> previous.put(s.getId(), s));
            for (StageRequest s : req.stages()) {
                Contract.FeeStage stage = s.id() != null && previous.containsKey(s.id()) ? previous.get(s.id()) : new Contract.FeeStage();
                if (stage.getId() == null) {
                    stage.setId(UUID.randomUUID().toString().substring(0, 8));
                }
                if (!stage.isBilled()) {
                    stage.setDescription(s.description().trim());
                    stage.setAmountCents(s.amountCents());
                    stage.setDueDate(s.dueDate());
                }
                stages.add(stage);
            }
        }
        c.setStages(stages);
        c.setSuccessPercentBp(req.successPercentBp());
        c.setSuccessBase(req.successPercentBp() == null ? null : trim(req.successBase()));
    }

    static String feeSignature(Contract c) {
        StringBuilder sb = new StringBuilder().append(c.getFeeModel()).append('|').append(c.getAmountCents()).append('|')
                .append(c.getInstallments()).append('|').append(c.getFirstDueDate()).append('|').append(c.getMonthlyCents())
                .append('|').append(c.getMonths()).append('|').append(c.getSuccessPercentBp());
        c.getStages().forEach(s -> sb.append('|').append(s.getDescription()).append(':').append(s.getAmountCents()));
        return sb.toString();
    }

    static long successAmount(long obtainedCents, int bp) {
        return Math.round(obtainedCents * (bp / 10000.0));
    }

    static String percent(int bp) {
        return (bp % 100 == 0 ? String.valueOf(bp / 100) : String.format(PT_BR, "%.2f", bp / 100.0)) + "%";
    }

    private FinancialTransaction receivable(Contract c, FinanceCategory category, long amount, LocalDate due, String description) {
        FinancialTransaction t = new FinancialTransaction();
        t.setCategory(category);
        t.setAmountCents(amount);
        t.setDueDate(due);
        t.setDescription(description);
        t.setClientId(c.getClientId());
        t.setClientName(c.getClientName());
        t.setCaseId(c.getCaseId());
        t.setCaseNumber(c.getCaseNumber());
        t.setContractId(c.getId());
        t.setDocumentNumber(c.getNumber());
        return t;
    }

    /** Valores dos campos dinâmicos. CPF/CNPJ completos apenas para quem tem acesso a dados sensíveis. */
    Map<String, String> values(AuthenticatedUser user, Contract c) {
        Map<String, String> v = new LinkedHashMap<>();
        String officeId = user.officeId();
        clients.findById(officeId, c.getClientId()).ifPresent(cl -> {
            v.put("cliente.nome", cl.getName());
            v.put("cliente.tipoDocumento", cl.getType() == ClientType.PJ ? "CNPJ" : "CPF");
            v.put("cliente.documento", user.has(Permission.CLIENTS_SENSITIVE_READ)
                    ? BrazilianDocuments.format(crypto.decrypt(cl.getDocumentEnc())) : null);
            String rg = cl.getRgEnc() != null && user.has(Permission.CLIENTS_SENSITIVE_READ) ? crypto.decrypt(cl.getRgEnc()) : null;
            v.put("cliente.rg", rg);
            v.put("cliente.qualificacao", cl.getType() == ClientType.PJ ? "pessoa jurídica de direito privado"
                    : String.join(", ", Stream.of(lower(cl.getMaritalStatus()), lower(cl.getProfession()),
                    rg == null ? null : "portador(a) do RG nº " + rg).filter(Objects::nonNull).toList()));
            v.put("cliente.endereco", address(cl.getAddress()));
            v.put("cliente.email", cl.getEmail());
            v.put("cliente.telefone", cl.getPhone());
        });
        Office office = offices.findById(officeId).orElse(null);
        if (office != null) {
            v.put("escritorio.nome", office.getName());
            v.put("escritorio.cnpj", office.getCnpj());
            v.put("escritorio.cidade", office.getCity());
            v.put("escritorio.uf", office.getState());
            v.put("data.cidade", office.getCity());
        }
        users.findById(c.getResponsibleUid()).ifPresent(u -> {
            v.put("advogado.nome", u.getDisplayName());
            v.put("advogado.oab", u.getOabNumber());
        });
        if (c.getCaseId() != null) {
            LegalCase lc = cases.load(user, c.getCaseId());
            v.put("processo.numero", lc.getCnjNumber());
            v.put("processo.vara", lc.getCourtDivision());
            v.put("processo.comarca", lc.getDistrict());
            v.put("processo.tribunal", lc.getCourt());
            v.put("processo.parteContraria", lc.getOpposingParty());
        }
        v.put("contrato.numero", c.getNumber());
        v.put("contrato.objeto", c.getObject());
        v.put("contrato.modeloHonorarios", c.getFeeModel().label().toLowerCase(PT_BR));
        if (c.getAmountCents() != null) {
            v.put("contrato.valor", brl(c.getAmountCents()));
            v.put("contrato.valorExtenso", ValorExtenso.reais(c.getAmountCents()));
            int n = c.getInstallments() == null ? 1 : c.getInstallments();
            v.put("contrato.parcelas", String.valueOf(n));
            v.put("contrato.valorParcela", brl(c.getAmountCents() / n));
        }
        if (c.getMonthlyCents() != null) {
            v.put("contrato.mensalidade", brl(c.getMonthlyCents()));
            v.put("contrato.mensalidadeExtenso", ValorExtenso.reais(c.getMonthlyCents()));
            v.put("contrato.meses", String.valueOf(c.getMonths()));
        }
        if (c.getFirstDueDate() != null) {
            v.put("contrato.primeiroVencimento", c.getFirstDueDate().format(SHORT_DATE));
        }
        if (!c.getStages().isEmpty()) {
            v.put("contrato.etapas", String.join("; ", c.getStages().stream()
                    .map(s -> s.getDescription() + ": " + brl(s.getAmountCents())).toList()));
            v.put("contrato.valor", brl(c.getStages().stream().mapToLong(Contract.FeeStage::getAmountCents).sum()));
            v.put("contrato.valorExtenso", ValorExtenso.reais(c.getStages().stream().mapToLong(Contract.FeeStage::getAmountCents).sum()));
        }
        if (c.getSuccessPercentBp() != null) {
            v.put("contrato.percentualExito", percent(c.getSuccessPercentBp()));
            v.put("contrato.baseExito", c.getSuccessBase() == null ? "o proveito econômico obtido" : c.getSuccessBase());
        }
        v.put("contrato.inicio", c.getStartDate().format(SHORT_DATE));
        v.put("contrato.termino", c.getEndDate() == null ? null : c.getEndDate().format(SHORT_DATE));
        v.put("data.hoje", FinanceService.today().format(LONG_DATE));
        return v;
    }

    private static String address(Address a) {
        if (a == null) {
            return null;
        }
        String line = String.join(", ", Stream.of(a.getStreet(), a.getNumber(), a.getComplement(), a.getNeighborhood())
                .filter(s -> s != null && !s.isBlank()).toList());
        String city = a.getCity() == null ? "" : a.getCity() + (a.getState() == null ? "" : "/" + a.getState());
        String result = String.join(" — ", Stream.of(line, city, a.getZipCode() == null ? null : "CEP " + a.getZipCode())
                .filter(s -> s != null && !s.isBlank()).toList());
        return result.isBlank() ? null : result;
    }

    static String brl(long cents) {
        return "R$ " + String.format(PT_BR, "%,.2f", cents / 100.0);
    }

    private static String lower(String s) {
        return s == null || s.isBlank() ? null : s.toLowerCase(PT_BR);
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void touch(AuthenticatedUser user, Contract c) {
        c.setUpdatedAt(Instant.now());
        c.setUpdatedBy(user.uid());
    }
}
