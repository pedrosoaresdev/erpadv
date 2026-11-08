package br.com.erpadv.dto;

import br.com.erpadv.models.LegalDocKind;
import br.com.erpadv.models.LegalDraft;
import br.com.erpadv.models.LegalDraftVersion;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class LegalEditorDtos {

    private LegalEditorDtos() {
    }

    public record TemplateResponse(String id, String name, LegalDocKind kind, String kindLabel, String description, boolean system,
                                   String content, Instant updatedAt, String createdByName) {
    }

    public record TemplateRequest(@NotBlank @Size(max = 120) String name, @NotNull LegalDocKind kind,
                                  @Size(max = 300) String description, @NotBlank @Size(max = 400_000) String content) {
    }

    public record FieldInfo(String key, String label, String group) {
    }

    public record FieldsResponse(List<FieldInfo> catalog, Map<String, String> values) {
    }

    public record CreateRequest(@Size(max = 200) String title, LegalDocKind kind, @Size(max = 64) String templateId,
                                @Size(max = 128) String clientId, @Size(max = 128) String caseId, @Size(max = 128) String lawyerUid,
                                /** Nulo = papel timbrado padrão do escritório; "" = sem papel timbrado. */
                                @Size(max = 128) String letterheadId) {

        public CreateRequest(String title, LegalDocKind kind, String templateId, String clientId, String caseId, String lawyerUid) {
            this(title, kind, templateId, clientId, caseId, lawyerUid, null);
        }
    }

    public record LetterheadMeta(@NotBlank @Size(max = 80) String name,
                                 @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(120) int marginTop,
                                 @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(120) int marginBottom,
                                 @jakarta.validation.constraints.Min(5) @jakarta.validation.constraints.Max(80) int marginLeft,
                                 @jakarta.validation.constraints.Min(5) @jakarta.validation.constraints.Max(80) int marginRight,
                                 boolean isDefault) {
    }

    public record LetterheadResponse(String id, String name, boolean hasPdf, int marginTop, int marginBottom, int marginLeft,
                                     int marginRight, boolean isDefault, String createdByName, Instant createdAt) {
        public static LetterheadResponse of(br.com.erpadv.models.Letterhead l) {
            return new LetterheadResponse(l.getId(), l.getName(), l.getPdfPath() != null, l.getMarginTop(), l.getMarginBottom(),
                    l.getMarginLeft(), l.getMarginRight(), l.isPrimary(), l.getCreatedByName(), l.getCreatedAt());
        }
    }

    public record UpdateRequest(@NotBlank @Size(max = 200) String title, @NotNull LegalDocKind kind,
                                @Size(max = 400_000) String content, @Size(max = 600) String header, @Size(max = 600) String footer,
                                boolean pageNumbers, @Size(max = 128) String clientId, @Size(max = 128) String caseId,
                                @Size(max = 128) String letterheadId) {

        public UpdateRequest(String title, LegalDocKind kind, String content, String header, String footer, boolean pageNumbers,
                             String clientId, String caseId) {
            this(title, kind, content, header, footer, pageNumbers, clientId, caseId, null);
        }
    }

    public record SnapshotRequest(@Size(max = 200) String note) {
    }

    public record SaveToCentralRequest(@Size(max = 8) String format) {
    }

    public record DraftSummary(String id, String title, LegalDocKind kind, String kindLabel, LegalDraft.Status status, String clientId,
                               String clientName, String caseId, String caseNumber, int version, String lawyerName,
                               String updatedByName, Instant updatedAt, Instant createdAt, String storedDocumentId) {
        public static DraftSummary of(LegalDraft d) {
            return new DraftSummary(d.getId(), d.getTitle(), d.getKind(), d.getKind() == null ? null : d.getKind().label(), d.getStatus(),
                    d.getClientId(), d.getClientName(), d.getCaseId(), d.getCaseNumber(), d.getVersion(), d.getLawyerName(),
                    d.getUpdatedByName(), d.getUpdatedAt(), d.getCreatedAt(), d.getStoredDocumentId());
        }
    }

    public record DraftResponse(String id, String title, LegalDocKind kind, String kindLabel, LegalDraft.Status status, String templateId,
                                String templateName, String clientId, String clientName, String caseId, String caseNumber,
                                String lawyerUid, String lawyerName, String content, String header, String footer, boolean pageNumbers,
                                String letterheadId, int version, Instant finalizedAt, String finalizedByName, String storedDocumentId,
                                String createdByName, String updatedByName, Instant createdAt, Instant updatedAt, List<String> missingFields) {
        public static DraftResponse of(LegalDraft d, List<String> missing) {
            return new DraftResponse(d.getId(), d.getTitle(), d.getKind(), d.getKind() == null ? null : d.getKind().label(), d.getStatus(),
                    d.getTemplateId(), d.getTemplateName(), d.getClientId(), d.getClientName(), d.getCaseId(), d.getCaseNumber(),
                    d.getLawyerUid(), d.getLawyerName(), d.getContent(), d.getHeader(), d.getFooter(), d.isPageNumbers(),
                    d.getLetterheadId(), d.getVersion(),
                    d.getFinalizedAt(), d.getFinalizedByName(), d.getStoredDocumentId(), d.getCreatedByName(), d.getUpdatedByName(),
                    d.getCreatedAt(), d.getUpdatedAt(), missing);
        }
    }

    public record VersionSummary(String id, int version, String title, LegalDraft.Status status, String note, String createdByName,
                                 Instant createdAt, int words) {
    }

    public record VersionResponse(String id, int version, String title, String content, String header, String footer,
                                  boolean pageNumbers, LegalDraft.Status status, String note, String createdByName, Instant createdAt) {
        public static VersionResponse of(LegalDraftVersion v) {
            return new VersionResponse(v.getId(), v.getVersion(), v.getTitle(), v.getContent(), v.getHeader(), v.getFooter(),
                    v.isPageNumbers(), v.getStatus(), v.getNote(), v.getCreatedByName(), v.getCreatedAt());
        }
    }
}
