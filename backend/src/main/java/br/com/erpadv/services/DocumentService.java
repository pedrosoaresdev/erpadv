package br.com.erpadv.services;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.audit.RequestInfo;
import br.com.erpadv.dto.CursorPage;
import br.com.erpadv.dto.DocumentDtos.DocumentFilter;
import br.com.erpadv.dto.DocumentDtos.DocumentMeta;
import br.com.erpadv.dto.DocumentDtos.DocumentResponse;
import br.com.erpadv.dto.DocumentDtos.FileContent;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.ForbiddenException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.firebase.FileInspector;
import br.com.erpadv.firebase.SearchTokens;
import br.com.erpadv.firebase.StorageGateway;
import br.com.erpadv.models.Client;
import br.com.erpadv.models.DocumentAccessLog;
import br.com.erpadv.models.DocumentVersion;
import br.com.erpadv.models.LegalCase;
import br.com.erpadv.models.StoredDocument;
import br.com.erpadv.repositories.ClientRepository;
import br.com.erpadv.repositories.DocumentRepositories;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Permission;
import com.google.cloud.firestore.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Repositório documental: upload validado, versionamento imutável, controle de acesso (sigilo,
 * restrição criminal), histórico de acesso por documento e trilha de auditoria. Arquivos nunca são
 * excluídos — documentos são arquivados (política de retenção / sigilo profissional).
 */
@Service
public class DocumentService {

    public static final long MAX_BYTES = 25L * 1024 * 1024;
    public static final Set<FileInspector.FileKind> ALLOWED = EnumSet.allOf(FileInspector.FileKind.class);

    private final DocumentRepositories.Documents documents;
    private final DocumentRepositories.Versions versions;
    private final DocumentRepositories.AccessLogs accessLogs;
    private final ClientRepository clients;
    private final CaseService cases;
    private final StorageGateway storage;
    private final AuditService audit;

    public DocumentService(DocumentRepositories.Documents documents, DocumentRepositories.Versions versions,
                           DocumentRepositories.AccessLogs accessLogs, ClientRepository clients, CaseService cases,
                           StorageGateway storage, AuditService audit) {
        this.documents = documents;
        this.versions = versions;
        this.accessLogs = accessLogs;
        this.clients = clients;
        this.cases = cases;
        this.storage = storage;
        this.audit = audit;
    }

    public CursorPage<DocumentResponse> list(AuthenticatedUser user, DocumentFilter filter, String cursor, int size) {
        Query q = documents.collection(user.requireOffice());
        if (!user.has(Permission.DOCUMENTS_CONFIDENTIAL)) {
            q = q.whereEqualTo("confidential", false);
        }
        if (!user.has(Permission.CRIMINAL_READ)) {
            q = q.whereEqualTo("restricted", false);
        }
        if (!filter.includeArchived()) {
            q = q.whereEqualTo("archived", false);
        }
        if (filter.category() != null) {
            q = q.whereEqualTo("category", filter.category().name());
        }
        if (notBlank(filter.clientId())) {
            q = q.whereEqualTo("clientId", filter.clientId());
        }
        if (notBlank(filter.caseId())) {
            q = q.whereEqualTo("caseId", filter.caseId());
        }
        if (notBlank(filter.search())) {
            String token = SearchTokens.queryToken(filter.search());
            if (token == null) {
                return new CursorPage<>(List.of(), null);
            }
            q = q.whereArrayContains("searchTokens", token);
        }
        return documents.page(q, "updatedAt", Query.Direction.DESCENDING, cursor, size).map(DocumentResponse::of);
    }

    public DocumentResponse get(AuthenticatedUser user, String id) {
        return DocumentResponse.of(load(user, id));
    }

    public DocumentResponse upload(AuthenticatedUser user, DocumentMeta meta, byte[] bytes, String originalName) {
        return DocumentResponse.of(store(user, meta, bytes, originalName, null));
    }

    /** Usado também por contratos (minuta exportada e contrato assinado). */
    StoredDocument store(AuthenticatedUser user, DocumentMeta meta, byte[] bytes, String originalName, String contractId) {
        String officeId = user.requireOffice();
        FileInspector.Inspection file = FileInspector.inspect(bytes, originalName, MAX_BYTES, ALLOWED);
        StoredDocument d = new StoredDocument();
        d.setId(documents.collection(officeId).document().getId());
        applyMeta(user, d, meta);
        d.setContractId(contractId);
        Instant now = Instant.now();
        d.setCreatedAt(now);
        d.setCreatedBy(user.uid());
        DocumentVersion v = putVersion(user, d, 1, bytes, file, "Versão inicial");
        documents.save(officeId, d);
        versions.save(officeId, v);
        log(user, d, "UPLOAD");
        audit.record(user, "DOCUMENT_UPLOADED", "document", d.getId(), d.getCategory().label() + ": " + d.getTitle(),
                Map.of("sha256", file.sha256(), "size", String.valueOf(file.size()), "confidential", String.valueOf(d.isConfidential())));
        return d;
    }

