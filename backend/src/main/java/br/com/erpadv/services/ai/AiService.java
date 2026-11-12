package br.com.erpadv.services.ai;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.dto.DocumentDtos.DocumentResponse;
import br.com.erpadv.dto.DocumentDtos.FileContent;
import br.com.erpadv.dto.LegalEditorDtos.CreateRequest;
import br.com.erpadv.dto.LegalEditorDtos.DraftResponse;
import br.com.erpadv.dto.LegalEditorDtos.UpdateRequest;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.ForbiddenException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.AiConversation;
import br.com.erpadv.models.CaseMovement;
import br.com.erpadv.models.Client;
import br.com.erpadv.models.Deadline;
import br.com.erpadv.models.DeadlineStatus;
import br.com.erpadv.models.IntegrationProvider;
import br.com.erpadv.models.LegalCase;
import br.com.erpadv.models.LegalDocKind;
import br.com.erpadv.repositories.ClientRepository;
import br.com.erpadv.repositories.DeadlineRepository;
import br.com.erpadv.repositories.FirestoreRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Permission;
import br.com.erpadv.services.CaseService;
import br.com.erpadv.services.CourtCalendarService;
import br.com.erpadv.services.DocumentService;
import br.com.erpadv.services.LegalEditorService;
import br.com.erpadv.services.integrations.IntegrationService;
import com.anthropic.models.messages.Base64PdfSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * IA Jurídica: monta o contexto (processo, documentos, cliente) respeitando as permissões e o sigilo do usuário, mascara
 * CPF/CNPJ, chama o modelo e guarda a conversa para revisão humana. Nenhuma resposta é simulada: sem o conector ativo, a
 * funcionalidade fica indisponível.
 */
@Service
public class AiService {

    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final int MAX_PER_HOUR = 60;
    private static final int MAX_DOCUMENTS = 4;
    private static final long MAX_PDF_BYTES = 20L * 1024 * 1024;

    public record AskRequest(String conversationId, AiCatalog.Agent agent, AiCatalog.Task task, String message, String caseId,
                             String clientId, List<String> documentIds) {
    }

    public record EditorRequest(String title, LegalDocKind kind) {
    }

    public record Option(String value, String label, String description, boolean available) {
    }

    public record TaskOption(String value, String label, int documentsRequired, boolean caseRequired) {
    }

    public record Catalog(boolean configured, boolean enabled, String model, List<Option> agents, List<TaskOption> tasks,
                          List<String> officialDomains) {
    }

    /** Saída estruturada da extração de datas e obrigações. */
    public record DateExtraction(
            @JsonPropertyDescription("Datas, prazos, audiências, vencimentos e obrigações encontrados") List<Item> items,
            @JsonPropertyDescription("Observações curtas sobre o que não ficou claro no texto") String observations) {
    }

    public record Item(
            @JsonPropertyDescription("Data no formato AAAA-MM-DD; vazio se o texto só indicar um prazo relativo") String date,
            @JsonPropertyDescription("Horário HH:MM, se houver") String time,
            @JsonPropertyDescription("PRAZO, AUDIENCIA, VENCIMENTO, OBRIGACAO ou OUTRO") String kind,
            @JsonPropertyDescription("O que deve ser feito ou o que acontece nessa data") String description,
            @JsonPropertyDescription("Trecho literal do documento que menciona a data") String sourceQuote,
            @JsonPropertyDescription("ALTA, MEDIA ou BAIXA") String confidence) {
    }

    @Repository
    public static class Conversations extends FirestoreRepository<AiConversation> {
        public Conversations(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, AiConversation.class, "ai_conversations");
        }

