package br.com.erpadv.dto;

import br.com.erpadv.models.DocumentCategory;
import br.com.erpadv.models.StoredDocument;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class DocumentDtos {

    private DocumentDtos() {
    }

    /** Metadados enviados junto com o arquivo (multipart "meta") ou na edição. */
    public record DocumentMeta(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 2000) String description,
            @NotNull DocumentCategory category,
            @Size(max = 128) String clientId,
            @Size(max = 128) String caseId,
            boolean confidential,
            boolean sharedWithClient,
            @Size(max = 128) String folderId,
            @Size(max = 10) java.util.List<@Size(max = 30) String> tags) {

        public DocumentMeta(String title, String description, DocumentCategory category, String clientId, String caseId,
                            boolean confidential, boolean sharedWithClient) {
            this(title, description, category, clientId, caseId, confidential, sharedWithClient, null, null);
        }
    }

    public record FolderRequest(@NotBlank @Size(max = 80) String name, @Size(max = 128) String parentId,
                                @jakarta.validation.constraints.Pattern(regexp = "^$|^#[0-9a-fA-F]{6}$") String color) {
    }

    public record FolderResponse(String id, String name, String parentId, String color, Instant createdAt) {
    }

    public record MoveRequest(@Size(max = 128) String folderId) {
    }

    public record ShareRequest(@jakarta.validation.constraints.NotEmpty @Size(max = 30) java.util.List<String> uids,
                               @Size(max = 500) String message) {
    }

    public record DocumentResponse(
            String id,
            String title,
            String description,
            DocumentCategory category,
            String categoryLabel,
            String clientId,
            String clientName,
            String caseId,
            String caseNumber,
            String contractId,
            boolean confidential,
            boolean restricted,
            boolean sharedWithClient,
            boolean archived,
            int currentVersion,
            String fileName,
            String contentType,
            long size,
            String sha256,
            String uploadedByName,
            Instant createdAt,
            Instant updatedAt,
            String folderId,
            java.util.List<String> tags,
            boolean favorite,
            boolean deleted,
            Instant deletedAt,
            String deletedByName) {

        public static DocumentResponse of(StoredDocument d) {
            return of(d, null);
        }

        public static DocumentResponse of(StoredDocument d, String uid) {
            return new DocumentResponse(d.getId(), d.getTitle(), d.getDescription(), d.getCategory(),
                    d.getCategory() == null ? null : d.getCategory().label(), d.getClientId(), d.getClientName(),
                    d.getCaseId(), d.getCaseNumber(), d.getContractId(), d.isConfidential(), d.isRestricted(),
                    d.isSharedWithClient(), d.isArchived(), d.getCurrentVersion(), d.getFileName(), d.getContentType(),
                    d.getSize(), d.getSha256(), d.getUploadedByName(), d.getCreatedAt(), d.getUpdatedAt(), d.getFolderId(),
                    d.getTags(), uid != null && d.getFavoriteUids() != null && d.getFavoriteUids().contains(uid),
                    d.isDeleted(), d.getDeletedAt(), d.getDeletedByName());
        }
    }

    public record DocumentFilter(String search, DocumentCategory category, String clientId, String caseId,
                                 boolean includeArchived, String folderId, boolean favorites, boolean trash, String tag) {

        public DocumentFilter(String search, DocumentCategory category, String clientId, String caseId, boolean includeArchived) {
            this(search, category, clientId, caseId, includeArchived, null, false, false, null);
        }
    }

    public record FileContent(byte[] bytes, String fileName, String contentType) {
    }
}
