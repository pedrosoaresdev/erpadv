package br.com.erpadv.services;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.dto.ClientDtos.ClientRequest;
import br.com.erpadv.dto.ClientDtos.ClientResponse;
import br.com.erpadv.dto.ClientDtos.ClientSummary;
import br.com.erpadv.dto.ClientDtos.InteractionRequest;
import br.com.erpadv.dto.CursorPage;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.ConflictException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.firebase.CryptoService;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.firebase.SearchTokens;
import br.com.erpadv.models.Client;
import br.com.erpadv.models.ClientInteraction;
import br.com.erpadv.models.ClientStatus;
import br.com.erpadv.models.ClientType;
import br.com.erpadv.repositories.ClientRepository;
import br.com.erpadv.repositories.UserRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Permission;
import br.com.erpadv.validation.BrazilianDocuments;
import com.google.cloud.firestore.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** CRM jurídico: cadastro PF/PJ, pesquisa, inativação, inadimplência e atendimentos. */
@Service
public class ClientService {

    private static final int MAX_TOKENS = 300;

    private final ClientRepository clients;
    private final UserRepository users;
    private final CryptoService crypto;
    private final AuditService audit;
    private final com.google.cloud.firestore.Firestore db;
    private final br.com.erpadv.repositories.OfficeRepository offices;

    public ClientService(ClientRepository clients, UserRepository users, CryptoService crypto, AuditService audit,
                         com.google.cloud.firestore.Firestore db, br.com.erpadv.repositories.OfficeRepository offices) {
        this.clients = clients;
        this.users = users;
        this.crypto = crypto;
        this.audit = audit;
        this.db = db;
        this.offices = offices;
    }

    public CursorPage<ClientSummary> list(AuthenticatedUser user, String search, ClientStatus status, ClientType type,
                                          Boolean defaulting, String cursor, int size) {
        String officeId = user.requireOffice();
        Query query = clients.collection(officeId);
        if (search != null && !search.isBlank()) {
            String token = searchToken(search);
            if (token == null) {
                return new CursorPage<>(List.of(), null);
            }
            query = query.whereArrayContains("searchTokens", token);
        }
        if (status != null) {
            query = query.whereEqualTo("status", status.name());
        }
        if (type != null) {
            query = query.whereEqualTo("type", type.name());
        }
        if (defaulting != null) {
            query = query.whereEqualTo("defaulting", defaulting);
        }
        return clients.page(query, "nameLower", Query.Direction.ASCENDING, cursor, size).map(this::toSummary);
    }

    public ClientResponse get(AuthenticatedUser user, String id) {
        Client client = load(user, id);
        boolean sensitive = user.has(Permission.CLIENTS_SENSITIVE_READ);
        if (sensitive) {
            audit.record(user, "CLIENT_SENSITIVE_VIEWED", "client", id, "Visualização de dados pessoais completos");
        }
        return toResponse(client, sensitive);
    }

    public Client load(AuthenticatedUser user, String id) {
        return clients.findById(user.requireOffice(), id).orElseThrow(() -> new NotFoundException("Cliente"));
    }

    public ClientResponse create(AuthenticatedUser user, ClientRequest req) {
        String officeId = user.requireOffice();
        if (req.document() == null || req.document().isBlank()) {
            throw new BusinessException("DOCUMENT_REQUIRED", "Informe o CPF/CNPJ do cliente.");
        }
        String docDigits = validateDocument(req.type(), req.document());
        String hash = crypto.hmac(docDigits);
        clients.findByDocumentHash(officeId, hash).ifPresent(c -> {
            throw new ConflictException("CLIENT_DUPLICATE", "Já existe cliente cadastrado com este CPF/CNPJ.");
        });
        validateResponsible(officeId, req.responsibleUid());

        Client client = new Client();
        apply(client, req, docDigits, hash);
        client.setStatus(ClientStatus.ACTIVE);
        Instant now = Instant.now();
        client.setLastActivityAt(now);
        client.setCreatedAt(now);
        client.setUpdatedAt(now);
        client.setCreatedBy(user.uid());
        client.setUpdatedBy(user.uid());
        clients.save(officeId, client);
        audit.record(user, "CLIENT_CREATED", "client", client.getId(), "Cliente cadastrado: " + client.getName());
        return toResponse(client, user.has(Permission.CLIENTS_SENSITIVE_READ));
    }

