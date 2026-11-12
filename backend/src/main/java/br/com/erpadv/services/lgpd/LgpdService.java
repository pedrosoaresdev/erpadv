package br.com.erpadv.services.lgpd;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.ConflictException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.firebase.CryptoService;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.Client;
import br.com.erpadv.models.ClientStatus;
import br.com.erpadv.models.ContractEnums.ContractStatus;
import br.com.erpadv.models.DocumentCategory;
import br.com.erpadv.models.FinancialTransaction;
import br.com.erpadv.models.LegalCase;
import br.com.erpadv.models.Lgpd;
import br.com.erpadv.models.Office;
import br.com.erpadv.models.SecurityEvent;
import br.com.erpadv.models.StoredDocument;
import br.com.erpadv.models.TransactionStatus;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.repositories.CaseRepository;
import br.com.erpadv.repositories.ClientRepository;
import br.com.erpadv.repositories.CommunicationRepositories;
import br.com.erpadv.repositories.ContractRepositories;
import br.com.erpadv.repositories.DocumentRepositories;
import br.com.erpadv.repositories.FinancialTransactionRepository;
import br.com.erpadv.repositories.LgpdRepositories;
import br.com.erpadv.repositories.OfficeRepository;
import br.com.erpadv.repositories.UserRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Permission;
import br.com.erpadv.security.PermissionResolver;
import br.com.erpadv.services.CourtCalendarService;
import br.com.erpadv.services.DocumentService;
import br.com.erpadv.services.NotificationService;
import br.com.erpadv.services.deadlines.BrazilianHolidays;
import br.com.erpadv.services.generation.HtmlDocument;
import br.com.erpadv.services.generation.ReportExporter;
import br.com.erpadv.services.security.SecurityEventService;
import br.com.erpadv.validation.BrazilianDocuments;
import com.google.cloud.firestore.Query;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * LGPD: solicitações de titulares (art. 18 e 19), consentimentos, registro das operações de tratamento (art. 37),
 * incidentes (art. 48 e Res. CD/ANPD 15/2024), controle de compartilhamento, retenção documental, política de
 * privacidade, exportação autorizada dos dados de um titular e anonimização quando juridicamente cabível.
 */
@Service
public class LgpdService {

    public static final int REQUEST_DAYS = 15;
    private static final ZoneId ZONE = CourtCalendarService.ZONE;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // ------------------------------------------------------------------ requisições

    public record RequestInput(Lgpd.RequestType type, String requesterName, String requesterEmail, String requesterPhone, String clientId,
                               String channel, String description, LocalDate receivedDate, boolean identityVerified) {
    }

    public record RequestUpdate(Lgpd.DataRequest.Status status, String response, Boolean identityVerified, String note) {
    }

    public record ConsentInput(String clientId, String purpose, String channel, String evidence, String documentId, LocalDate grantedDate,
                               LocalDate expiresDate) {
    }

    public record ActivityInput(String name, String purpose, Lgpd.LegalBasis legalBasis, boolean sensitiveData, String sensitiveBasis,
                                List<String> dataCategories, List<String> dataSubjects, List<String> sharedWith, String retention,
                                boolean internationalTransfer, String securityMeasures, String responsibleName, boolean active) {
    }

    public record IncidentInput(String title, String description, Instant discoveredAt, Instant occurredAt, List<String> dataCategories,
                                int affectedSubjects, boolean sensitiveData, Lgpd.Incident.Risk risk, boolean anpdNotificationRequired,
                                String containmentMeasures) {
    }

    public record IncidentUpdate(Lgpd.Incident.Status status, Lgpd.Incident.Risk risk, Boolean anpdNotificationRequired, LocalDate anpdNotifiedDate,
                                 String anpdProtocol, LocalDate subjectsNotifiedDate, String containmentMeasures, String note) {
    }

    public record ShareInput(String recipient, String recipientType, String clientId, String caseId, String dataDescription, String purpose,
                             Lgpd.LegalBasis legalBasis, LocalDate sharedDate) {
    }

    public record PolicyInput(String title, String content) {
    }

    public record RetentionInput(DocumentCategory category, int years, String legalReference) {
    }

    public record RetentionItem(String documentId, String title, DocumentCategory category, String clientName, String caseNumber,
                                Instant createdAt, LocalDate expiredOn) {
    }

    public record Overview(long openRequests, long overdueRequests, long openIncidents, long anpdPending, long activeConsents,
                           long activities, long pendingSuggestions, Integer publishedPolicyVersion, LocalDate nextRequestDue) {
    }

    public record PublicPolicy(String officeName, String title, int version, Instant publishedAt, String content) {
    }

    public record AnonymizeResult(String clientId, String label, int interactionsRemoved, int communicationsRedacted, int transactionsRelabeled) {
    }

    private final LgpdRepositories.Requests requests;
    private final LgpdRepositories.Consents consents;
    private final LgpdRepositories.Activities activities;
    private final LgpdRepositories.Incidents incidents;
    private final LgpdRepositories.Shares shares;
    private final LgpdRepositories.Policies policies;
    private final LgpdRepositories.Retention retention;
    private final ClientRepository clients;
    private final CaseRepository cases;
    private final ContractRepositories.Contracts contracts;
    private final FinancialTransactionRepository transactions;
    private final DocumentRepositories.Documents documents;
    private final CommunicationRepositories.Communications communications;
    private final DocumentService documentService;
    private final OfficeRepository offices;
    private final UserRepository users;
    private final PermissionResolver permissionResolver;
    private final CryptoService crypto;
    private final NotificationService notifications;
    private final SecurityEventService events;
    private final AuditService audit;

    public LgpdService(LgpdRepositories.Requests requests, LgpdRepositories.Consents consents, LgpdRepositories.Activities activities,
                       LgpdRepositories.Incidents incidents, LgpdRepositories.Shares shares, LgpdRepositories.Policies policies,
                       LgpdRepositories.Retention retention, ClientRepository clients, CaseRepository cases,
                       ContractRepositories.Contracts contracts, FinancialTransactionRepository transactions,
                       DocumentRepositories.Documents documents, CommunicationRepositories.Communications communications,
                       DocumentService documentService, OfficeRepository offices, UserRepository users, PermissionResolver permissionResolver,
                       CryptoService crypto, NotificationService notifications, SecurityEventService events, AuditService audit) {
        this.requests = requests;
        this.consents = consents;
        this.activities = activities;
        this.incidents = incidents;
        this.shares = shares;
        this.policies = policies;
        this.retention = retention;
        this.clients = clients;
        this.cases = cases;
        this.contracts = contracts;
        this.transactions = transactions;
        this.documents = documents;
        this.communications = communications;
        this.documentService = documentService;
        this.offices = offices;
        this.users = users;
        this.permissionResolver = permissionResolver;
        this.crypto = crypto;
        this.notifications = notifications;
        this.events = events;
        this.audit = audit;
    }

