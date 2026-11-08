package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Documento do Editor Jurídico ({@code offices/{officeId}/legal_documents/{id}}), em rascunho ou finalizado. */
@Data
@EqualsAndHashCode(callSuper = true)
public class LegalDraft extends BaseEntity {

    public enum Status { RASCUNHO, FINAL }

    private String title;
    private String titleLower;
    private LegalDocKind kind;
    private String templateId;
    private String templateName;
    private String clientId;
    private String clientName;
    private String caseId;
    private String caseNumber;
    /** Herdado de processo criminal/sigiloso: visível só para quem tem CRIMINAL_READ. */
    private boolean restricted;
    private String lawyerUid;
    private String lawyerName;
    /** HTML saneado. */
    private String content;
    private String header;
    private String footer;
    private boolean pageNumbers = true;
    /** Papel timbrado aplicado (nulo = folha em branco). */
    private String letterheadId;
    private Status status = Status.RASCUNHO;
    /** Número da última versão registrada no histórico. */
    private int version;
    private Instant finalizedAt;
    private String finalizedByName;
    /** Documento gerado na central documental (PDF/DOCX), quando salvo lá. */
    private String storedDocumentId;
    private String createdByName;
    private String updatedByName;
    private List<String> searchTokens = new ArrayList<>();
}