    public ClientResponse update(AuthenticatedUser user, String id, ClientRequest req) {
        String officeId = user.requireOffice();
        Client client = load(user, id);
        String docDigits = req.document() == null || req.document().isBlank()
                ? crypto.decrypt(client.getDocumentEnc())
                : BrazilianDocuments.digits(req.document());
        validateDocument(req.type(), docDigits);
        String hash = crypto.hmac(docDigits);
        if (!hash.equals(client.getDocumentHash())) {
            if (!user.has(Permission.CLIENTS_SENSITIVE_READ)) {
                throw new BusinessException("SENSITIVE_EDIT_DENIED", "Sem permissão para alterar o CPF/CNPJ do cliente.");
            }
            clients.findByDocumentHash(officeId, hash).filter(c -> !c.getId().equals(id)).ifPresent(c -> {
                throw new ConflictException("CLIENT_DUPLICATE", "Já existe cliente cadastrado com este CPF/CNPJ.");
            });
        }
        validateResponsible(officeId, req.responsibleUid());
        List<String> changed = changedFields(client, req, hash);
        String previousRg = client.getRgEnc();
        apply(client, req, docDigits, hash);
        // Usuários sem acesso a dados sensíveis recebem o RG mascarado; não sobrescrever com vazio.
        if (!user.has(Permission.CLIENTS_SENSITIVE_READ)) {
            client.setRgEnc(previousRg);
        }
        client.setUpdatedAt(Instant.now());
        client.setUpdatedBy(user.uid());
        clients.save(officeId, client);
        audit.record(user, "CLIENT_UPDATED", "client", id, "Cliente atualizado: " + client.getName(),
                Map.of("fields", String.join(",", changed)));
        return toResponse(client, user.has(Permission.CLIENTS_SENSITIVE_READ));
    }

    public ClientResponse inactivate(AuthenticatedUser user, String id, String reason) {
        Client client = load(user, id);
        if (client.getStatus() == ClientStatus.INACTIVE) {
            throw new BusinessException("ALREADY_INACTIVE", "Cliente já está inativo.");
        }
        client.setStatus(ClientStatus.INACTIVE);
        client.setInactivatedAt(Instant.now());
        client.setInactivationReason(reason);
        touch(user, client);
        clients.save(user.officeId(), client);
        audit.record(user, "CLIENT_INACTIVATED", "client", id, "Cliente inativado", Map.of("reason", reason));
        return toResponse(client, user.has(Permission.CLIENTS_SENSITIVE_READ));
    }

    public ClientResponse reactivate(AuthenticatedUser user, String id) {
        Client client = load(user, id);
        if (client.getStatus() == ClientStatus.ACTIVE) {
            throw new BusinessException("ALREADY_ACTIVE", "Cliente já está ativo.");
        }
        client.setStatus(ClientStatus.ACTIVE);
        client.setInactivatedAt(null);
        client.setInactivationReason(null);
        touch(user, client);
        clients.save(user.officeId(), client);
        audit.record(user, "CLIENT_REACTIVATED", "client", id, "Cliente reativado");
        return toResponse(client, user.has(Permission.CLIENTS_SENSITIVE_READ));
    }

    public ClientResponse setDefaulting(AuthenticatedUser user, String id, boolean defaulting) {
        Client client = load(user, id);
        client.setDefaulting(defaulting);
        client.setDefaultingAuto(false);
        touch(user, client);
        clients.save(user.officeId(), client);
        audit.record(user, defaulting ? "CLIENT_MARKED_DEFAULTING" : "CLIENT_UNMARKED_DEFAULTING", "client", id,
                defaulting ? "Cliente marcado como inadimplente" : "Inadimplência removida");
        return toResponse(client, user.has(Permission.CLIENTS_SENSITIVE_READ));
    }