    public DocumentResponse newVersion(AuthenticatedUser user, String id, byte[] bytes, String originalName, String note) {
        StoredDocument d = load(user, id);
        if (d.isArchived()) {
            throw new BusinessException("DOCUMENT_ARCHIVED", "Desarquive o documento para enviar nova versão.");
        }
        FileInspector.Inspection file = FileInspector.inspect(bytes, originalName, MAX_BYTES, ALLOWED);
        if (file.sha256().equals(d.getSha256())) {
            throw new BusinessException("SAME_CONTENT", "O arquivo enviado é idêntico à versão atual.");
        }
        int next = d.getCurrentVersion() + 1;
        DocumentVersion v = putVersion(user, d, next, bytes, file, note == null || note.isBlank() ? null : note.trim());
        documents.save(user.officeId(), d);
        versions.save(user.officeId(), v);
        log(user, d, "NEW_VERSION");
        audit.record(user, "DOCUMENT_VERSION_ADDED", "document", id, "Versão " + next + " de " + d.getTitle(),
                Map.of("sha256", file.sha256()));
        return DocumentResponse.of(d);
    }

    public DocumentResponse update(AuthenticatedUser user, String id, DocumentMeta meta) {
        StoredDocument d = load(user, id);
        applyMeta(user, d, meta);
        d.setUpdatedAt(Instant.now());
        d.setUpdatedBy(user.uid());
        documents.save(user.officeId(), d);
        log(user, d, "UPDATE");
        audit.record(user, "DOCUMENT_UPDATED", "document", id, "Metadados atualizados: " + d.getTitle());
        return DocumentResponse.of(d);
    }

    public DocumentResponse setArchived(AuthenticatedUser user, String id, boolean archived) {
        StoredDocument d = load(user, id);
        d.setArchived(archived);
        d.setUpdatedAt(Instant.now());
        d.setUpdatedBy(user.uid());
        documents.save(user.officeId(), d);
        log(user, d, archived ? "ARCHIVE" : "UNARCHIVE");
        audit.record(user, archived ? "DOCUMENT_ARCHIVED" : "DOCUMENT_UNARCHIVED", "document", id, d.getTitle());
        return DocumentResponse.of(d);
    }

    public List<DocumentVersion> versions(AuthenticatedUser user, String id) {
        load(user, id);
        return versions.list(versions.collection(user.officeId()).whereEqualTo("documentId", id)
                .orderBy("version", Query.Direction.DESCENDING), 200);
    }

    public CursorPage<DocumentAccessLog> accessLog(AuthenticatedUser user, String id, String cursor, int size) {
        load(user, id);
        return accessLogs.page(accessLogs.collection(user.officeId()).whereEqualTo("documentId", id),
                "createdAt", Query.Direction.DESCENDING, cursor, size);
    }

    /** Conteúdo de uma versão (atual quando {@code version} é nulo); registra VIEW ou DOWNLOAD. */
    public FileContent content(AuthenticatedUser user, String id, Integer version, boolean download) {
        StoredDocument d = load(user, id);
        String path = d.getStoragePath();
        String name = d.getFileName();
        String type = d.getContentType();
        int v = d.getCurrentVersion();
        if (version != null && version != d.getCurrentVersion()) {
            DocumentVersion dv = versions.list(versions.collection(user.officeId()).whereEqualTo("documentId", id)
                    .whereEqualTo("version", version), 1).stream().findFirst()
                    .orElseThrow(() -> new NotFoundException("Versão"));
            path = dv.getStoragePath();
            name = dv.getFileName();
            type = dv.getContentType();
            v = dv.getVersion();
        }
        byte[] bytes = storage.get(path);
        log(user, d, download ? "DOWNLOAD" : "VIEW", v);
        audit.record(user, download ? "DOCUMENT_DOWNLOADED" : "DOCUMENT_VIEWED", "document", id,
                d.getTitle() + " (v" + v + ")");
        return new FileContent(bytes, name, type);
    }

    public StoredDocument load(AuthenticatedUser user, String id) {
        StoredDocument d = documents.findById(user.requireOffice(), id).orElseThrow(() -> new NotFoundException("Documento"));
        if ((d.isConfidential() && !user.has(Permission.DOCUMENTS_CONFIDENTIAL))
                || (d.isRestricted() && !user.has(Permission.CRIMINAL_READ))) {
            throw new NotFoundException("Documento");
        }
        return d;
    }