    private static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    private static String clean(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static List<String> cleanList(List<String> l) {
        return l == null ? new ArrayList<>() : new ArrayList<>(l.stream().map(x -> clean(x, 120)).filter(x -> x != null).distinct().limit(40).toList());
    }

    private Client client(String officeId, String clientId) {
        return clients.findById(officeId, clientId).orElseThrow(() -> new NotFoundException("Cliente"));
    }

    private void stamp(AuthenticatedUser user, br.com.erpadv.models.BaseEntity e, boolean created) {
        Instant now = Instant.now();
        if (created) {
            e.setId(e.getId() == null ? UUID.randomUUID().toString() : e.getId());
            e.setCreatedAt(now);
            e.setCreatedBy(user.uid());
        }
        e.setUpdatedAt(now);
        e.setUpdatedBy(user.uid());
    }

    // ------------------------------------------------------------------ painel

    public Overview overview(AuthenticatedUser user) {
        String officeId = user.requireOffice();
        LocalDate today = today();
        List<Lgpd.DataRequest> reqs = requests.list(requests.collection(officeId), 2000);
        List<Lgpd.DataRequest> open = reqs.stream().filter(r -> r.getStatus() == Lgpd.DataRequest.Status.RECEBIDA
                || r.getStatus() == Lgpd.DataRequest.Status.EM_ANALISE).toList();
        List<Lgpd.Incident> inc = incidents.list(incidents.collection(officeId), 1000);
        List<Lgpd.ProcessingActivity> acts = activities.list(activities.collection(officeId), 500);
        Integer published = policies.list(policies.collection(officeId).whereEqualTo("published", true), 200).stream()
                .map(Lgpd.PrivacyPolicy::getVersion).max(Integer::compare).orElse(null);
        return new Overview(open.size(), open.stream().filter(r -> r.getDueDate() != null && r.getDueDate().isBefore(today)).count(),
                inc.stream().filter(i -> i.getStatus() != Lgpd.Incident.Status.ENCERRADO).count(),
                inc.stream().filter(i -> i.isAnpdNotificationRequired() && i.getAnpdNotifiedDate() == null).count(),
                consents.list(consents.collection(officeId).whereEqualTo("revoked", false), 5000).size(),
                acts.size(), acts.stream().filter(Lgpd.ProcessingActivity::isSuggestion).count(), published,
                open.stream().map(Lgpd.DataRequest::getDueDate).filter(d -> d != null).min(Comparator.naturalOrder()).orElse(null));
    }

    // ------------------------------------------------------------------ solicitações de titulares

    public List<Lgpd.DataRequest> requests(AuthenticatedUser user, Lgpd.DataRequest.Status status, String clientId) {
        Query q = requests.collection(user.requireOffice());
        if (clientId != null && !clientId.isBlank()) {
            q = q.whereEqualTo("clientId", clientId);
        } else if (status != null) {
            q = q.whereEqualTo("status", status.name());
        }
        return requests.list(q, 1000).stream()
                .filter(r -> status == null || r.getStatus() == status)
                .sorted(Comparator.comparing(Lgpd.DataRequest::getReceivedDate, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
    }

    public Lgpd.DataRequest createRequest(AuthenticatedUser user, RequestInput in) {
        String officeId = user.requireOffice();
        if (in.type() == null || clean(in.requesterName(), 200) == null) {
            throw new BusinessException("INVALID_REQUEST", "Informe o tipo de solicitação e o nome do titular.");
        }
        if (in.requesterEmail() != null && !in.requesterEmail().isBlank() && !in.requesterEmail().trim().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new BusinessException("INVALID_EMAIL", "E-mail do titular inválido.");
        }
        LocalDate received = in.receivedDate() == null ? today() : in.receivedDate();
        if (received.isAfter(today())) {
            throw new BusinessException("INVALID_DATE", "A data de recebimento não pode ser futura.");
        }
        Lgpd.DataRequest r = new Lgpd.DataRequest();
        r.setProtocol("LGPD-" + received.getYear() + "-" + code(6));
        r.setType(in.type());
        r.setRequesterName(clean(in.requesterName(), 200));
        r.setRequesterEmail(clean(in.requesterEmail(), 150));
        r.setRequesterPhone(clean(in.requesterPhone(), 30));
        if (in.clientId() != null && !in.clientId().isBlank()) {
            Client c = client(officeId, in.clientId());
            r.setClientId(c.getId());
            r.setClientName(c.getName());
        }
        r.setChannel(clean(in.channel(), 60));
        r.setDescription(clean(in.description(), 4000));
        r.setReceivedDate(received);
        r.setDueDate(received.plusDays(REQUEST_DAYS));
        r.setIdentityVerified(in.identityVerified());
        r.setCreatedByName(user.displayName());
        r.getHistory().add(new Lgpd.Entry(Instant.now(), user.displayName(), "Solicitação registrada: " + in.type().label()));
        stamp(user, r, true);
        requests.save(officeId, r);
        audit.record(user, "LGPD_REQUEST_CREATED", "lgpd_request", r.getId(), r.getProtocol() + " · " + in.type().label() + " · " + r.getRequesterName());
        notifyManagers(officeId, user.uid(), "Nova solicitação de titular (LGPD)", r.getProtocol() + " — " + in.type().label()
                + ". Prazo: " + r.getDueDate().format(BR) + ".", "/lgpd?aba=solicitacoes");
        return r;
    }

    public Lgpd.DataRequest updateRequest(AuthenticatedUser user, String id, RequestUpdate in) {
        String officeId = user.requireOffice();
        Lgpd.DataRequest r = requests.findById(officeId, id).orElseThrow(() -> new NotFoundException("Solicitação"));
        Map<String, Object> before = audit.snapshot(r);
        if (in.identityVerified() != null && in.identityVerified() != r.isIdentityVerified()) {
            r.setIdentityVerified(in.identityVerified());
            r.getHistory().add(new Lgpd.Entry(Instant.now(), user.displayName(), in.identityVerified() ? "Identidade do titular confirmada" : "Confirmação de identidade desfeita"));
        }
        if (in.status() != null && in.status() != r.getStatus()) {
            boolean closing = in.status() == Lgpd.DataRequest.Status.ATENDIDA || in.status() == Lgpd.DataRequest.Status.NEGADA;
            if (closing && clean(in.response(), 8000) == null) {
                throw new BusinessException("RESPONSE_REQUIRED", "Registre a resposta enviada ao titular (ou o fundamento da negativa).");
            }
            if (in.status() == Lgpd.DataRequest.Status.ATENDIDA && !r.isIdentityVerified() && !Boolean.TRUE.equals(in.identityVerified())) {
                throw new BusinessException("IDENTITY_NOT_VERIFIED", "Confirme a identidade do titular antes de atender a solicitação.");
            }
            r.setStatus(in.status());
            if (closing) {
                r.setResponse(clean(in.response(), 8000));
                r.setRespondedAt(Instant.now());
                r.setRespondedByName(user.displayName());
            }
            r.getHistory().add(new Lgpd.Entry(Instant.now(), user.displayName(), "Situação: " + in.status().name().replace('_', ' ').toLowerCase(Locale.ROOT)));
        } else if (clean(in.response(), 8000) != null) {
            r.setResponse(clean(in.response(), 8000));
        }
        if (clean(in.note(), 2000) != null) {
            r.getHistory().add(new Lgpd.Entry(Instant.now(), user.displayName(), clean(in.note(), 2000)));
        }
        stamp(user, r, false);
        requests.save(officeId, r);
        audit.recordChange(user, "LGPD_REQUEST_UPDATED", "lgpd_request", id, r.getProtocol() + " atualizada", Map.of(), before, r);
        return r;
    }

    // ------------------------------------------------------------------ consentimentos

    public List<Lgpd.Consent> consents(AuthenticatedUser user, String clientId) {
        Query q = consents.collection(user.requireOffice());
        if (clientId != null && !clientId.isBlank()) {
            q = q.whereEqualTo("clientId", clientId);
        }
        return consents.list(q, 3000).stream()
                .sorted(Comparator.comparing(Lgpd.Consent::getGrantedDate, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
    }

    public Lgpd.Consent createConsent(AuthenticatedUser user, ConsentInput in) {
        String officeId = user.requireOffice();
        if (in.clientId() == null || clean(in.purpose(), 500) == null) {
            throw new BusinessException("INVALID_CONSENT", "Informe o titular (cliente) e a finalidade específica do consentimento.");
        }
        Client c = client(officeId, in.clientId());
        LocalDate granted = in.grantedDate() == null ? today() : in.grantedDate();
        if (granted.isAfter(today()) || (in.expiresDate() != null && !in.expiresDate().isAfter(granted))) {
            throw new BusinessException("INVALID_DATE", "Datas do consentimento inválidas.");
        }
        Lgpd.Consent x = new Lgpd.Consent();
        x.setClientId(c.getId());
        x.setClientName(c.getName());
        x.setPurpose(clean(in.purpose(), 500));
        x.setChannel(clean(in.channel(), 60));
        x.setEvidence(clean(in.evidence(), 1000));
        if (in.documentId() != null && !in.documentId().isBlank()) {
            documentService.get(user, in.documentId());
            x.setDocumentId(in.documentId());
        }
        x.setGrantedDate(granted);
        x.setExpiresDate(in.expiresDate());
        x.setCreatedByName(user.displayName());
        stamp(user, x, true);
        consents.save(officeId, x);
        audit.record(user, "CONSENT_RECORDED", "consent", x.getId(), "Consentimento de " + c.getName() + ": " + x.getPurpose());
        return x;
    }

    public Lgpd.Consent revokeConsent(AuthenticatedUser user, String id, String note) {
        String officeId = user.requireOffice();
        Lgpd.Consent x = consents.findById(officeId, id).orElseThrow(() -> new NotFoundException("Consentimento"));
        if (x.isRevoked()) {
            throw new BusinessException("ALREADY_REVOKED", "Este consentimento já foi revogado.");
        }
        x.setRevoked(true);
        x.setRevokedDate(today());
        x.setRevocationNote(clean(note, 1000));
        stamp(user, x, false);
        consents.save(officeId, x);
        audit.record(user, "CONSENT_REVOKED", "consent", id, "Consentimento revogado: " + x.getClientName() + " — " + x.getPurpose());
        return x;
    }

    // ------------------------------------------------------------------ registro das operações de tratamento

    public List<Lgpd.ProcessingActivity> activities(AuthenticatedUser user) {
        return activities.list(activities.collection(user.requireOffice()), 500).stream()
                .sorted(Comparator.comparing(Lgpd.ProcessingActivity::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))).toList();
    }

    public Lgpd.ProcessingActivity saveActivity(AuthenticatedUser user, String id, ActivityInput in) {
        String officeId = user.requireOffice();
        if (clean(in.name(), 200) == null || clean(in.purpose(), 1000) == null || in.legalBasis() == null) {
            throw new BusinessException("INVALID_ACTIVITY", "Informe nome, finalidade e base legal da atividade de tratamento.");
        }
        if (in.sensitiveData() && clean(in.sensitiveBasis(), 500) == null) {
            throw new BusinessException("SENSITIVE_BASIS_REQUIRED", "Dados sensíveis exigem a hipótese do art. 11 (ex.: exercício regular de direitos).");
        }
        Lgpd.ProcessingActivity a = id == null ? new Lgpd.ProcessingActivity()
                : activities.findById(officeId, id).orElseThrow(() -> new NotFoundException("Atividade"));
        Map<String, Object> before = id == null ? null : audit.snapshot(a);
        a.setName(clean(in.name(), 200));
        a.setPurpose(clean(in.purpose(), 1000));
        a.setLegalBasis(in.legalBasis());
        a.setSensitiveData(in.sensitiveData());
        a.setSensitiveBasis(in.sensitiveData() ? clean(in.sensitiveBasis(), 500) : null);
        a.setDataCategories(cleanList(in.dataCategories()));
        a.setDataSubjects(cleanList(in.dataSubjects()));
        a.setSharedWith(cleanList(in.sharedWith()));
        a.setRetention(clean(in.retention(), 300));
        a.setInternationalTransfer(in.internationalTransfer());
        a.setSecurityMeasures(clean(in.securityMeasures(), 1000));
        a.setResponsibleName(clean(in.responsibleName(), 120));
        a.setActive(in.active());
        a.setSuggestion(false);
        stamp(user, a, id == null);
        activities.save(officeId, a);
        if (id == null) {
            audit.record(user, "PROCESSING_ACTIVITY_CREATED", "processing_activity", a.getId(), "Atividade de tratamento: " + a.getName());
        } else {
            audit.recordChange(user, "PROCESSING_ACTIVITY_UPDATED", "processing_activity", a.getId(), "Atividade de tratamento: " + a.getName(),
                    Map.of(), before, a);
        }
        return a;
    }

    public void deleteActivity(AuthenticatedUser user, String id) {
        String officeId = user.requireOffice();
        Lgpd.ProcessingActivity a = activities.findById(officeId, id).orElseThrow(() -> new NotFoundException("Atividade"));
        activities.delete(officeId, id);
        audit.record(user, "PROCESSING_ACTIVITY_DELETED", "processing_activity", id, "Atividade removida: " + a.getName());
    }

    /**
     * Pontos de partida típicos de um escritório de advocacia, marcados como sugestão até o escritório revisar e salvar.
     * Só são criados quando o registro está vazio.
     */
    public List<Lgpd.ProcessingActivity> seedSuggestions(AuthenticatedUser user) {
        String officeId = user.requireOffice();
        if (!activities.list(activities.collection(officeId), 1).isEmpty()) {
            throw new BusinessException("ALREADY_HAS_ACTIVITIES", "O registro já tem atividades. As sugestões só são carregadas em um registro vazio.");
        }
        List<Lgpd.ProcessingActivity> out = new ArrayList<>();
        out.add(suggestion("Atendimento e cadastro de clientes", "Identificar e contatar clientes, formalizar a contratação e prestar os serviços jurídicos.",
                Lgpd.LegalBasis.EXECUCAO_CONTRATO, List.of("Identificação (nome, CPF/CNPJ, RG)", "Contato", "Endereço", "Estado civil e profissão"),
                List.of("Clientes", "Representantes de clientes PJ"), List.of(), "Durante a relação contratual e prazos prescricionais aplicáveis"));
        out.add(suggestion("Patrocínio de processos judiciais e administrativos", "Defender os interesses do cliente em juízo e fora dele.",
                Lgpd.LegalBasis.EXERCICIO_DIREITOS, List.of("Dados do processo", "Partes e testemunhas", "Documentos e provas"),
                List.of("Clientes", "Partes contrárias", "Testemunhas", "Peritos"), List.of("Tribunais e órgãos públicos", "Peritos e assistentes técnicos"),
                "Até o fim do processo e prazos de ação rescisória e responsabilidade profissional"));
        out.add(suggestion("Gestão financeira e honorários", "Emitir cobranças, recibos e cumprir obrigações fiscais e contábeis.",
                Lgpd.LegalBasis.OBRIGACAO_LEGAL, List.of("Identificação", "Dados de pagamento", "Valores e vencimentos"),
                List.of("Clientes", "Fornecedores"), List.of("Contabilidade", "Instituições financeiras"), "5 anos (legislação tributária)"));
        out.add(suggestion("Gestão da equipe", "Administrar o vínculo com colaboradores, acessos ao sistema e obrigações trabalhistas.",
                Lgpd.LegalBasis.OBRIGACAO_LEGAL, List.of("Identificação", "Contato", "Registro profissional (OAB)", "Acessos e auditoria"),
                List.of("Advogados", "Estagiários", "Colaboradores"), List.of("Contabilidade"), "Prazos da legislação trabalhista e previdenciária"));
        out.add(suggestion("Captação de clientes (CRM)", "Registrar contatos de potenciais clientes e propostas de honorários.",
                Lgpd.LegalBasis.LEGITIMO_INTERESSE, List.of("Nome", "Contato", "Assunto de interesse"), List.of("Potenciais clientes"), List.of(),
                "Até 2 anos sem contratação"));
        out.add(suggestion("Comunicação com clientes (e-mail e WhatsApp)", "Enviar andamentos, documentos, cobranças e lembretes.",
                Lgpd.LegalBasis.EXECUCAO_CONTRATO, List.of("Contato", "Conteúdo das mensagens"), List.of("Clientes"),
                List.of("Provedor de e-mail", "Meta (WhatsApp Business)"), "Durante a relação contratual"));
        for (Lgpd.ProcessingActivity a : out) {
            stamp(user, a, true);
            activities.save(officeId, a);
        }
        audit.record(user, "PROCESSING_ACTIVITIES_SEEDED", "processing_activity", null, out.size() + " sugestão(ões) carregada(s) para revisão");
        return out;
    }

    private static Lgpd.ProcessingActivity suggestion(String name, String purpose, Lgpd.LegalBasis basis, List<String> categories,
                                                      List<String> subjects, List<String> shared, String retention) {
        Lgpd.ProcessingActivity a = new Lgpd.ProcessingActivity();
        a.setName(name);
        a.setPurpose(purpose);
        a.setLegalBasis(basis);
        a.setDataCategories(new ArrayList<>(categories));
        a.setDataSubjects(new ArrayList<>(subjects));
        a.setSharedWith(new ArrayList<>(shared));
        a.setRetention(retention);
        a.setSecurityMeasures("Controle de acesso por perfil, autenticação em dois fatores, criptografia de dados sensíveis, trilha de auditoria e backup cifrado");
        a.setSuggestion(true);
        return a;
    }

    // ------------------------------------------------------------------ incidentes

    public List<Lgpd.Incident> incidents(AuthenticatedUser user) {
        return incidents.list(incidents.collection(user.requireOffice()), 1000).stream()
                .sorted(Comparator.comparing(Lgpd.Incident::getDiscoveredAt, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
    }

    public Lgpd.Incident createIncident(AuthenticatedUser user, IncidentInput in) {
        String officeId = user.requireOffice();
        if (clean(in.title(), 200) == null || clean(in.description(), 4000) == null) {
            throw new BusinessException("INVALID_INCIDENT", "Descreva o incidente (título e o que aconteceu).");
        }
        Instant discovered = in.discoveredAt() == null ? Instant.now() : in.discoveredAt();
        if (discovered.isAfter(Instant.now().plusSeconds(60)) || (in.occurredAt() != null && in.occurredAt().isAfter(discovered))) {
            throw new BusinessException("INVALID_DATE", "Datas do incidente inválidas.");
        }
        if (in.affectedSubjects() < 0) {
            throw new BusinessException("INVALID_INCIDENT", "Número de titulares afetados inválido.");
        }
        Lgpd.Incident i = new Lgpd.Incident();
        i.setTitle(clean(in.title(), 200));
        i.setDescription(clean(in.description(), 4000));
        i.setDiscoveredAt(discovered);
        i.setOccurredAt(in.occurredAt());
        i.setDataCategories(cleanList(in.dataCategories()));
        i.setAffectedSubjects(in.affectedSubjects());
        i.setSensitiveData(in.sensitiveData());
        i.setRisk(in.risk() == null ? Lgpd.Incident.Risk.MEDIO : in.risk());
        i.setAnpdNotificationRequired(in.anpdNotificationRequired() || i.getRisk() == Lgpd.Incident.Risk.ALTO);
        i.setAnpdDeadline(i.isAnpdNotificationRequired() ? plusBusinessDays(discovered.atZone(ZONE).toLocalDate(), 3) : null);
        i.setContainmentMeasures(clean(in.containmentMeasures(), 4000));
        i.setCreatedByName(user.displayName());
        i.getTimeline().add(new Lgpd.Entry(Instant.now(), user.displayName(), "Incidente registrado"));
        stamp(user, i, true);
        incidents.save(officeId, i);
        audit.record(user, "INCIDENT_CREATED", "incident", i.getId(), "Incidente de segurança: " + i.getTitle());
        events.record(officeId, "LGPD_INCIDENT", SecurityEvent.Severity.CRITICO, "Incidente com dados pessoais registrado: " + i.getTitle(),
                user.uid(), user.displayName(), Map.of("incidentId", i.getId()));
        notifyManagers(officeId, user.uid(), "Incidente de segurança registrado", i.getTitle()
                + (i.getAnpdDeadline() == null ? "" : ". Comunicação à ANPD até " + i.getAnpdDeadline().format(BR) + "."), "/lgpd?aba=incidentes");
        return i;
    }

    public Lgpd.Incident updateIncident(AuthenticatedUser user, String id, IncidentUpdate in) {
        String officeId = user.requireOffice();
        Lgpd.Incident i = incidents.findById(officeId, id).orElseThrow(() -> new NotFoundException("Incidente"));
        Map<String, Object> before = audit.snapshot(i);
        if (in.risk() != null) {
            i.setRisk(in.risk());
        }
        if (in.anpdNotificationRequired() != null) {
            i.setAnpdNotificationRequired(in.anpdNotificationRequired());
        }
        if (i.getRisk() == Lgpd.Incident.Risk.ALTO) {
            i.setAnpdNotificationRequired(true);
        }
        if (i.isAnpdNotificationRequired() && i.getAnpdDeadline() == null) {
            i.setAnpdDeadline(plusBusinessDays(i.getDiscoveredAt().atZone(ZONE).toLocalDate(), 3));
        }
        if (in.anpdNotifiedDate() != null) {
            i.setAnpdNotifiedDate(in.anpdNotifiedDate());
            i.setAnpdProtocol(clean(in.anpdProtocol(), 120));
            i.getTimeline().add(new Lgpd.Entry(Instant.now(), user.displayName(), "Comunicado à ANPD em " + in.anpdNotifiedDate().format(BR)
                    + (i.getAnpdProtocol() == null ? "" : " (protocolo " + i.getAnpdProtocol() + ")")));
        }
        if (in.subjectsNotifiedDate() != null) {
            i.setSubjectsNotifiedDate(in.subjectsNotifiedDate());
            i.getTimeline().add(new Lgpd.Entry(Instant.now(), user.displayName(), "Titulares comunicados em " + in.subjectsNotifiedDate().format(BR)));
        }
        if (clean(in.containmentMeasures(), 4000) != null) {
            i.setContainmentMeasures(clean(in.containmentMeasures(), 4000));
        }
        if (in.status() != null && in.status() != i.getStatus()) {
            if (in.status() == Lgpd.Incident.Status.ENCERRADO && i.isAnpdNotificationRequired() && i.getAnpdNotifiedDate() == null) {
                throw new BusinessException("ANPD_PENDING", "Registre a comunicação à ANPD antes de encerrar (ou revise a avaliação de risco).");
            }
            i.setStatus(in.status());
            i.getTimeline().add(new Lgpd.Entry(Instant.now(), user.displayName(), "Situação: " + in.status().name().replace('_', ' ').toLowerCase(Locale.ROOT)));
        }
        if (clean(in.note(), 2000) != null) {
            i.getTimeline().add(new Lgpd.Entry(Instant.now(), user.displayName(), clean(in.note(), 2000)));
        }
        stamp(user, i, false);
        incidents.save(officeId, i);
        audit.recordChange(user, "INCIDENT_UPDATED", "incident", id, "Incidente atualizado: " + i.getTitle(), Map.of(), before, i);
        return i;
    }

    /** Soma dias úteis (sem fins de semana e feriados nacionais). */
    public static LocalDate plusBusinessDays(LocalDate start, int days) {
        LocalDate d = start;
        int added = 0;
        while (added < days) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !BrazilianHolidays.of(d.getYear()).containsKey(d)) {
                added++;
            }
        }
        return d;
    }

    // ------------------------------------------------------------------ compartilhamentos

    public List<Lgpd.DataShare> shares(AuthenticatedUser user, String clientId) {
        Query q = shares.collection(user.requireOffice());
        if (clientId != null && !clientId.isBlank()) {
            q = q.whereEqualTo("clientId", clientId);
        }
        return shares.list(q, 3000).stream()
                .sorted(Comparator.comparing(Lgpd.DataShare::getSharedDate, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
    }

    public Lgpd.DataShare createShare(AuthenticatedUser user, ShareInput in) {
        String officeId = user.requireOffice();
        if (clean(in.recipient(), 200) == null || clean(in.dataDescription(), 1000) == null || clean(in.purpose(), 1000) == null || in.legalBasis() == null) {
            throw new BusinessException("INVALID_SHARE", "Informe destinatário, dados compartilhados, finalidade e base legal.");
        }
        Lgpd.DataShare s = new Lgpd.DataShare();
        s.setRecipient(clean(in.recipient(), 200));
        s.setRecipientType(clean(in.recipientType(), 60));
        if (in.clientId() != null && !in.clientId().isBlank()) {
            Client c = client(officeId, in.clientId());
            s.setClientId(c.getId());
            s.setClientName(c.getName());
        }
        if (in.caseId() != null && !in.caseId().isBlank()) {
            cases.findById(officeId, in.caseId()).orElseThrow(() -> new NotFoundException("Processo"));
            s.setCaseId(in.caseId());
        }
        s.setDataDescription(clean(in.dataDescription(), 1000));
        s.setPurpose(clean(in.purpose(), 1000));
        s.setLegalBasis(in.legalBasis());
        s.setSharedDate(in.sharedDate() == null ? today() : in.sharedDate());
        s.setCreatedByName(user.displayName());
        stamp(user, s, true);
        shares.save(officeId, s);
        audit.record(user, "DATA_SHARED", "data_share", s.getId(), "Compartilhamento com " + s.getRecipient()
                + (s.getClientName() == null ? "" : " (" + s.getClientName() + ")"));
        return s;
    }

    // ------------------------------------------------------------------ política de privacidade

    public List<Lgpd.PrivacyPolicy> policyVersions(AuthenticatedUser user) {
        return policies.list(policies.collection(user.requireOffice()), 200).stream()
                .sorted(Comparator.comparingInt(Lgpd.PrivacyPolicy::getVersion).reversed()).toList();
    }

    public Lgpd.PrivacyPolicy saveDraft(AuthenticatedUser user, String id, PolicyInput in) {
        String officeId = user.requireOffice();
        String content = in.content() == null ? "" : HtmlDocument.sanitize(in.content());
        if (content.replaceAll("<[^>]+>", "").trim().length() < 200) {
            throw new BusinessException("POLICY_TOO_SHORT", "A política de privacidade está curta demais para informar o titular adequadamente.");
        }
        Lgpd.PrivacyPolicy p;
        if (id == null) {
            p = new Lgpd.PrivacyPolicy();
            p.setVersion(policyVersions(user).stream().mapToInt(Lgpd.PrivacyPolicy::getVersion).max().orElse(0) + 1);
            p.setCreatedByName(user.displayName());
        } else {
            p = policies.findById(officeId, id).orElseThrow(() -> new NotFoundException("Política"));
            if (p.isPublished() || p.getPublishedAt() != null) {
                throw new ConflictException("POLICY_PUBLISHED", "Versão publicada não pode ser alterada. Crie uma nova versão.");
            }
        }
        p.setTitle(clean(in.title(), 200) == null ? "Política de privacidade" : clean(in.title(), 200));
        p.setContent(content);
        stamp(user, p, id == null);
        policies.save(officeId, p);
        audit.record(user, "PRIVACY_POLICY_SAVED", "privacy_policy", p.getId(), "Política de privacidade v" + p.getVersion() + " (rascunho)");
        return p;
    }

    public Lgpd.PrivacyPolicy publish(AuthenticatedUser user, String id) {
        String officeId = user.requireOffice();
        Lgpd.PrivacyPolicy p = policies.findById(officeId, id).orElseThrow(() -> new NotFoundException("Política"));
        if (p.getPublishedAt() != null) {
            throw new ConflictException("POLICY_PUBLISHED", "Esta versão já foi publicada.");
        }
        for (Lgpd.PrivacyPolicy other : policies.list(policies.collection(officeId).whereEqualTo("published", true), 200)) {
            other.setPublished(false);
            policies.save(officeId, other);
        }
        p.setPublished(true);
        p.setPublishedAt(Instant.now());
        p.setPublishedByName(user.displayName());
        stamp(user, p, false);
        policies.save(officeId, p);
        audit.record(user, "PRIVACY_POLICY_PUBLISHED", "privacy_policy", id, "Política de privacidade v" + p.getVersion() + " publicada");
        return p;
    }

    /** Versão vigente para a página pública do escritório (sem autenticação). */
    public PublicPolicy publicPolicy(String officeId) {
        Office office = offices.findById(officeId).orElseThrow(() -> new NotFoundException("Política"));
        Lgpd.PrivacyPolicy p = policies.list(policies.collection(officeId).whereEqualTo("published", true), 10).stream()
                .max(Comparator.comparingInt(Lgpd.PrivacyPolicy::getVersion)).orElseThrow(() -> new NotFoundException("Política"));
        return new PublicPolicy(office.getName(), p.getTitle(), p.getVersion(), p.getPublishedAt(), p.getContent());
    }

    /** Modelo inicial preenchido com os dados do escritório, para revisão antes de publicar. */
    public PolicyInput template(AuthenticatedUser user) {
        Office o = offices.findById(user.requireOffice()).orElseThrow(() -> new NotFoundException("Escritório"));
        String name = HtmlDocument.escape(o.getName());
        String cnpj = o.getCnpj() == null ? "" : ", inscrito no CNPJ " + HtmlDocument.escape(BrazilianDocuments.format(o.getCnpj()));
        String email = o.getEmail() == null ? "[e-mail do encarregado]" : HtmlDocument.escape(o.getEmail());
        String html = "<h2>1. Quem somos</h2><p>" + name + cnpj + " é o controlador dos dados pessoais tratados na prestação de serviços "
                + "advocatícios, nos termos da Lei 13.709/2018 (LGPD).</p>"
                + "<h2>2. Dados que tratamos</h2><p>Dados de identificação e contato de clientes e de seus representantes; dados dos processos e "
                + "documentos necessários à defesa dos seus interesses; dados financeiros relativos a honorários; dados de partes contrárias, "
                + "testemunhas e terceiros constantes dos processos.</p>"
                + "<h2>3. Finalidades e bases legais</h2><ul><li>Prestação dos serviços contratados (execução de contrato).</li>"
                + "<li>Atuação em processos judiciais, administrativos e arbitrais (exercício regular de direitos).</li>"
                + "<li>Cumprimento de obrigações legais, fiscais e regulatórias, inclusive perante a OAB.</li>"
                + "<li>Comunicações sobre o andamento dos serviços.</li></ul>"
                + "<h2>4. Compartilhamento</h2><p>Os dados podem ser compartilhados com tribunais e órgãos públicos, peritos, contabilidade e "
                + "fornecedores de tecnologia que atuam em nosso nome, sempre na medida necessária e sob dever de confidencialidade.</p>"
                + "<h2>5. Sigilo e segurança</h2><p>Observamos o sigilo profissional da advocacia e adotamos controle de acesso por perfil, "
                + "autenticação em dois fatores, criptografia de dados sensíveis, trilha de auditoria e cópias de segurança cifradas.</p>"
                + "<h2>6. Retenção</h2><p>Os dados são mantidos pelo tempo necessário às finalidades acima e aos prazos legais, prescricionais e de "
                + "responsabilidade profissional; depois disso são eliminados ou anonimizados.</p>"
                + "<h2>7. Seus direitos</h2><p>Você pode solicitar confirmação de tratamento, acesso, correção, anonimização, bloqueio ou eliminação, "
                + "portabilidade, informações sobre compartilhamento e revogação do consentimento (art. 18 da LGPD). Responderemos em até "
                + REQUEST_DAYS + " dias.</p>"
                + "<h2>8. Contato do encarregado</h2><p>Solicitações sobre dados pessoais: " + email + ".</p>";
        return new PolicyInput("Política de privacidade — " + o.getName(), html);
    }

    // ------------------------------------------------------------------ retenção documental

    public List<Lgpd.RetentionRule> retentionRules(AuthenticatedUser user) {
        return retention.list(retention.collection(user.requireOffice()), 100).stream()
                .sorted(Comparator.comparing(r -> r.getCategory().name())).toList();
    }

    public Lgpd.RetentionRule saveRetention(AuthenticatedUser user, RetentionInput in) {
        String officeId = user.requireOffice();
        if (in.category() == null || in.years() < 0 || in.years() > 100) {
            throw new BusinessException("INVALID_RETENTION", "Informe a categoria e o prazo em anos (0 = guarda permanente).");
        }
        Lgpd.RetentionRule r = retention.findById(officeId, in.category().name()).orElseGet(Lgpd.RetentionRule::new);
        Map<String, Object> before = r.getId() == null ? null : audit.snapshot(r);
        boolean created = r.getId() == null;
        r.setId(in.category().name());
        r.setCategory(in.category());
        r.setYears(in.years());
        r.setLegalReference(clean(in.legalReference(), 300));
        stamp(user, r, created);
        retention.save(officeId, r);
        audit.recordChange(user, "RETENTION_RULE_SAVED", "retention", r.getId(), "Retenção: " + in.category().label() + " — "
                + (in.years() == 0 ? "permanente" : in.years() + " ano(s)"), Map.of(), before, r);
        return r;
    }

    public void deleteRetention(AuthenticatedUser user, DocumentCategory category) {
        String officeId = user.requireOffice();
        retention.findById(officeId, category.name()).orElseThrow(() -> new NotFoundException("Regra"));
        retention.delete(officeId, category.name());
        audit.record(user, "RETENTION_RULE_DELETED", "retention", category.name(), "Regra de retenção removida: " + category.label());
    }

    /** Documentos cujo prazo de guarda venceu (não excluídos); a eliminação é sempre decisão humana. */
    public List<RetentionItem> retentionReport(AuthenticatedUser user) {
        String officeId = user.requireOffice();
        Map<DocumentCategory, Lgpd.RetentionRule> rules = new EnumMap<>(DocumentCategory.class);
        retentionRules(user).forEach(r -> rules.put(r.getCategory(), r));
        List<RetentionItem> out = new ArrayList<>();
        LocalDate today = today();
        for (Map.Entry<DocumentCategory, Lgpd.RetentionRule> e : rules.entrySet()) {
            if (e.getValue().getYears() <= 0) {
                continue;
            }
            for (StoredDocument d : documents.list(documents.collection(officeId).whereEqualTo("category", e.getKey().name()), 5000)) {
                if (d.isDeleted() || d.getCreatedAt() == null) {
                    continue;
                }
                if (d.isRestricted() && !user.has(Permission.CRIMINAL_READ)) {
                    continue;
                }
                LocalDate expires = d.getCreatedAt().atZone(ZONE).toLocalDate().plusYears(e.getValue().getYears());
                if (!expires.isAfter(today)) {
                    out.add(new RetentionItem(d.getId(), d.getTitle(), d.getCategory(), d.getClientName(), d.getCaseNumber(), d.getCreatedAt(), expires));
                }
            }
        }
        out.sort(Comparator.comparing(RetentionItem::expiredOn));
        return out;
    }

    /** Envia à lixeira documentos com prazo vencido (a eliminação definitiva segue a regra da central documental). */
    public int trashExpired(AuthenticatedUser user, List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty() || documentIds.size() > 500) {
            throw new BusinessException("INVALID_SELECTION", "Selecione de 1 a 500 documentos.");
        }
        Set<String> expired = new java.util.HashSet<>(retentionReport(user).stream().map(RetentionItem::documentId).toList());
        int n = 0;
        for (String id : documentIds) {
            if (!expired.contains(id)) {
                throw new BusinessException("NOT_EXPIRED", "Há documento selecionado cujo prazo de guarda ainda não venceu.");
            }
        }
        for (String id : documentIds) {
            documentService.trash(user, id);
            n++;
        }
        audit.record(user, "RETENTION_APPLIED", "retention", null, n + " documento(s) enviado(s) à lixeira por prazo de guarda vencido");
        return n;
    }

    // ------------------------------------------------------------------ dados de um titular

    /** Relatório dos dados de um cliente (acesso e portabilidade), com CPF/CNPJ completos para entrega ao próprio titular. */
    public ReportExporter.Exported exportClient(AuthenticatedUser user, String clientId, String format) {
        String officeId = user.requireOffice();
        Client c = client(officeId, clientId);
        List<List<String>> fields = new ArrayList<>();
        fields.add(List.of("Tipo", c.getType() == null ? "" : c.getType().name()));
        fields.add(List.of("Nome", nz(c.getName())));
        fields.add(List.of("Nome fantasia", nz(c.getTradeName())));
        fields.add(List.of(c.getType() != null && c.getType().name().equals("PJ") ? "CNPJ" : "CPF",
                c.getDocumentEnc() == null ? "" : BrazilianDocuments.format(crypto.decrypt(c.getDocumentEnc()))));
        fields.add(List.of("RG", c.getRgEnc() == null ? "" : nz(crypto.decrypt(c.getRgEnc()))));
        fields.add(List.of("Nascimento", c.getBirthDate() == null ? "" : c.getBirthDate().format(BR)));
        fields.add(List.of("Nacionalidade", nz(c.getNationality())));
        fields.add(List.of("Estado civil", nz(c.getMaritalStatus())));
        fields.add(List.of("Profissão", nz(c.getProfession())));
        fields.add(List.of("Telefone", nz(c.getPhone())));
        fields.add(List.of("WhatsApp", nz(c.getWhatsapp())));
        fields.add(List.of("E-mail", nz(c.getEmail())));
        if (c.getAddress() != null) {
            var a = c.getAddress();
            fields.add(List.of("Endereço", String.join(", ", java.util.stream.Stream.of(a.getStreet(), a.getNumber(), a.getComplement(),
                    a.getNeighborhood(), a.getCity(), a.getState(), a.getZipCode()).filter(x -> x != null && !x.isBlank()).toList())));
        }
        fields.add(List.of("Origem do cadastro", nz(c.getSource())));
        fields.add(List.of("Cliente desde", c.getCreatedAt() == null ? "" : c.getCreatedAt().atZone(ZONE).toLocalDate().format(BR)));
        List<ReportExporter.Section> sections = new ArrayList<>();
        sections.add(ReportExporter.Section.fields("Dados cadastrais", fields));
        List<List<String>> caseRows = cases.list(cases.collection(officeId).whereEqualTo("clientId", clientId), 1000).stream()
                .filter(lc -> !lc.isRestricted() || user.has(Permission.CRIMINAL_READ))
                .map(lc -> List.of(nz(lc.getCnjNumber()), nz(lc.getCourt()), lc.getArea() == null ? "" : lc.getArea().name(),
                        lc.getStatus() == null ? "" : lc.getStatus().label())).toList();
        sections.add(ReportExporter.Section.table("Processos", List.of("Número", "Tribunal", "Área", "Situação"), caseRows));
        List<List<String>> contractRows = contracts.list(contracts.collection(officeId).whereEqualTo("clientId", clientId), 500).stream()
                .map(k -> List.of(nz(k.getNumber()), nz(k.getTitle()), k.getStatus() == null ? "" : k.getStatus().name())).toList();
        sections.add(ReportExporter.Section.table("Contratos", List.of("Número", "Objeto", "Situação"), contractRows));
        if (user.has(Permission.FINANCE_READ) || user.has(Permission.LGPD_MANAGE)) {
            List<List<String>> fin = transactions.list(transactions.collection(officeId).whereEqualTo("clientId", clientId), 2000).stream()
                    .filter(t -> t.getScope() == null || !t.getScope().name().equals("PESSOAL"))
                    .map(t -> List.of(nz(t.getDescription()), t.getDueDate() == null ? "" : t.getDueDate().format(BR),
                            String.format(Locale.ROOT, "%.2f", t.getAmountCents() / 100.0), t.getStatus() == null ? "" : t.getStatus().name())).toList();
            sections.add(ReportExporter.Section.table("Lançamentos financeiros", List.of("Descrição", "Vencimento", "Valor (R$)", "Situação"), fin));
        }
        List<List<String>> docs = documents.list(documents.collection(officeId).whereEqualTo("clientId", clientId), 2000).stream()
                .filter(d -> !d.isDeleted() && (!d.isRestricted() || user.has(Permission.CRIMINAL_READ)))
                .map(d -> List.of(nz(d.getTitle()), d.getCategory() == null ? "" : d.getCategory().label(),
                        d.getCreatedAt() == null ? "" : d.getCreatedAt().atZone(ZONE).toLocalDate().format(BR))).toList();
        sections.add(ReportExporter.Section.table("Documentos", List.of("Título", "Categoria", "Data"), docs));
        List<List<String>> comms = communications.list(communications.collection(officeId).whereEqualTo("clientId", clientId), 2000).stream()
                .map(x -> List.of(x.getCreatedAt() == null ? "" : x.getCreatedAt().atZone(ZONE).toLocalDate().format(BR),
                        x.getChannel() == null ? "" : x.getChannel().name(), nz(x.getSubject()))).toList();
        sections.add(ReportExporter.Section.table("Comunicações", List.of("Data", "Canal", "Assunto"), comms));
        List<List<String>> cons = consents(user, clientId).stream().map(x -> List.of(nz(x.getPurpose()),
                x.getGrantedDate() == null ? "" : x.getGrantedDate().format(BR), x.isRevoked() ? "revogado em " + x.getRevokedDate().format(BR) : "vigente")).toList();
        sections.add(ReportExporter.Section.table("Consentimentos", List.of("Finalidade", "Concedido em", "Situação"), cons));
        List<List<String>> sh = shares(user, clientId).stream().map(x -> List.of(nz(x.getRecipient()), nz(x.getDataDescription()),
                x.getLegalBasis() == null ? "" : x.getLegalBasis().label(), x.getSharedDate() == null ? "" : x.getSharedDate().format(BR))).toList();
        sections.add(ReportExporter.Section.table("Compartilhamentos com terceiros", List.of("Destinatário", "Dados", "Base legal", "Data"), sh));
        Office o = offices.findById(officeId).orElse(null);
        ReportExporter.Report report = new ReportExporter.Report("Dados pessoais do titular — " + c.getName(), o == null ? null : o.getName(),
                "Relatório para atendimento de solicitação de acesso/portabilidade (LGPD, art. 18)", sections, false);
        ReportExporter.Exported out = ReportExporter.export(report, format, "dados-titular-" + c.getName());
        audit.record(user, "LGPD_DATA_EXPORTED", "client", clientId, "Dados do titular exportados (" + format + "): " + c.getName());
        events.record(user, "LGPD_EXPORT", SecurityEvent.Severity.ATENCAO, "Exportação dos dados pessoais de " + c.getName() + " por " + user.displayName());
        return out;
    }

    /**
     * Anonimização do cliente quando juridicamente cabível: sem processos, sem contratos ativos e sem valores em aberto
     * (processos e obrigações em curso justificam a guarda — art. 16 da LGPD). Lançamentos já quitados permanecem por
     * obrigação fiscal, com o nome substituído.
     */
    public AnonymizeResult anonymize(AuthenticatedUser user, String clientId, String requestId) {
        String officeId = user.requireOffice();
        Client c = client(officeId, clientId);
        if (c.getAnonymizedAt() != null) {
            throw new BusinessException("ALREADY_ANONYMIZED", "Este cliente já foi anonimizado.");
        }
        List<LegalCase> linkedCases = cases.list(cases.collection(officeId).whereEqualTo("clientId", clientId), 1);
        if (!linkedCases.isEmpty()) {
            throw new ConflictException("LEGAL_RETENTION", "O cliente tem processos vinculados: os dados devem ser guardados para o exercício "
                    + "regular de direitos (art. 16, I e art. 7º, VI). Bloqueie o acesso ou aguarde o fim dos prazos.");
        }
        boolean activeContract = contracts.list(contracts.collection(officeId).whereEqualTo("clientId", clientId), 500).stream()
                .anyMatch(k -> k.getStatus() == ContractStatus.ATIVO || k.getStatus() == ContractStatus.RASCUNHO);
        if (activeContract) {
            throw new ConflictException("LEGAL_RETENTION", "Há contrato ativo ou em rascunho com o cliente.");
        }
        List<FinancialTransaction> fin = transactions.list(transactions.collection(officeId).whereEqualTo("clientId", clientId), 5000);
        if (fin.stream().anyMatch(t -> t.getStatus() == TransactionStatus.ABERTO)) {
            throw new ConflictException("LEGAL_RETENTION", "Há valores em aberto com o cliente.");
        }
        String label = "Titular anonimizado " + code(6);
        Map<String, Object> before = audit.snapshot(c);
        c.setName(label);
        c.setNameLower(label.toLowerCase(Locale.ROOT));
        c.setTradeName(null);
        c.setDocumentEnc(null);
        c.setDocumentHash("anon-" + UUID.randomUUID());
        c.setDocumentMasked(null);
        c.setRgEnc(null);
        c.setBirthDate(null);
        c.setPhone(null);
        c.setWhatsapp(null);
        c.setEmail(null);
        c.setAddress(null);
        c.setProfession(null);
        c.setMaritalStatus(null);
        c.setNationality(null);
        c.setNotes(null);
        c.setStateRegistration(null);
        c.setMunicipalRegistration(null);
        c.setLegalRepresentative(null);
        c.setLegalRepresentativeRole(null);
        c.setContacts(new ArrayList<>());
        c.setSourceDetail(null);
        c.setSearchTokens(new ArrayList<>());
        c.setStatus(ClientStatus.INACTIVE);
        c.setInactivationReason("Anonimizado (LGPD)");
        c.setInactivatedAt(Instant.now());
        c.setAnonymizedAt(Instant.now());
        stamp(user, c, false);
        clients.save(officeId, c);
        int interactions = 0;
        var ref = clients.collection(officeId).document(clientId).collection("interactions");
        for (var doc : Futures.await(ref.limit(1000).get()).getDocuments()) {
            Futures.await(doc.getReference().delete());
            interactions++;
        }
        int comms = 0;
        for (var x : communications.list(communications.collection(officeId).whereEqualTo("clientId", clientId), 5000)) {
            x.setClientName(label);
            x.setAddress(null);
            x.setSubject(null);
            x.setBody("[conteúdo removido por anonimização]");
            communications.save(officeId, x);
            comms++;
        }
        int relabeled = 0;
        for (FinancialTransaction t : fin) {
            t.setClientName(label);
            t.setCounterparty(null);
            transactions.save(officeId, t);
            relabeled++;
        }
        for (Lgpd.Consent x : consents(user, clientId)) {
            x.setClientName(label);
            consents.save(officeId, x);
        }
        audit.recordChange(user, "CLIENT_ANONYMIZED", "client", clientId, "Cliente anonimizado (LGPD)",
                requestId == null ? Map.of() : Map.of("requestId", requestId), before, c);
        events.record(user, "LGPD_ANONYMIZED", SecurityEvent.Severity.ATENCAO, "Cliente anonimizado por " + user.displayName());
        if (requestId != null && !requestId.isBlank()) {
            requests.findById(officeId, requestId).ifPresent(r -> {
                r.getHistory().add(new Lgpd.Entry(Instant.now(), user.displayName(), "Dados do cliente anonimizados"));
                r.setClientName(label);
                r.setRequesterName(label);
                r.setRequesterEmail(null);
                r.setRequesterPhone(null);
                requests.save(officeId, r);
            });
        }
        return new AnonymizeResult(clientId, label, interactions, comms, relabeled);
    }

    // ------------------------------------------------------------------ alertas de prazo

    /** Avisa responsáveis por LGPD de solicitações e comunicações à ANPD vencendo (rotina diária). */
    public int notifyDeadlines(String officeId) {
        LocalDate today = today();
        int n = 0;
        for (Lgpd.DataRequest r : requests.list(requests.collection(officeId), 2000)) {
            boolean open = r.getStatus() == Lgpd.DataRequest.Status.RECEBIDA || r.getStatus() == Lgpd.DataRequest.Status.EM_ANALISE;
            if (open && r.getDueDate() != null && !r.getDueDate().isAfter(today.plusDays(3))) {
                notifyManagers(officeId, null, r.getDueDate().isBefore(today) ? "Solicitação LGPD em atraso" : "Solicitação LGPD vencendo",
                        r.getProtocol() + " — " + r.getType().label() + " · prazo " + r.getDueDate().format(BR), "/lgpd?aba=solicitacoes");
                n++;
            }
        }
        for (Lgpd.Incident i : incidents.list(incidents.collection(officeId), 1000)) {
            if (i.isAnpdNotificationRequired() && i.getAnpdNotifiedDate() == null && i.getAnpdDeadline() != null
                    && !i.getAnpdDeadline().isAfter(today.plusDays(1))) {
                notifyManagers(officeId, null, "Comunicação à ANPD pendente", i.getTitle() + " · prazo " + i.getAnpdDeadline().format(BR),
                        "/lgpd?aba=incidentes");
                n++;
            }
        }
        return n;
    }

    private void notifyManagers(String officeId, String exceptUid, String title, String body, String link) {
        for (UserProfile u : users.activeStaff(officeId)) {
            if (u.getId().equals(exceptUid)) {
                continue;
            }
            if (permissionResolver.resolve(u).contains(Permission.LGPD_MANAGE)) {
                notifications.notify(officeId, u.getId(), "LGPD", title, body, link);
            }
        }
    }

    private static String code(int len) {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