    public ClientInteraction addInteraction(AuthenticatedUser user, String clientId, InteractionRequest req) {
        load(user, clientId);
        ClientInteraction interaction = new ClientInteraction();
        interaction.setClientId(clientId);
        interaction.setChannel(req.channel());
        interaction.setSubject(req.subject().trim());
        interaction.setDescription(trim(req.description()));
        interaction.setOccurredAt(req.occurredAt());
        interaction.setUserUid(user.uid());
        interaction.setUserName(user.displayName());
        interaction.setCreatedAt(Instant.now());
        interaction.setCreatedBy(user.uid());
        clients.saveInteraction(user.officeId(), interaction);
        Client owner = load(user, clientId);
        if (owner.getLastActivityAt() == null || owner.getLastActivityAt().isBefore(req.occurredAt())) {
            owner.setLastActivityAt(req.occurredAt());
            clients.save(user.officeId(), owner);
        }
        audit.record(user, "CLIENT_INTERACTION_ADDED", "client", clientId, "Atendimento registrado: " + interaction.getSubject());
        return interaction;
    }

    public CursorPage<ClientInteraction> interactions(AuthenticatedUser user, String clientId, String cursor, int size) {
        load(user, clientId);
        return clients.interactions(user.officeId(), clientId, cursor, size);
    }

    // ------------------------------------------------------------------ exclusão, duplicidade, fichas e exportação

    /** Coleções com vínculo ao cliente: a exclusão só é permitida quando todas estão vazias. */
    static final Map<String, String> LINKS = new java.util.LinkedHashMap<>(Map.of(
            "cases", "processo(s)", "contracts", "contrato(s)", "financial_transactions", "lançamento(s) financeiro(s)",
            "documents", "documento(s)", "deadlines", "prazo(s)", "appointments", "compromisso(s)"));

    public Map<String, Long> links(AuthenticatedUser user, String id) {
        load(user, id);
        var officeRef = db.collection("offices").document(user.officeId());
        Map<String, com.google.api.core.ApiFuture<com.google.cloud.firestore.AggregateQuerySnapshot>> futures = new java.util.LinkedHashMap<>();
        LINKS.keySet().forEach(col -> futures.put(col, officeRef.collection(col).whereEqualTo("clientId", id).count().get()));
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        futures.forEach((k, f) -> counts.put(k, Futures.await(f).getCount()));
        return counts;
    }

    /**
     * Exclusão definitiva, permitida apenas para cadastros sem processos, contratos, financeiro, documentos,
     * prazos ou compromissos (obrigações de guarda). Nos demais casos, o cadastro deve ser arquivado (inativado).
     */
    public void delete(AuthenticatedUser user, String id, String reason) {
        Client client = load(user, id);
        Map<String, Long> counts = links(user, id);
        List<String> blocking = counts.entrySet().stream().filter(e -> e.getValue() > 0)
                .map(e -> e.getValue() + " " + LINKS.get(e.getKey())).toList();
        if (!blocking.isEmpty()) {
            throw new ConflictException("CLIENT_HAS_LINKS", "Cliente com " + String.join(", ", blocking)
                    + ". Arquive (inative) o cadastro em vez de excluir.");
        }
        var interactionsRef = clients.collection(user.officeId()).document(id).collection("interactions");
        for (var doc : Futures.await(interactionsRef.limit(500).get()).getDocuments()) {
            Futures.await(doc.getReference().delete());
        }
        Futures.await(clients.collection(user.officeId()).document(id).delete());
        audit.record(user, "CLIENT_DELETED", "client", id, "Cliente excluído: " + client.getName(),
                Map.of("reason", reason == null ? "" : reason, "document", client.getDocumentMasked() == null ? "" : client.getDocumentMasked()));
    }

