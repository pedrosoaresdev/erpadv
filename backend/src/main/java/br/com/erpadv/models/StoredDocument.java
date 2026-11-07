package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Metadados de documento: {@code offices/{officeId}/documents/{id}}. O arquivo fica no Firebase
 * Storage ({@code offices/{officeId}/documents/{id}/v{n}/...}) e só é servido pelo backend.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StoredDocument extends BaseEntity {
    private String title;
    private String titleLower;
    private String description;
    private DocumentCategory category;
    private String clientId;
    private String clientName;
    private String caseId;
    private String caseNumber;
    private String contractId;
    /** Exige DOCUMENTS_CONFIDENTIAL. */
    private boolean confidential;
    /** Herdado de processo criminal/sigiloso: exige CRIMINAL_READ. */
    private boolean restricted;
    /** Liberado para o portal do cliente (Etapa 14). */
    private boolean sharedWithClient;
    private boolean archived;
    private int currentVersion;
    private String fileName;
    private String contentType;
    private long size;
    private String sha256;
    private String storagePath;
    private String uploadedBy;
    private String uploadedByName;
    private List<String> searchTokens = new ArrayList<>();
}
