package br.com.erpadv.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Registros do módulo LGPD (Lei 13.709/2018), todos em subcoleções do escritório. */
public final class Lgpd {

    private Lgpd() {
    }

    /** Bases legais (art. 7º e, para dados sensíveis, art. 11). */
    public enum LegalBasis {
        CONSENTIMENTO("Consentimento do titular (art. 7º, I)"),
        OBRIGACAO_LEGAL("Cumprimento de obrigação legal ou regulatória (art. 7º, II)"),
        POLITICAS_PUBLICAS("Execução de políticas públicas (art. 7º, III)"),
        ESTUDOS("Realização de estudos por órgão de pesquisa (art. 7º, IV)"),
        EXECUCAO_CONTRATO("Execução de contrato ou procedimentos preliminares (art. 7º, V)"),
        EXERCICIO_DIREITOS("Exercício regular de direitos em processo judicial, administrativo ou arbitral (art. 7º, VI)"),
        PROTECAO_VIDA("Proteção da vida ou da incolumidade física (art. 7º, VII)"),
        TUTELA_SAUDE("Tutela da saúde (art. 7º, VIII)"),
        LEGITIMO_INTERESSE("Legítimo interesse do controlador ou de terceiro (art. 7º, IX)"),
        PROTECAO_CREDITO("Proteção do crédito (art. 7º, X)");

        private final String label;

        LegalBasis(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Direitos do titular (art. 18). */
    public enum RequestType {
        CONFIRMACAO_EXISTENCIA("Confirmação da existência de tratamento"),
        ACESSO("Acesso aos dados"),
        CORRECAO("Correção de dados incompletos, inexatos ou desatualizados"),
        ANONIMIZACAO_BLOQUEIO_ELIMINACAO("Anonimização, bloqueio ou eliminação de dados desnecessários ou excessivos"),
        PORTABILIDADE("Portabilidade dos dados"),
        ELIMINACAO_CONSENTIMENTO("Eliminação dos dados tratados com consentimento"),
        INFORMACAO_COMPARTILHAMENTO("Informação sobre compartilhamento com terceiros"),
        INFORMACAO_CONSENTIMENTO("Informação sobre a possibilidade de não consentir"),
        REVOGACAO_CONSENTIMENTO("Revogação do consentimento"),
        REVISAO_DECISAO_AUTOMATIZADA("Revisão de decisão automatizada (art. 20)"),
        OPOSICAO("Oposição a tratamento irregular");

        private final String label;

        RequestType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Entry {
        private Instant at;
        private String byName;
        private String text;
    }

    /** Solicitação de titular (prazo de 15 dias para a declaração completa — art. 19, II). */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class DataRequest extends BaseEntity {
        public enum Status { RECEBIDA, EM_ANALISE, ATENDIDA, NEGADA, CANCELADA }

        private String protocol;
        private RequestType type;
        private String requesterName;
        private String requesterEmail;
        private String requesterPhone;
        private String clientId;
        private String clientName;
        private String channel;
        private String description;
        private LocalDate receivedDate;
        private LocalDate dueDate;
        private boolean identityVerified;
        private Status status = Status.RECEBIDA;
        private String response;
        private Instant respondedAt;
        private String respondedByName;
        private String createdByName;
        private List<Entry> history = new ArrayList<>();
    }

    /** Consentimento registrado (finalidade específica, com prova e revogação). */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Consent extends BaseEntity {
        private String clientId;
        private String clientName;
        private String purpose;
        private String channel;
        private String evidence;
        private String documentId;
        private LocalDate grantedDate;
        private LocalDate expiresDate;
        private boolean revoked;
        private LocalDate revokedDate;
        private String revocationNote;
        private String createdByName;
    }

    /** Registro das operações de tratamento (art. 37) com base legal e finalidade. */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class ProcessingActivity extends BaseEntity {
        private String name;
        private String purpose;
        private LegalBasis legalBasis;
        private boolean sensitiveData;
        private String sensitiveBasis;
        private List<String> dataCategories = new ArrayList<>();
        private List<String> dataSubjects = new ArrayList<>();
        private List<String> sharedWith = new ArrayList<>();
        private String retention;
        private boolean internationalTransfer;
        private String securityMeasures;
        private String responsibleName;
        private boolean active = true;
        /** Criado a partir das sugestões do sistema e ainda não revisado pelo escritório. */
        private boolean suggestion;
    }

    /** Incidente de segurança com dados pessoais (Res. CD/ANPD 15/2024: comunicação em 3 dias úteis quando houver risco relevante). */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Incident extends BaseEntity {
        public enum Risk { BAIXO, MEDIO, ALTO }

        public enum Status { ABERTO, EM_TRATAMENTO, ENCERRADO }

        private String title;
        private String description;
        private Instant discoveredAt;
        private Instant occurredAt;
        private List<String> dataCategories = new ArrayList<>();
        private int affectedSubjects;
        private boolean sensitiveData;
        private Risk risk = Risk.MEDIO;
        private boolean anpdNotificationRequired;
        private LocalDate anpdDeadline;
        private LocalDate anpdNotifiedDate;
        private String anpdProtocol;
        private LocalDate subjectsNotifiedDate;
        private String containmentMeasures;
        private Status status = Status.ABERTO;
        private String createdByName;
        private List<Entry> timeline = new ArrayList<>();
    }

    /** Compartilhamento de dados pessoais com terceiros (controle e prestação de contas). */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class DataShare extends BaseEntity {
        private String recipient;
        private String recipientType;
        private String clientId;
        private String clientName;
        private String caseId;
        private String dataDescription;
        private String purpose;
        private LegalBasis legalBasis;
        private LocalDate sharedDate;
        private String createdByName;
    }

    /** Versão da política de privacidade do escritório. */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class PrivacyPolicy extends BaseEntity {
        private int version;
        private String title;
        private String content;
        private boolean published;
        private Instant publishedAt;
        private String publishedByName;
        private String createdByName;
    }

    /** Prazo de guarda por categoria de documento, em anos (0 = guarda permanente). */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class RetentionRule extends BaseEntity {
        private DocumentCategory category;
        private int years;
        private String legalReference;
    }
}
