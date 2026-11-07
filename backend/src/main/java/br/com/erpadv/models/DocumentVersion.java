package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** Versão de documento: {@code offices/{officeId}/document_versions/{id}}. Versões nunca são apagadas. */
@Data
@EqualsAndHashCode(callSuper = true)
public class DocumentVersion extends BaseEntity {
    private String documentId;
    private int version;
    private String storagePath;
    private String fileName;
    private String contentType;
    private long size;
    private String sha256;
    private String note;
    private String uploadedBy;
    private String uploadedByName;
}
