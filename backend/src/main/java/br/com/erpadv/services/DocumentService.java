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
    private final DocumentRepositories.Folders folders;
    private final NotificationService notifications;
    private final br.com.erpadv.repositories.UserRepository users;
    /** Limite por arquivo (configurável por DOCUMENTS_MAX_MB). */
    private long maxBytes = MAX_BYTES;

    public DocumentService(DocumentRepositories.Documents documents, DocumentRepositories.Versions versions,
                           DocumentRepositories.AccessLogs accessLogs, ClientRepository clients, CaseService cases,
                           StorageGateway storage, AuditService audit, DocumentRepositories.Folders folders,
                           NotificationService notifications, br.com.erpadv.repositories.UserRepository users,
                           @org.springframework.beans.factory.annotation.Value("${app.documents.max-size-mb:25}") long maxSizeMb) {
        this.folders = folders;
        this.notifications = notifications;
        this.users = users;
        this.maxBytes = Math.max(1, Math.min(maxSizeMb, 200)) * 1024 * 1024;
        this.documents = documents;
        this.versions = versions;
        this.accessLogs = accessLogs;
        this.clients = clients;
        this.cases = cases;
        this.storage = storage;
        this.audit = audit;
    }

    public CursorPage<DocumentResponse> list(AuthenticatedUser user, DocumentFilter filter, String cursor, int size) {
        if (notBlank(filter.folderId()) || filter.favorites() || filter.trash() || notBlank(filter.tag())) {
            return organized(user, filter);
        }
        CursorPage<DocumentResponse> page = basicList(user, filter, cursor, size);
        // Itens da lixeira ficam fora das listagens (mesmo com "incluir arquivados").
        return new CursorPage<>(page.items().stream().filter(d -> !d.deleted()).toList(), page.nextCursor());
    }

    /**
     * Visões da central documental (pasta, favoritos, lixeira, etiqueta): consulta por um único campo e
     * filtros de sigilo/arquivamento em memória (até 500 itens por visão).
     */
    private CursorPage<DocumentResponse> organized(AuthenticatedUser user, DocumentFilter filter) {
        String officeId = user.requireOffice();
        Query q = documents.collection(officeId);
        if (filter.trash()) {
            q = q.whereEqualTo("deleted", true);
        } else if (filter.favorites()) {
            q = q.whereArrayContains("favoriteUids", user.uid());
        } else if (notBlank(filter.folderId())) {
            q = q.whereEqualTo("folderId", filter.folderId());
        } else {
            q = q.whereArrayContains("tags", filter.tag().trim().toLowerCase(java.util.Locale.ROOT));
        }
        String token = notBlank(filter.search()) ? SearchTokens.queryToken(filter.search()) : null;
        List<DocumentResponse> items = documents.list(q, 500).stream()
                .filter(d -> !d.isConfidential() || user.has(Permission.DOCUMENTS_CONFIDENTIAL))
                .filter(d -> !d.isRestricted() || user.has(Permission.CRIMINAL_READ))
                .filter(d -> filter.trash() == d.isDeleted())
                .filter(d -> filter.trash() || filter.includeArchived() || !d.isArchived())
                .filter(d -> filter.category() == null || filter.category() == d.getCategory())
                .filter(d -> !notBlank(filter.clientId()) || filter.clientId().equals(d.getClientId()))
                .filter(d -> !notBlank(filter.caseId()) || filter.caseId().equals(d.getCaseId()))
                .filter(d -> token == null || d.getSearchTokens().contains(token))
                .sorted(java.util.Comparator.comparing((StoredDocument d) -> filter.trash() && d.getDeletedAt() != null
                        ? d.getDeletedAt() : d.getUpdatedAt(), java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .map(d -> DocumentResponse.of(d, user.uid()))
                .toList();
        return new CursorPage<>(items, null);
    }

    private CursorPage<DocumentResponse> basicList(AuthenticatedUser user, DocumentFilter filter, String cursor, int size) {
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
        return documents.page(q, "updatedAt", Query.Direction.DESCENDING, cursor, size).map(d -> DocumentResponse.of(d, user.uid()));
    }

    public DocumentResponse get(AuthenticatedUser user, String id) {
        return DocumentResponse.of(load(user, id), user.uid());
    }

    // ------------------------------------------------------------------ pastas

    public List<br.com.erpadv.dto.DocumentDtos.FolderResponse> folders(AuthenticatedUser user) {
        return folders.list(folders.collection(user.requireOffice()).orderBy("nameLower"), 500).stream()
                .map(f -> new br.com.erpadv.dto.DocumentDtos.FolderResponse(f.getId(), f.getName(), f.getParentId(), f.getColor(), f.getCreatedAt()))
                .toList();
    }

    public br.com.erpadv.dto.DocumentDtos.FolderResponse saveFolder(AuthenticatedUser user, String id,
                                                                  br.com.erpadv.dto.DocumentDtos.FolderRequest req) {
        String officeId = user.requireOffice();
        br.com.erpadv.models.DocumentFolder f = id == null ? new br.com.erpadv.models.DocumentFolder()
                : folders.findById(officeId, id).orElseThrow(() -> new NotFoundException("Pasta"));
        String parent = notBlank(req.parentId()) ? req.parentId() : null;
        if (parent != null) {
            folders.findById(officeId, parent).orElseThrow(() -> new NotFoundException("Pasta superior"));
            // Impede ciclos (mover a pasta para dentro de si mesma ou de uma subpasta).
            String cursor = parent;
            int guard = 0;
            while (cursor != null && guard++ < 50) {
                if (cursor.equals(id)) {
                    throw new BusinessException("FOLDER_CYCLE", "Uma pasta não pode ficar dentro dela mesma.");
                }
                cursor = folders.findById(officeId, cursor).map(br.com.erpadv.models.DocumentFolder::getParentId).orElse(null);
            }
        }
        String previousName = f.getName();
        f.setName(req.name().trim());
        f.setNameLower(SearchTokens.normalize(req.name()));
        f.setParentId(parent);
        f.setColor(notBlank(req.color()) ? req.color() : null);
        Instant now = Instant.now();
        if (id == null) {
            f.setCreatedAt(now);
            f.setCreatedBy(user.uid());
        }
        f.setUpdatedAt(now);
        f.setUpdatedBy(user.uid());
        folders.save(officeId, f);
        audit.record(user, id == null ? "FOLDER_CREATED" : "FOLDER_UPDATED", "folder", f.getId(),
                (id == null ? "Pasta criada: " : "Pasta atualizada: ") + f.getName(),
                Map.of("name", (previousName == null ? "" : previousName + "->") + f.getName()));
        return new br.com.erpadv.dto.DocumentDtos.FolderResponse(f.getId(), f.getName(), f.getParentId(), f.getColor(), f.getCreatedAt());
    }

    public void deleteFolder(AuthenticatedUser user, String id) {
        String officeId = user.requireOffice();
        br.com.erpadv.models.DocumentFolder f = folders.findById(officeId, id).orElseThrow(() -> new NotFoundException("Pasta"));
        boolean hasChildren = !folders.list(folders.collection(officeId).whereEqualTo("parentId", id), 1).isEmpty();
        boolean hasDocs = documents.list(documents.collection(officeId).whereEqualTo("folderId", id), 500).stream()
                .anyMatch(d -> !d.isDeleted());
        if (hasChildren || hasDocs) {
            throw new BusinessException("FOLDER_NOT_EMPTY", "Mova ou exclua os documentos e subpastas antes de excluir a pasta.");
        }
        folders.delete(officeId, id);
        audit.record(user, "FOLDER_DELETED", "folder", id, "Pasta excluída: " + f.getName());
    }

    // ------------------------------------------------------------------ organização e lixeira

    public DocumentResponse move(AuthenticatedUser user, String id, String folderId) {
        StoredDocument d = load(user, id);
        String target = notBlank(folderId) ? folderId : null;
        if (target != null) {
            folders.findById(user.officeId(), target).orElseThrow(() -> new NotFoundException("Pasta"));
        }
        String previous = d.getFolderId();
        d.setFolderId(target);
        touch(user, d);
        documents.save(user.officeId(), d);
        log(user, d, "MOVE");
        audit.record(user, "DOCUMENT_MOVED", "document", id, "Documento movido: " + d.getTitle(),
                Map.of("folder", (previous == null ? "raiz" : previous) + "->" + (target == null ? "raiz" : target)));
        return DocumentResponse.of(d, user.uid());
    }

    /** Copia o documento (versão atual) para outra pasta; o histórico de versões não é copiado. */
    public DocumentResponse copy(AuthenticatedUser user, String id, String folderId) {
        StoredDocument source = load(user, id);
        byte[] bytes = storage.get(source.getStoragePath());
        DocumentMeta meta = new DocumentMeta("Cópia de " + source.getTitle(), source.getDescription(), source.getCategory(),
                source.getClientId(), source.getCaseId(), source.isConfidential(), false,
                notBlank(folderId) ? folderId : source.getFolderId(), source.getTags());
        StoredDocument copy = store(user, meta, bytes, source.getFileName(), source.getContractId());
        audit.record(user, "DOCUMENT_COPIED", "document", copy.getId(), "Cópia de " + source.getTitle(), Map.of("sourceId", id));
        return DocumentResponse.of(copy, user.uid());
    }

    public DocumentResponse favorite(AuthenticatedUser user, String id, boolean on) {
        StoredDocument d = load(user, id);
        List<String> favs = new java.util.ArrayList<>(d.getFavoriteUids() == null ? List.of() : d.getFavoriteUids());
        favs.remove(user.uid());
        if (on) {
            favs.add(user.uid());
        }
        d.setFavoriteUids(favs);
        documents.save(user.officeId(), d);
        return DocumentResponse.of(d, user.uid());
    }

    /** Envia para a lixeira (restaurável). */
    public DocumentResponse trash(AuthenticatedUser user, String id) {
        StoredDocument d = load(user, id);
        d.setDeleted(true);
        d.setArchived(true);
        d.setDeletedAt(Instant.now());
        d.setDeletedBy(user.uid());
        d.setDeletedByName(user.displayName());
        touch(user, d);
        documents.save(user.officeId(), d);
        log(user, d, "TRASH");
        audit.record(user, "DOCUMENT_TRASHED", "document", id, "Documento enviado para a lixeira: " + d.getTitle());
        return DocumentResponse.of(d, user.uid());
    }

    public DocumentResponse restore(AuthenticatedUser user, String id) {
        StoredDocument d = load(user, id);
        if (!d.isDeleted()) {
            throw new BusinessException("NOT_IN_TRASH", "O documento não está na lixeira.");
        }
        d.setDeleted(false);
        d.setArchived(false);
        d.setDeletedAt(null);
        d.setDeletedBy(null);
        d.setDeletedByName(null);
        if (d.getFolderId() != null && folders.findById(user.officeId(), d.getFolderId()).isEmpty()) {
            d.setFolderId(null);
        }
        touch(user, d);
        documents.save(user.officeId(), d);
        log(user, d, "RESTORE");
        audit.record(user, "DOCUMENT_RESTORED", "document", id, "Documento restaurado da lixeira: " + d.getTitle());
        return DocumentResponse.of(d, user.uid());
    }

    /**
     * Eliminação definitiva (somente itens da lixeira): apaga todas as versões no Storage. O registro da
     * exclusão permanece na trilha de auditoria (quem, quando, hash do conteúdo).
     */
    public void purge(AuthenticatedUser user, String id, String reason) {
        StoredDocument d = load(user, id);
        if (!d.isDeleted()) {
            throw new BusinessException("NOT_IN_TRASH", "Envie o documento para a lixeira antes de excluí-lo definitivamente.");
        }
        if (d.getContractId() != null) {
            throw new BusinessException("CONTRACT_DOCUMENT", "Documento vinculado a contrato não pode ser eliminado.");
        }
        String officeId = user.officeId();
        long freed = 0;
        for (DocumentVersion v : versions.list(versions.collection(officeId).whereEqualTo("documentId", id), 200)) {
            freed += v.getSize();
            try {
                storage.delete(v.getStoragePath());
            } catch (RuntimeException ignored) {
                // arquivo já ausente no Storage
            }
            br.com.erpadv.firebase.Futures.await(versions.collection(officeId).document(v.getId()).delete());
        }
        br.com.erpadv.firebase.Futures.await(documents.collection(officeId).document(id).delete());
        if (license != null && freed > 0) {
            license.addStorage(officeId, -freed);
        }
        audit.record(user, "DOCUMENT_PURGED", "document", id, "Documento eliminado definitivamente: " + d.getTitle(),
                Map.of("reason", reason == null ? "" : reason, "sha256", d.getSha256() == null ? "" : d.getSha256(),
                        "versions", String.valueOf(d.getCurrentVersion())));
    }

    /** Compartilhamento interno: notifica integrantes (o acesso continua sujeito às permissões de cada um). */
    public void share(AuthenticatedUser user, String id, List<String> uids, String message) {
        StoredDocument d = load(user, id);
        int sent = 0;
        for (String uid : new LinkedHashSet<>(uids)) {
            if (uid.equals(user.uid()) || users.findInOffice(user.officeId(), uid).filter(u -> u.getRole() != null && u.getRole().isStaff()).isEmpty()) {
                continue;
            }
            notifications.notify(user.officeId(), uid, "DOCUMENT_SHARED", user.displayName() + " compartilhou um documento",
                    d.getTitle() + (notBlank(message) ? " — " + message.trim() : ""), "/documentos?doc=" + id);
            sent++;
        }
        log(user, d, "SHARE");
        audit.record(user, "DOCUMENT_SHARED", "document", id, "Documento compartilhado internamente (" + sent + "): " + d.getTitle());
    }

    /** Documentos com o mesmo conteúdo (SHA-256), para avisar sobre duplicidade antes do envio. */
    public List<DocumentResponse> duplicates(AuthenticatedUser user, String sha256) {
        if (sha256 == null || !sha256.matches("^[0-9a-f]{64}$")) {
            return List.of();
        }
        return documents.list(documents.collection(user.requireOffice()).whereEqualTo("sha256", sha256), 10).stream()
                .filter(d -> !d.isConfidential() || user.has(Permission.DOCUMENTS_CONFIDENTIAL))
                .filter(d -> !d.isRestricted() || user.has(Permission.CRIMINAL_READ))
                .map(d -> DocumentResponse.of(d, user.uid()))
                .toList();
    }

    public long maxBytes() {
        return maxBytes;
    }

    private void touch(AuthenticatedUser user, StoredDocument d) {
        d.setUpdatedAt(Instant.now());
        d.setUpdatedBy(user.uid());
    }

    public DocumentResponse upload(AuthenticatedUser user, DocumentMeta meta, byte[] bytes, String originalName) {
        return DocumentResponse.of(store(user, meta, bytes, originalName, null));
    }

    /** Usado também por contratos (minuta exportada e contrato assinado). */
    StoredDocument store(AuthenticatedUser user, DocumentMeta meta, byte[] bytes, String originalName, String contractId) {
        String officeId = user.requireOffice();
        FileInspector.Inspection file = FileInspector.inspect(bytes, originalName, maxBytes, ALLOWED);
        if (license != null) {
            license.assertStorage(officeId, bytes.length);
        }
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
        if (license != null) {
            license.addStorage(officeId, bytes.length);
        }
        readContent(officeId, d, bytes);
        audit.record(user, "DOCUMENT_UPLOADED", "document", d.getId(), d.getCategory().label() + ": " + d.getTitle(),
                Map.of("sha256", file.sha256(), "size", String.valueOf(file.size()), "confidential", String.valueOf(d.isConfidential())));
        return d;
    }

    public DocumentResponse newVersion(AuthenticatedUser user, String id, byte[] bytes, String originalName, String note) {
        StoredDocument d = load(user, id);
        if (d.isArchived()) {
            throw new BusinessException("DOCUMENT_ARCHIVED", "Desarquive o documento para enviar nova versão.");
        }
        FileInspector.Inspection file = FileInspector.inspect(bytes, originalName, maxBytes, ALLOWED);
        if (file.sha256().equals(d.getSha256())) {
            throw new BusinessException("SAME_CONTENT", "O arquivo enviado é idêntico à versão atual.");
        }
        if (license != null) {
            license.assertStorage(user.officeId(), bytes.length);
        }
        int next = d.getCurrentVersion() + 1;
        DocumentVersion v = putVersion(user, d, next, bytes, file, note == null || note.isBlank() ? null : note.trim());
        documents.save(user.officeId(), d);
        versions.save(user.officeId(), v);
        log(user, d, "NEW_VERSION");
        if (license != null) {
            license.addStorage(user.officeId(), bytes.length);
        }
        readContent(user.officeId(), d, bytes);
        audit.record(user, "DOCUMENT_VERSION_ADDED", "document", id, "Versão " + next + " de " + d.getTitle(),
                Map.of("sha256", file.sha256()));
        return DocumentResponse.of(d);
    }

    public DocumentResponse update(AuthenticatedUser user, String id, DocumentMeta meta) {
        StoredDocument d = load(user, id);
        Map<String, Object> auditBefore = audit.snapshot(d);
        applyMeta(user, d, meta);
        d.setUpdatedAt(Instant.now());
        d.setUpdatedBy(user.uid());
        documents.save(user.officeId(), d);
        log(user, d, "UPDATE");
        audit.recordChange(user, "DOCUMENT_UPDATED", "document", id, "Metadados atualizados: " + d.getTitle(),
                Map.of(), auditBefore, d);
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
        if (notBlank(meta.folderId())) {
            folders.findById(officeId, meta.folderId()).orElseThrow(() -> new NotFoundException("Pasta"));
            d.setFolderId(meta.folderId());
        } else if (meta.folderId() != null) {
            d.setFolderId(null);
        }
        if (meta.tags() != null) {
            d.setTags(meta.tags().stream().filter(DocumentService::notBlank).map(t -> t.trim().toLowerCase(java.util.Locale.ROOT))
                    .distinct().limit(10).toList());
        }
        rebuildTokens(d);
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

    /** Termos de busca: título, descrição, cliente, etiquetas, número do processo e palavras do conteúdo (texto/OCR). */
    static void rebuildTokens(StoredDocument d) {
        Set<String> tokens = new LinkedHashSet<>(SearchTokens.forText(d.getTitle()));
        tokens.addAll(SearchTokens.forText(d.getDescription()));
        tokens.addAll(SearchTokens.forText(d.getClientName()));
        if (d.getTags() != null) {
            d.getTags().forEach(t -> tokens.addAll(SearchTokens.forText(t)));
        }
        if (d.getCaseNumber() != null) {
            tokens.add("c:" + d.getCaseNumber().replaceAll("\\D", ""));
        }
        List<String> base = tokens.stream().limit(300).toList();
        Set<String> all = new LinkedHashSet<>(base);
        if (d.getContentTokens() != null) {
            all.addAll(d.getContentTokens());
        }
        d.setSearchTokens(all.stream().limit(700).toList());
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OcrService ocr;

    /** Texto do arquivo para busca e IA (OCR em segundo plano quando digitalizado); nunca impede o envio. */
    private void readContent(String officeId, StoredDocument d, byte[] bytes) {
        if (ocr == null) {
            return;
        }
        try {
            ocr.process(officeId, d, bytes);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(DocumentService.class).warn("Leitura do conteúdo falhou para {}", d.getId(), e);
        }
    }

    /** Texto extraído do documento (com as mesmas permissões de leitura do arquivo). */
    public OcrService.TextView text(AuthenticatedUser user, String id) {
        StoredDocument d = load(user, id);
        log(user, d, "VIEW_TEXT");
        return ocr.view(user.officeId(), d);
    }

    /** Reprocessa a leitura do conteúdo da versão atual (ex.: depois de instalar o OCR no servidor). */
    public OcrService.TextView reprocess(AuthenticatedUser user, String id) {
        StoredDocument d = load(user, id);
        byte[] bytes = content(user, id, null, false).bytes();
        ocr.process(user.officeId(), d, bytes);
        audit.record(user, "DOCUMENT_OCR_REQUESTED", "document", id, "Leitura do conteúdo reprocessada: " + d.getTitle());
        return ocr.view(user.officeId(), load(user, id));
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private br.com.erpadv.services.platform.LicenseService license;
}