        public void delete(String officeId, String id) {
            Futures.await(collection(officeId).document(id).delete());
        }
    }

    private final Conversations conversations;
    private final ClaudeGateway claude;
    private final IntegrationService integrations;
    private final CaseService cases;
    private final DeadlineRepository deadlines;
    private final ClientRepository clients;
    private final DocumentService documents;
    private final LegalEditorService editor;
    private final AuditService audit;
    private final Map<String, Deque<Long>> usage = new ConcurrentHashMap<>();

    public AiService(Conversations conversations, ClaudeGateway claude, IntegrationService integrations, CaseService cases,
                     DeadlineRepository deadlines, ClientRepository clients, DocumentService documents, LegalEditorService editor,
                     AuditService audit) {
        this.conversations = conversations;
        this.claude = claude;
        this.integrations = integrations;
        this.cases = cases;
        this.deadlines = deadlines;
        this.clients = clients;
        this.documents = documents;
        this.editor = editor;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ catálogo e conversas

    public Catalog catalog(AuthenticatedUser user) {
        String officeId = user.requireOffice();
        boolean configured = integrations.anthropicKey(officeId) != null && !integrations.anthropicKey(officeId).isBlank();
        boolean enabled = integrations.active(officeId, IntegrationProvider.ANTHROPIC);
        List<Option> agents = new ArrayList<>();
        for (AiCatalog.Agent a : AiCatalog.Agent.values()) {
            agents.add(new Option(a.name(), a.label(), a.description(), user.has(a.permission())));
        }
        List<TaskOption> tasks = new ArrayList<>();
        for (AiCatalog.Task t : AiCatalog.Task.values()) {
            tasks.add(new TaskOption(t.name(), t.label(), t.documentsRequired(), t.caseRequired()));
        }
        return new Catalog(configured, enabled, claude.model(), agents, tasks, ClaudeGateway.OFFICIAL_DOMAINS);
    }

    public List<AiConversation> mine(AuthenticatedUser user) {
        return conversations.list(conversations.collection(user.requireOffice()).whereEqualTo("uid", user.uid()), 300).stream()
                .sorted(Comparator.comparing(AiConversation::getLastMessageAt, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
    }

    public AiConversation get(AuthenticatedUser user, String id) {
        AiConversation c = conversations.findById(user.requireOffice(), id).orElseThrow(() -> new NotFoundException("Conversa"));
        if (!c.getUid().equals(user.uid())) {
            // conversas com IA são pessoais: podem conter estratégia e dados sigilosos
            throw new NotFoundException("Conversa");
        }
        return c;
    }

    public void delete(AuthenticatedUser user, String id) {
        AiConversation c = get(user, id);
        conversations.delete(user.officeId(), c.getId());
        audit.record(user, "AI_CONVERSATION_DELETED", "ai", id, "Conversa com IA excluída: " + c.getTitle());
    }

    // ------------------------------------------------------------------ pergunta

    public AiConversation ask(AuthenticatedUser user, AskRequest req) {
        String officeId = user.requireOffice();
        if (!integrations.active(officeId, IntegrationProvider.ANTHROPIC)) {
            throw new BusinessException("AI_NOT_CONFIGURED", "A IA jurídica não está ativada. O administrador pode ativá-la em Integrações → Conectores.");
        }
        String question = req.message() == null ? "" : req.message().trim();
        if (question.length() > 20_000) {
            throw new BusinessException("MESSAGE_TOO_LONG", "Pergunta longa demais.");
        }
        AiConversation conv = req.conversationId() == null || req.conversationId().isBlank() ? null : get(user, req.conversationId());
        AiCatalog.Agent agent = conv != null ? AiCatalog.Agent.valueOf(conv.getAgent()) : req.agent() == null ? AiCatalog.Agent.ASSISTENTE_JURIDICO : req.agent();
        AiCatalog.Task task = conv != null ? AiCatalog.Task.CONVERSA : req.task() == null ? AiCatalog.Task.CONVERSA : req.task();
        if (!user.has(Permission.AI_USE) || !user.has(agent.permission())) {
            throw new ForbiddenException("ACCESS_DENIED", "Seu perfil não pode usar o agente " + agent.label() + ".");
        }
        if (task == AiCatalog.Task.PESQUISA) {
            agent = AiCatalog.Agent.PESQUISADOR;
        }
        if (question.isEmpty() && task.instruction() == null) {
            throw new BusinessException("EMPTY_MESSAGE", "Escreva a pergunta.");
        }
        rateLimit(user);

        // contexto: na conversa existente, o mesmo contexto é relido (com as permissões atuais do usuário)
        String caseId = conv != null ? conv.getCaseId() : blank(req.caseId());
        String clientId = conv != null ? conv.getClientId() : blank(req.clientId());
        List<String> docIds = conv != null ? conv.getDocumentIds() : req.documentIds() == null ? List.of()
                : req.documentIds().stream().filter(x -> x != null && !x.isBlank()).distinct().toList();
        if (conv == null) {
            if (task.caseRequired() && caseId == null) {
                throw new BusinessException("CASE_REQUIRED", "Escolha o processo para esta tarefa.");
            }
            if (docIds.size() < task.documentsRequired()) {
                throw new BusinessException("DOCUMENTS_REQUIRED", "Escolha " + task.documentsRequired() + " documento(s) para esta tarefa.");
            }
            if (task == AiCatalog.Task.EXTRACAO_DATAS && docIds.isEmpty() && caseId == null) {
                throw new BusinessException("CONTEXT_REQUIRED", "Escolha um documento ou um processo para extrair as datas.");
            }
            if (docIds.size() > MAX_DOCUMENTS) {
                throw new BusinessException("TOO_MANY_DOCUMENTS", "Use no máximo " + MAX_DOCUMENTS + " documentos por conversa.");
            }
        }
        boolean pseudonymize = !"false".equalsIgnoreCase(integrations.config(officeId, IntegrationProvider.ANTHROPIC, "pseudonymize"));
        Context ctx = context(user, caseId, clientId, docIds, pseudonymize);

        if (conv == null) {
            conv = new AiConversation();
            conv.setId(UUID.randomUUID().toString());
            conv.setUid(user.uid());
            conv.setUserName(user.displayName());
            conv.setAgent(agent.name());
            conv.setTask(task.name());
            conv.setCaseId(caseId);
            conv.setCaseNumber(ctx.caseNumber);
            conv.setClientId(clientId);
            conv.setClientName(ctx.clientName);
            conv.setDocumentIds(new ArrayList<>(docIds));
            conv.setDocumentTitles(ctx.documentTitles);
            conv.setRestricted(ctx.restricted);
            conv.setTitle(title(task, question, ctx));
            conv.setCreatedAt(Instant.now());
            conv.setCreatedBy(user.uid());
        }

        String userText = (task.instruction() == null ? "" : "Tarefa: " + task.instruction() + "\n\n")
                + (question.isEmpty() ? "" : "Pergunta do usuário:\n" + question);
        AiConversation.Turn userTurn = new AiConversation.Turn();
        userTurn.setRole("user");
        userTurn.setText(question.isEmpty() ? task.label() : question);
        userTurn.setAt(Instant.now());

        String system = AiCatalog.RULES + "\nPapel: " + agent.prompt() + "\nData de hoje: "
                + java.time.LocalDate.now(CourtCalendarService.ZONE).format(BR) + ".";
        String apiKey = integrations.anthropicKey(officeId);
        AiConversation.Turn answer = new AiConversation.Turn();
        answer.setRole("assistant");
        answer.setAt(Instant.now());
        answer.setModel(claude.model());
        long in;
        long out;

        if (task == AiCatalog.Task.EXTRACAO_DATAS && conv.getMessages().isEmpty()) {
            List<ContentBlockParam> content = new ArrayList<>(ctx.blocks);
            content.add(text(userText));
            ClaudeGateway.Structured<DateExtraction> r = claude.extract(apiKey, system, content, DateExtraction.class);
            DateExtraction ex = r.value();
            List<AiConversation.ExtractedItem> items = new ArrayList<>();
            if (ex != null && ex.items() != null) {
                for (Item it : ex.items()) {
                    AiConversation.ExtractedItem e = new AiConversation.ExtractedItem();
                    e.setDate(it.date());
                    e.setTime(it.time());
                    e.setKind(it.kind());
                    e.setDescription(it.description());
                    e.setSourceQuote(it.sourceQuote());
                    e.setConfidence(it.confidence());
                    items.add(e);
                }
            }
            answer.setExtraction(items);
            answer.setText(items.isEmpty() ? "Nenhuma data ou obrigação encontrada no contexto."
                    : items.size() + " data(s) ou obrigação(ões) encontrada(s). Confira cada uma no documento antes de criar prazos."
                    + (ex != null && ex.observations() != null && !ex.observations().isBlank() ? "\n\n" + ex.observations() : ""));
            answer.setStopReason(r.stopReason());
            in = r.inputTokens();
            out = r.outputTokens();
        } else {
            // histórico: a primeira pergunta leva o contexto; as seguintes são só texto
            List<MessageParam> history = new ArrayList<>();
            List<ContentBlockParam> content = new ArrayList<>();
            List<AiConversation.Turn> past = conv.getMessages();
            if (past.isEmpty()) {
                content.addAll(ctx.blocks);
                content.add(text(userText));
            } else {
                for (int i = 0; i < past.size(); i++) {
                    AiConversation.Turn t = past.get(i);
                    if ("user".equals(t.getRole())) {
                        if (i == 0) {
                            List<ContentBlockParam> first = new ArrayList<>(ctx.blocks);
                            first.add(text(firstUserText(conv, t)));
                            history.add(MessageParam.ofUserOfContentBlockParams(first));
                        } else {
                            history.add(MessageParam.ofUser(t.getText()));
                        }
                    } else {
                        history.add(MessageParam.ofAssistant(t.getText() == null || t.getText().isBlank() ? "(sem resposta)" : t.getText()));
                    }
                }
                content.add(text(question));
            }
            boolean web = task == AiCatalog.Task.PESQUISA || agent == AiCatalog.Agent.PESQUISADOR;
            ClaudeGateway.Result r = claude.complete(apiKey, system, history, content, task.effort(), web);
            if ("refusal".equals(r.stopReason())) {
                answer.setText("A IA não respondeu a este pedido por política de segurança do provedor"
                        + (r.refusalCategory() == null ? "" : " (categoria: " + r.refusalCategory() + ")") + ". Reformule a pergunta.");
            } else {
                answer.setText(r.text().isBlank() ? "(sem resposta)" : r.text()
                        + ("max_tokens".equals(r.stopReason()) ? "\n\n[Resposta interrompida no limite de tamanho — peça para continuar.]" : ""));
            }
            for (ClaudeGateway.Citation c : r.citations()) {
                AiConversation.Source s = new AiConversation.Source();
                s.setUrl(c.url());
                s.setTitle(c.title());
                s.setCitedText(c.citedText() == null ? null : c.citedText().length() > 600 ? c.citedText().substring(0, 600) + "…" : c.citedText());
                answer.getSources().add(s);
            }
            answer.setModel(r.model());
            answer.setStopReason(r.stopReason());
            in = r.inputTokens();
            out = r.outputTokens();
        }
        conv.getMessages().add(userTurn);
        conv.getMessages().add(answer);
        conv.setInputTokens(conv.getInputTokens() + in);
        conv.setOutputTokens(conv.getOutputTokens() + out);
        conv.setLastMessageAt(Instant.now());
        conv.setUpdatedAt(Instant.now());
        conv.setUpdatedBy(user.uid());
        conversations.save(officeId, conv);
        Map<String, String> details = new TreeMap<>();
        details.put("agent", agent.name());
        details.put("task", task.name());
        details.put("model", answer.getModel() == null ? "" : answer.getModel());
        details.put("inputTokens", String.valueOf(in));
        details.put("outputTokens", String.valueOf(out));
        details.put("documents", String.valueOf(docIds.size()));
        if (caseId != null) {
            details.put("caseId", caseId);
        }
        // a auditoria registra o uso, nunca o conteúdo enviado
        audit.record(user, "AI_REQUEST", "ai", conv.getId(), agent.label() + " · " + task.label(), details);
        return conv;
    }

    private static String firstUserText(AiConversation conv, AiConversation.Turn t) {
        AiCatalog.Task task = AiCatalog.Task.valueOf(conv.getTask());
        return (task.instruction() == null ? "" : "Tarefa: " + task.instruction() + "\n\n")
                + (t.getText() == null || t.getText().equals(task.label()) ? "" : "Pergunta do usuário:\n" + t.getText());
    }

    // ------------------------------------------------------------------ revisão humana e envio ao editor

    public AiConversation review(AuthenticatedUser user, String id, int index) {
        AiConversation c = get(user, id);
        AiConversation.Turn t = turn(c, index);
        t.setReviewed(true);
        t.setReviewedByName(user.displayName());
        t.setReviewedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        conversations.save(user.officeId(), c);
        audit.record(user, "AI_OUTPUT_REVIEWED", "ai", id, "Resposta da IA revisada pelo advogado");
        return c;
    }

    /** Cria um rascunho no Editor Jurídico com a resposta (sempre rascunho, marcado como gerado por IA). */
    public DraftResponse toEditor(AuthenticatedUser user, String id, int index, EditorRequest req) {
        if (!user.has(Permission.DOCUMENTS_WRITE)) {
            throw new ForbiddenException("ACCESS_DENIED", "Sem permissão para criar documentos no Editor Jurídico.");
        }
        AiConversation c = get(user, id);
        AiConversation.Turn t = turn(c, index);
        String title = req == null || req.title() == null || req.title().isBlank() ? c.getTitle() : req.title().trim();
        LegalDocKind kind = req == null || req.kind() == null ? LegalDocKind.OUTRO : req.kind();
        DraftResponse d = editor.create(user, new CreateRequest(title.length() > 200 ? title.substring(0, 200) : title, kind, "sys-branco",
                c.getClientId(), c.getCaseId(), null));
        String html = "<p><strong>Rascunho gerado com IA — revisão obrigatória pelo advogado responsável antes de qualquer uso.</strong></p>"
                + MarkdownLite.toHtml(t.getText());
        DraftResponse saved = editor.update(user, d.id(), new UpdateRequest(d.title(), d.kind(), html, d.header(), d.footer(), d.pageNumbers(),
                d.clientId(), d.caseId(), d.letterheadId()));
        t.setDraftId(saved.id());
        conversations.save(user.officeId(), c);
        audit.record(user, "AI_OUTPUT_TO_EDITOR", "ai", id, "Resposta da IA enviada ao Editor Jurídico como rascunho");
        return saved;
    }

    private static AiConversation.Turn turn(AiConversation c, int index) {
        if (index < 0 || index >= c.getMessages().size() || !"assistant".equals(c.getMessages().get(index).getRole())) {
            throw new BusinessException("INVALID_MESSAGE", "Resposta não encontrada.");
        }
        return c.getMessages().get(index);
    }

    // ------------------------------------------------------------------ contexto

    private static final class Context {
        final List<ContentBlockParam> blocks = new ArrayList<>();
        final List<String> documentTitles = new ArrayList<>();
        String caseNumber;
        String clientName;
        boolean restricted;
    }

    private Context context(AuthenticatedUser user, String caseId, String clientId, List<String> docIds, boolean pseudo) {
        Context ctx = new Context();
        StringBuilder sb = new StringBuilder();
        if (caseId != null) {
            if (!user.has(Permission.CASES_READ)) {
                throw new ForbiddenException("ACCESS_DENIED", "Sem permissão para consultar processos.");
            }
            LegalCase c = cases.load(user, caseId);
            ctx.caseNumber = c.getCnjNumber();
            ctx.clientName = c.getClientName();
            ctx.restricted |= c.isRestricted();
            sb.append("## Processo\n");
            line(sb, "Número", c.getCnjNumber());
            line(sb, "Cliente", c.getClientName());
            line(sb, "Polo ativo", c.getActiveParty());
            line(sb, "Polo passivo", c.getPassiveParty());
            line(sb, "Parte contrária", c.getOpposingParty());
            line(sb, "Tribunal", c.getCourt());
            line(sb, "Vara/juízo", c.getCourtDivision() != null ? c.getCourtDivision() : c.getJudgingBody());
            line(sb, "Comarca", c.getDistrict() == null ? null : c.getDistrict() + (c.getState() == null ? "" : "/" + c.getState()));
            line(sb, "Área", c.getArea() == null ? null : c.getArea().label());
            line(sb, "Classe", c.getProceduralClass());
            line(sb, "Assunto", c.getSubject());
            line(sb, "Instância", c.getInstance());
            line(sb, "Fase", c.getPhase() == null ? null : c.getPhase().label());
            line(sb, "Situação", c.getStatus() == null ? null : c.getStatus().label());
            line(sb, "Valor da causa", c.getClaimValueCents() == null ? null : String.format(Locale.ROOT, "R$ %.2f", c.getClaimValueCents() / 100.0));
            line(sb, "Distribuição", c.getDistributionDate() == null ? null : c.getDistributionDate().format(BR));
            line(sb, "Responsável", c.getResponsibleName());
            line(sb, "Observações", c.getNotes());
            List<CaseMovement> moves = new ArrayList<>(cases.movements(user, caseId, null, 80).items());
            if (!moves.isEmpty()) {
                sb.append("\n### Movimentações (mais recentes primeiro)\n");
                for (CaseMovement m : moves) {
                    sb.append("- ").append(m.getDate() == null ? "" : m.getDate().format(BR)).append(" · ")
                            .append(m.getType() == null ? "" : m.getType().label()).append(" · ").append(nz(m.getTitle()));
                    if (m.getDescription() != null && !m.getDescription().isBlank()) {
                        String d = m.getDescription().length() > 1500 ? m.getDescription().substring(0, 1500) + "…" : m.getDescription();
                        sb.append(": ").append(d.replace('\n', ' '));
                    }
                    sb.append('\n');
                }
            }
            if (user.has(Permission.DEADLINES_READ)) {
                List<Deadline> open = deadlines.list(deadlines.collection(user.officeId()).whereEqualTo("caseId", caseId), 200).stream()
                        .filter(d -> d.getStatus() == DeadlineStatus.PENDENTE)
                        .sorted(Comparator.comparing(Deadline::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
                if (!open.isEmpty()) {
                    sb.append("\n### Prazos em aberto\n");
                    open.forEach(d -> sb.append("- ").append(d.getDueDate() == null ? "" : d.getDueDate().format(BR)).append(" · ")
                            .append(d.getType() == null ? "" : d.getType().label()).append(" · ").append(nz(d.getDescription())).append('\n'));
                }
            }
        }
        if (clientId != null && caseId == null) {
            if (!user.has(Permission.CLIENTS_READ)) {
                throw new ForbiddenException("ACCESS_DENIED", "Sem permissão para consultar clientes.");
            }
            Client cl = clients.findById(user.officeId(), clientId).orElseThrow(() -> new NotFoundException("Cliente"));
            ctx.clientName = cl.getName();
            sb.append("## Cliente\n");
            line(sb, "Nome", cl.getName());
            line(sb, "Tipo", cl.getType() == null ? null : cl.getType().name());
            line(sb, "Profissão", cl.getProfession());
            line(sb, "Estado civil", cl.getMaritalStatus());
            line(sb, "Cidade", cl.getAddress() == null ? null : cl.getAddress().getCity());
            line(sb, "Observações", cl.getNotes());
        }
        if (sb.length() > 0) {
            String t = pseudo ? DocumentText.pseudonymize(sb.toString()) : sb.toString();
            ctx.blocks.add(text("Contexto do escritório (dados do sistema):\n\n" + t));
        }
        int n = 1;
        for (String id : docIds) {
            DocumentResponse meta = documents.get(user, id);
            ctx.documentTitles.add(meta.title());
            ctx.restricted |= meta.restricted();
            FileContent file = documents.content(user, id, null, false);
            // texto já lido pela central (nativo ou OCR feito no servidor) evita enviar o PDF digitalizado como imagem
            String txt = ocr == null ? "" : documentRepo.findById(user.officeId(), id)
                    .flatMap(sd -> ocr.currentText(user.officeId(), sd)).orElse("");
            if (txt.isBlank()) {
                txt = DocumentText.extract(file.bytes(), file.contentType(), file.fileName());
            }
            if (txt.length() >= 200 || !DocumentText.isPdf(file.contentType(), file.fileName())) {
                if (txt.isBlank()) {
                    throw new BusinessException("UNSUPPORTED_DOCUMENT", "Não foi possível ler o texto de \"" + meta.title()
                            + "\". Use PDF, DOCX ou TXT.");
                }
                ctx.blocks.add(text("## Documento " + n + ": " + meta.title() + "\n\n" + (pseudo ? DocumentText.pseudonymize(txt) : txt)));
            } else {
                // PDF digitalizado (sem camada de texto): o modelo lê as páginas como imagem
                if (file.bytes().length > MAX_PDF_BYTES) {
                    throw new BusinessException("DOCUMENT_TOO_LARGE", "\"" + meta.title() + "\" é um PDF digitalizado grande demais para a IA (máx. 20 MB).");
                }
                ctx.blocks.add(text("## Documento " + n + ": " + meta.title() + " (PDF digitalizado a seguir)"));
                ctx.blocks.add(ContentBlockParam.ofDocument(DocumentBlockParam.builder()
                        .source(Base64PdfSource.builder().data(Base64.getEncoder().encodeToString(file.bytes())).build())
                        .title(meta.title()).build()));
            }
            n++;
        }
        return ctx;
    }

    private static ContentBlockParam text(String s) {
        return ContentBlockParam.ofText(TextBlockParam.builder().text(s).build());
    }

    private static void line(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("- ").append(label).append(": ").append(value.trim()).append('\n');
        }
    }

    private static String title(AiCatalog.Task task, String question, Context ctx) {
        String base = task == AiCatalog.Task.CONVERSA || task == AiCatalog.Task.PESQUISA
                ? (question.length() > 80 ? question.substring(0, 80) + "…" : question) : task.label();
        String ref = ctx.caseNumber != null ? ctx.caseNumber : !ctx.documentTitles.isEmpty() ? ctx.documentTitles.get(0) : ctx.clientName;
        return ref == null || base.contains(ref) ? base : base + " · " + ref;
    }

    /** Limite de uso por pessoa (controle de custo e de abuso): 60 pedidos por hora. */
    private void rateLimit(AuthenticatedUser user) {
        long now = System.currentTimeMillis();
        Deque<Long> q = usage.computeIfAbsent(user.officeId() + "/" + user.uid(), k -> new ConcurrentLinkedDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() > 3_600_000) {
            q.pollFirst();
        }
        if (q.size() >= MAX_PER_HOUR) {
            throw new BusinessException("AI_QUOTA", "Limite de " + MAX_PER_HOUR + " pedidos à IA por hora atingido. Tente mais tarde.");
        }
        q.addLast(now);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private br.com.erpadv.services.OcrService ocr;
    @org.springframework.beans.factory.annotation.Autowired
    private br.com.erpadv.repositories.DocumentRepositories.Documents documentRepo;
}
