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

    public ClientService(ClientRepository clients, UserRepository users, CryptoService crypto, AuditService audit) {
        this.clients = clients;
        this.users = users;
        this.crypto = crypto;
        this.audit = audit;
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
        audit.record(user, "CLIENT_INTERACTION_ADDED", "client", clientId, "Atendimento registrado: " + interaction.getSubject());
        return interaction;
    }

    public CursorPage<ClientInteraction> interactions(AuthenticatedUser user, String clientId, String cursor, int size) {
        load(user, clientId);
        return clients.interactions(user.officeId(), clientId, cursor, size);
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
                c.getUpdatedAt());
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
