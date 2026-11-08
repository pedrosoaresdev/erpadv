package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** Versão (instantâneo) de um documento do editor ({@code offices/{officeId}/legal_document_versions/{id}}). */
@Data
@EqualsAndHashCode(callSuper = true)
public class LegalDraftVersion extends BaseEntity {
    private String draftId;
    private int version;
    private String title;
    private String content;
    private String header;
    private String footer;
    private boolean pageNumbers;
    private LegalDraft.Status status;
    private String note;
    private String createdByName;
}