    // ------------------------------------------------------------------ helpers

    private DocumentVersion putVersion(AuthenticatedUser user, StoredDocument d, int number, byte[] bytes,
                                       FileInspector.Inspection file, String note) {
        String path = "offices/" + user.officeId() + "/documents/" + d.getId() + "/v" + number + "/"
                + UUID.randomUUID() + "-" + file.safeName();
        storage.put(path, bytes, file.kind().contentType, Map.of(
                "officeId", user.officeId(), "documentId", d.getId(), "version", String.valueOf(number),
                "uploadedBy", user.uid(), "sha256", file.sha256()));
        Instant now = Instant.now();
        d.setCurrentVersion(number);
        d.setFileName(file.safeName());
        d.setContentType(file.kind().contentType);
        d.setSize(file.size());
        d.setSha256(file.sha256());
        d.setStoragePath(path);
        d.setUploadedBy(user.uid());
        d.setUploadedByName(user.displayName());
        d.setUpdatedAt(now);
        d.setUpdatedBy(user.uid());

        DocumentVersion v = new DocumentVersion();
        v.setDocumentId(d.getId());
        v.setVersion(number);
        v.setStoragePath(path);
        v.setFileName(file.safeName());
        v.setContentType(file.kind().contentType);
        v.setSize(file.size());
        v.setSha256(file.sha256());
        v.setNote(note);
        v.setUploadedBy(user.uid());
        v.setUploadedByName(user.displayName());
        v.setCreatedAt(now);
        v.setCreatedBy(user.uid());
        return v;
    }

    private void applyMeta(AuthenticatedUser user, StoredDocument d, DocumentMeta meta) {
        String officeId = user.officeId();
        boolean confidential = meta.confidential() || meta.category().alwaysConfidential();
        if (confidential && !user.has(Permission.DOCUMENTS_CONFIDENTIAL)) {
            throw new ForbiddenException("CONFIDENTIAL_DENIED", "Sem permissão para documentos sigilosos.");
        }
        boolean restricted = false;
        d.setCaseId(null);
        d.setCaseNumber(null);
        d.setClientId(null);
        d.setClientName(null);
        if (notBlank(meta.caseId())) {
            LegalCase c = cases.load(user, meta.caseId());
            if (c.isRestricted() && !user.has(Permission.CRIMINAL_WRITE)) {
                throw new ForbiddenException("CRIMINAL_ACCESS_DENIED", "Sem permissão para documentos de processo restrito.");
            }
            restricted = c.isRestricted();
            d.setCaseId(c.getId());
            d.setCaseNumber(c.getCnjNumber());
            d.setClientId(c.getClientId());
            d.setClientName(c.getClientName());
        } else if (notBlank(meta.clientId())) {
            Client client = clients.findById(officeId, meta.clientId())
                    .orElseThrow(() -> new BusinessException("INVALID_CLIENT", "Cliente não encontrado."));
            d.setClientId(client.getId());
            d.setClientName(client.getName());
        }
        d.setTitle(meta.title().trim());
        d.setTitleLower(SearchTokens.normalize(meta.title()));
        d.setDescription(meta.description() == null || meta.description().isBlank() ? null : meta.description().trim());
        d.setCategory(meta.category());
        d.setConfidential(confidential);
        d.setRestricted(restricted);
        // Sigilosos nunca são liberados ao portal do cliente.
        d.setSharedWithClient(meta.sharedWithClient() && !confidential && !restricted);
        Set<String> tokens = new LinkedHashSet<>(SearchTokens.forText(d.getTitle()));
        tokens.addAll(SearchTokens.forText(d.getDescription()));
        tokens.addAll(SearchTokens.forText(d.getClientName()));
        if (d.getCaseNumber() != null) {
            tokens.add("c:" + d.getCaseNumber().replaceAll("\\D", ""));
        }
        d.setSearchTokens(tokens.stream().limit(300).toList());
    }

    private DocumentAccessLog log(AuthenticatedUser user, StoredDocument d, String action) {
        return log(user, d, action, d.getCurrentVersion());
    }

    private DocumentAccessLog log(AuthenticatedUser user, StoredDocument d, String action, int version) {
        DocumentAccessLog entry = new DocumentAccessLog();
        entry.setDocumentId(d.getId());
        entry.setVersion(version);
        entry.setAction(action);
        entry.setUid(user.uid());
        entry.setUserName(user.displayName());
        entry.setIp(RequestInfo.ip());
        entry.setCreatedAt(Instant.now());
        entry.setCreatedBy(user.uid());
        try {
            accessLogs.save(user.officeId(), entry);
        } catch (RuntimeException e) {
            // A auditoria geral já registra o acesso; o log por documento é complementar.
        }
        return entry;
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }
}