    /** Possíveis duplicidades por CPF/CNPJ, telefone, e-mail ou nome idêntico. */
    public List<br.com.erpadv.dto.ClientDtos.DuplicateMatch> duplicates(AuthenticatedUser user, String name, String document,
                                                                         String phone, String email, String excludeId) {
        String officeId = user.requireOffice();
        Map<String, br.com.erpadv.dto.ClientDtos.DuplicateMatch> found = new java.util.LinkedHashMap<>();
        java.util.function.BiConsumer<String, String> check = (token, reason) -> {
            if (token == null) {
                return;
            }
            for (Client c : clients.list(clients.collection(officeId).whereArrayContains("searchTokens", token), 5)) {
                if (!c.getId().equals(excludeId)) {
                    found.putIfAbsent(c.getId(), new br.com.erpadv.dto.ClientDtos.DuplicateMatch(toSummary(c), reason));
                }
            }
        };
        String digits = BrazilianDocuments.digits(document);
        if ((digits.length() == 11 || digits.length() == 14) && BrazilianDocuments.isValidCpfOrCnpj(digits)) {
            check.accept("d:" + crypto.hmac(digits), "Mesmo CPF/CNPJ");
        }
        String phoneDigits = BrazilianDocuments.digits(phone);
        if (phoneDigits.length() >= 8) {
            check.accept("p:" + phoneDigits.substring(Math.max(0, phoneDigits.length() - 11)), "Mesmo telefone");
        }
        if (email != null && email.contains("@")) {
            check.accept("e:" + email.trim().toLowerCase(Locale.ROOT), "Mesmo e-mail");
        }
        if (name != null && name.trim().length() >= 5) {
            String normalized = SearchTokens.normalize(name);
            for (Client c : clients.list(clients.collection(officeId).whereEqualTo("nameLower", normalized), 5)) {
                if (!c.getId().equals(excludeId)) {
                    found.putIfAbsent(c.getId(), new br.com.erpadv.dto.ClientDtos.DuplicateMatch(toSummary(c), "Mesmo nome"));
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    /** Clientes ativos sem atendimento ou movimentação há {@code days} dias ou mais. */
    public List<ClientSummary> stale(AuthenticatedUser user, int days) {
        String officeId = user.requireOffice();
        long cutoff = Instant.now().minus(Math.max(1, days), java.time.temporal.ChronoUnit.DAYS).toEpochMilli();
        return Futures.await(clients.collection(officeId).whereEqualTo("status", ClientStatus.ACTIVE.name()).limit(5000).get())
                .getDocuments().stream()
                .filter(d -> {
                    Long last = d.getLong("lastActivityAt");
                    Long created = d.getLong("createdAt");
                    return (last != null ? last : created != null ? created : Long.MAX_VALUE) < cutoff;
                })
                .map(d -> clients.findById(officeId, d.getId()).orElse(null))
                .filter(Objects::nonNull)
                .sorted(java.util.Comparator.comparing(c -> c.getNameLower() == null ? "" : c.getNameLower()))
                .limit(500)
                .map(this::toSummary)
                .toList();
    }

    /** Ficha cadastral (PDF ou Excel). CPF/CNPJ completo apenas para quem tem acesso a dados sensíveis. */
    public br.com.erpadv.services.generation.ReportExporter.Exported sheet(AuthenticatedUser user, String id, String format,
                                                                         List<List<String>> cases) {
        Client c = load(user, id);
        boolean sensitive = user.has(Permission.CLIENTS_SENSITIVE_READ);
        ClientResponse r = toResponse(c, sensitive);
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
        List<List<String>> fields = new ArrayList<>();
        java.util.function.BiConsumer<String, String> add = (k, v) -> {
            if (v != null && !v.isBlank()) {
                fields.add(List.of(k, v));
            }
        };
        boolean pj = c.getType() == ClientType.PJ;
        add.accept(pj ? "Razão social" : "Nome completo", c.getName());
        add.accept("Nome fantasia", c.getTradeName());
        add.accept(pj ? "CNPJ" : "CPF", r.document());
        add.accept("RG", sensitive ? r.rg() : null);
        add.accept("Data de nascimento", c.getBirthDate() == null ? null : c.getBirthDate().format(fmt));
        add.accept("Nacionalidade", c.getNationality());
        add.accept("Estado civil", c.getMaritalStatus());
        add.accept("Profissão", c.getProfession());
        add.accept("Inscrição estadual", c.getStateRegistration());
        add.accept("Inscrição municipal", c.getMunicipalRegistration());
        add.accept("Representante legal", c.getLegalRepresentative() == null ? null
                : c.getLegalRepresentative() + (c.getLegalRepresentativeRole() == null ? "" : " (" + c.getLegalRepresentativeRole() + ")"));
        add.accept("Telefone", c.getPhone());
        add.accept("WhatsApp", c.getWhatsapp());
        add.accept("E-mail", c.getEmail());
        if (c.getAddress() != null) {
            var a = c.getAddress();
            String line = java.util.stream.Stream.of(a.getStreet(), a.getNumber(), a.getComplement(), a.getNeighborhood(), a.getCity(),
                    a.getState(), a.getZipCode()).filter(x -> x != null && !x.isBlank()).collect(java.util.stream.Collectors.joining(", "));
            add.accept("Endereço", line);
        }
        add.accept("Situação", c.getStatus() == ClientStatus.ACTIVE ? "Ativo" : "Inativo"
                + (c.getInactivationReason() == null ? "" : " — " + c.getInactivationReason()));
        add.accept("Inadimplente", c.isDefaulting() ? "Sim" : "Não");
        add.accept("Origem", c.getSource() == null ? null : DashboardService.sourceLabel(c.getSource())
                + (c.getSourceDetail() == null ? "" : " — " + c.getSourceDetail()));
        add.accept("Cadastrado em", c.getCreatedAt() == null ? null : c.getCreatedAt().atZone(java.time.ZoneId.of("America/Sao_Paulo")).format(fmt));
        add.accept("Observações", c.getNotes());
        List<br.com.erpadv.services.generation.ReportExporter.Section> sections = new ArrayList<>();
        sections.add(br.com.erpadv.services.generation.ReportExporter.Section.fields("Dados cadastrais", fields));
        if (!c.getContacts().isEmpty()) {
            sections.add(br.com.erpadv.services.generation.ReportExporter.Section.table("Contatos", List.of("Nome", "Função", "Telefone", "E-mail"),
                    c.getContacts().stream().map(x -> List.of(nz(x.getName()), nz(x.getRole()), nz(x.getPhone()), nz(x.getEmail()))).toList()));
        }
        if (cases != null) {
            sections.add(br.com.erpadv.services.generation.ReportExporter.Section.table("Processos vinculados",
                    List.of("Número CNJ", "Área", "Juízo", "Situação"), cases));
        }
        String officeName = offices.findById(user.officeId()).map(br.com.erpadv.models.Office::getName).orElse("Escritório");
        audit.record(user, "CLIENT_SHEET_EXPORTED", "client", id, "Ficha cadastral gerada: " + c.getName(),
                Map.of("format", format == null ? "pdf" : format, "sensitive", String.valueOf(sensitive)));
        return br.com.erpadv.services.generation.ReportExporter.export(
                new br.com.erpadv.services.generation.ReportExporter.Report("Ficha cadastral do cliente", officeName,
                        c.getName(), sections, false), format, "ficha-cliente-" + SearchTokens.normalize(c.getName()).replace(' ', '-'));
    }

    /** Exporta a lista (filtros atuais ou seleção) — dados mascarados; uso registrado na auditoria. */
    public br.com.erpadv.services.generation.ReportExporter.Exported export(AuthenticatedUser user, String search, ClientStatus status,
                                                                          ClientType type, Boolean defaulting, List<String> ids,
                                                                          String format) {
        String officeId = user.requireOffice();
        List<ClientSummary> rows = new ArrayList<>();
        if (ids != null && !ids.isEmpty()) {
            ids.stream().limit(1000).forEach(i -> clients.findById(officeId, i).map(this::toSummary).ifPresent(rows::add));
        } else {
            String cursor = null;
            do {
                CursorPage<ClientSummary> page = list(user, search, status, type, defaulting, cursor, 100);
                rows.addAll(page.items());
                cursor = page.nextCursor();
            } while (cursor != null && rows.size() < 5000);
        }
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
        List<List<String>> table = rows.stream().map(c -> List.of(nz(c.name()), c.type() == ClientType.PJ ? "PJ" : "PF",
                nz(c.documentMasked()), nz(c.phone()), nz(c.email()), c.status() == ClientStatus.ACTIVE ? "Ativo" : "Inativo",
                c.defaulting() ? "Sim" : "Não",
                c.createdAt() == null ? "" : c.createdAt().atZone(java.time.ZoneId.of("America/Sao_Paulo")).format(fmt))).toList();
        String officeName = offices.findById(officeId).map(br.com.erpadv.models.Office::getName).orElse("Escritório");
        audit.record(user, "CLIENTS_EXPORTED", "client", "*", "Exportação de clientes (" + rows.size() + ")",
                Map.of("format", format == null ? "pdf" : format, "selection", String.valueOf(ids != null && !ids.isEmpty())));
        String filters = java.util.stream.Stream.of(search == null || search.isBlank() ? null : "busca \"" + search + "\"",
                        status == null ? null : (status == ClientStatus.ACTIVE ? "ativos" : "inativos"),
                        type == null ? null : type.name(), Boolean.TRUE.equals(defaulting) ? "inadimplentes" : null,
                        ids != null && !ids.isEmpty() ? "seleção manual" : null)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.joining(", "));
        return br.com.erpadv.services.generation.ReportExporter.export(new br.com.erpadv.services.generation.ReportExporter.Report(
                "Clientes cadastrados", officeName, rows.size() + " cliente(s)" + (filters.isEmpty() ? "" : " · Filtros: " + filters),
                List.of(br.com.erpadv.services.generation.ReportExporter.Section.table("Clientes",
                        List.of("Nome", "Tipo", "CPF/CNPJ", "Telefone", "E-mail", "Situação", "Inadimplente", "Cadastro"), table)), true),
                format, "clientes");
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    // ------------------------------------------------------------------ helpers

    /** Token de busca: CPF/CNPJ (HMAC), telefone (sufixo) ou prefixo de nome. */
    String searchToken(String term) {
        String digits = BrazilianDocuments.digits(term);
        boolean numeric = term.replaceAll("[\\d\\s().\\-/+]", "").isEmpty();
        if (numeric && (digits.length() == 11 || digits.length() == 14)
                && BrazilianDocuments.isValidCpfOrCnpj(digits)) {
            return "d:" + crypto.hmac(digits);
        }
        if (numeric && digits.length() >= 8) {
            return "p:" + digits;
        }
        if (term.contains("@")) {
            return "e:" + term.trim().toLowerCase(Locale.ROOT);
        }
        return SearchTokens.queryToken(term);
    }

    private String validateDocument(ClientType type, String document) {
        String digits = BrazilianDocuments.digits(document);
        if (type == ClientType.PF && digits.length() != 11) {
            throw new BusinessException("DOCUMENT_TYPE_MISMATCH", "Pessoa física deve ter CPF.");
        }
        if (type == ClientType.PJ && digits.length() != 14) {
            throw new BusinessException("DOCUMENT_TYPE_MISMATCH", "Pessoa jurídica deve ter CNPJ.");
        }
        return digits;
    }

    private void validateResponsible(String officeId, String responsibleUid) {
        if (responsibleUid != null && !responsibleUid.isBlank()
                && users.findInOffice(officeId, responsibleUid).isEmpty()) {
            throw new BusinessException("INVALID_RESPONSIBLE", "Responsável não pertence ao escritório.");
        }
    }

    private void apply(Client client, ClientRequest req, String docDigits, String hash) {
        client.setType(req.type());
        client.setName(req.name().trim());
        client.setNameLower(SearchTokens.normalize(req.name()));
        client.setTradeName(trim(req.tradeName()));
        client.setDocumentEnc(crypto.encrypt(docDigits));
        client.setDocumentHash(hash);
        client.setDocumentMasked(BrazilianDocuments.mask(docDigits));
        client.setRgEnc(req.rg() == null || req.rg().isBlank() ? null : crypto.encrypt(req.rg().trim()));
        client.setBirthDate(req.birthDate());
        client.setPhone(trim(req.phone()));
        client.setWhatsapp(trim(req.whatsapp()));
        client.setEmail(req.email() == null || req.email().isBlank() ? null : req.email().trim().toLowerCase(Locale.ROOT));
        client.setAddress(req.address());
        client.setProfession(trim(req.profession()));
        client.setMaritalStatus(trim(req.maritalStatus()));
        client.setNotes(trim(req.notes()));
        client.setResponsibleUid(trim(req.responsibleUid()));
        client.setNationality(trim(req.nationality()));
        boolean pj = req.type() == ClientType.PJ;
        client.setStateRegistration(pj ? trim(req.stateRegistration()) : null);
        client.setMunicipalRegistration(pj ? trim(req.municipalRegistration()) : null);
        client.setLegalRepresentative(pj ? trim(req.legalRepresentative()) : null);
        client.setLegalRepresentativeRole(pj ? trim(req.legalRepresentativeRole()) : null);
        List<br.com.erpadv.models.ClientContact> contacts = new ArrayList<>();
        if (req.contacts() != null) {
            for (var c : req.contacts()) {
                var contact = new br.com.erpadv.models.ClientContact();
                contact.setName(c.name().trim());
                contact.setRole(trim(c.role()));
                contact.setPhone(trim(c.phone()));
                contact.setEmail(c.email() == null || c.email().isBlank() ? null : c.email().trim().toLowerCase(Locale.ROOT));
                contacts.add(contact);
            }
        }
        client.setContacts(contacts);
        client.setSource(trim(req.source()));
        client.setSourceDetail(trim(req.sourceDetail()));
        client.setSearchTokens(buildTokens(client, docDigits, hash));
    }

    private List<String> buildTokens(Client client, String docDigits, String hash) {
        Set<String> tokens = new LinkedHashSet<>();
        tokens.add("d:" + hash);
        if (client.getEmail() != null) {
            tokens.add("e:" + client.getEmail());
        }
        tokens.addAll(SearchTokens.forPhone(client.getPhone()));
        tokens.addAll(SearchTokens.forPhone(client.getWhatsapp()));
        tokens.addAll(SearchTokens.forText(client.getName()));
        tokens.addAll(SearchTokens.forText(client.getTradeName()));
        return new ArrayList<>(tokens).subList(0, Math.min(tokens.size(), MAX_TOKENS));
    }

    private List<String> changedFields(Client c, ClientRequest r, String newHash) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(c.getName(), r.name().trim())) changed.add("name");
        if (!Objects.equals(c.getTradeName(), trim(r.tradeName()))) changed.add("tradeName");
        if (!Objects.equals(c.getType(), r.type())) changed.add("type");
        if (!Objects.equals(c.getDocumentHash(), newHash)) changed.add("document");
        if (!Objects.equals(c.getBirthDate(), r.birthDate())) changed.add("birthDate");
        if (!Objects.equals(c.getPhone(), trim(r.phone()))) changed.add("phone");
        if (!Objects.equals(c.getWhatsapp(), trim(r.whatsapp()))) changed.add("whatsapp");
        if (!Objects.equals(c.getEmail(), r.email() == null ? null : r.email().trim().toLowerCase(Locale.ROOT))) changed.add("email");
        if (!Objects.equals(c.getAddress(), r.address())) changed.add("address");
        if (!Objects.equals(c.getProfession(), trim(r.profession()))) changed.add("profession");
        if (!Objects.equals(c.getMaritalStatus(), trim(r.maritalStatus()))) changed.add("maritalStatus");
        if (!Objects.equals(c.getNotes(), trim(r.notes()))) changed.add("notes");
        if (!Objects.equals(c.getResponsibleUid(), trim(r.responsibleUid()))) changed.add("responsibleUid");
        if (!Objects.equals(c.getNationality(), trim(r.nationality()))) changed.add("nationality");
        if (!Objects.equals(c.getLegalRepresentative(), trim(r.legalRepresentative()))) changed.add("legalRepresentative");
        if (!Objects.equals(c.getSource(), trim(r.source()))) changed.add("source");
        if (r.contacts() != null && r.contacts().size() != c.getContacts().size()) changed.add("contacts");
        return changed;
    }

    private void touch(AuthenticatedUser user, Client client) {
        client.setUpdatedAt(Instant.now());
        client.setUpdatedBy(user.uid());
    }

    private ClientSummary toSummary(Client c) {
        return new ClientSummary(c.getId(), c.getType(), c.getName(), c.getDocumentMasked(), c.getPhone(),
                c.getEmail(), c.getStatus(), c.isDefaulting(), c.getCreatedAt());
    }

    ClientResponse toResponse(Client c, boolean sensitive) {
        String document = sensitive ? BrazilianDocuments.format(crypto.decrypt(c.getDocumentEnc())) : c.getDocumentMasked();
        String rg = c.getRgEnc() == null ? null : sensitive ? crypto.decrypt(c.getRgEnc()) : "***";
        return new ClientResponse(c.getId(), c.getType(), c.getName(), c.getTradeName(), document,
                c.getDocumentMasked(), rg, sensitive, c.getBirthDate(), c.getPhone(), c.getWhatsapp(), c.getEmail(),
                c.getAddress(), c.getProfession(), c.getMaritalStatus(), c.getNotes(), c.getStatus(), c.isDefaulting(),
                c.getResponsibleUid(), c.getInactivatedAt(), c.getInactivationReason(), c.getCreatedAt(),
                c.getUpdatedAt(), c.getNationality(), c.getStateRegistration(), c.getMunicipalRegistration(),
                c.getLegalRepresentative(), c.getLegalRepresentativeRole(), c.getContacts(), c.getSource(),
                c.getSourceDetail(), c.getLastActivityAt());
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
