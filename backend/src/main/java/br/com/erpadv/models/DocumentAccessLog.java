package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** Histórico de acesso: {@code offices/{officeId}/document_access_logs/{id}} (gravado apenas pelo backend). */
@Data
@EqualsAndHashCode(callSuper = true)
public class DocumentAccessLog extends BaseEntity {
    private String documentId;
    private int version;
    /** UPLOAD, NEW_VERSION, VIEW, DOWNLOAD, UPDATE, ARCHIVE, UNARCHIVE. */
    private String action;
    private String uid;
    private String userName;
    private String ip;
}
