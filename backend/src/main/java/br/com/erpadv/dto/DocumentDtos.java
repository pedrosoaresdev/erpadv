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
            boolean sharedWithClient) {
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
            Instant updatedAt) {

        public static DocumentResponse of(StoredDocument d) {
            return new DocumentResponse(d.getId(), d.getTitle(), d.getDescription(), d.getCategory(),
                    d.getCategory() == null ? null : d.getCategory().label(), d.getClientId(), d.getClientName(),
                    d.getCaseId(), d.getCaseNumber(), d.getContractId(), d.isConfidential(), d.isRestricted(),
                    d.isSharedWithClient(), d.isArchived(), d.getCurrentVersion(), d.getFileName(), d.getContentType(),
                    d.getSize(), d.getSha256(), d.getUploadedByName(), d.getCreatedAt(), d.getUpdatedAt());
        }
    }

    public record DocumentFilter(String search, DocumentCategory category, String clientId, String caseId,
                                 boolean includeArchived) {
    }

    public record FileContent(byte[] bytes, String fileName, String contentType) {
    }
}
