package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** Documento {@code offices/{officeId}} — o tenant do SaaS. */
@Data
@EqualsAndHashCode(callSuper = true)
public class Office extends BaseEntity {
    private String name;
    private String cnpj;
    private String email;
    private String phone;
    private String city;
    private String state;
    private String ownerUid;
    private String plan;
    private String status;
    private String nameLower;
    /** Limite de usuários ativos/convidados (vazio = ilimitado). */
    private Integer maxUsers;
    private java.time.Instant suspendedAt;
    private String suspendedReason;
    /** true quando criado pelo painel master da plataforma. */
    private Boolean createdByPlatform;

    // ---- configurações do escritório (Fase 6)
    private Address address;
    private String website;
    /** Registro da sociedade de advogados na OAB. */
    private String oabRegistration;
    private String logoPath;
    private String logoContentType;
    private java.time.Instant logoUpdatedAt;
    /** Módulos do plano que o próprio escritório preferiu desligar. */
    private java.util.List<String> disabledModules = new java.util.ArrayList<>();
    private boolean onboardingDismissed;
    /** Atalhos para sistemas dos tribunais (PJe, eproc…); nulo = lista padrão verificada. */
    private java.util.List<SystemLink> systemLinks;

    @lombok.Data
    public static class SystemLink {
        private String id;
        private String name;
        private String url;
        /** Sigla do tribunal (ex.: TJPE) para abrir a partir do processo. */
        private String court;
        private String description;
    }

    // ---- assinatura e licença (Fase 6, cobrança manual pelo painel master)
    /** MENSAL ou ANUAL. */
    private String billingCycle;
    /** TRIAL, ATIVA, EM_ATRASO, SUSPENSA ou CANCELADA. */
    private String subscriptionStatus;
    private java.time.LocalDate trialEndsAt;
    private java.time.LocalDate nextInvoiceDate;
    /** Preço negociado (substitui o do plano quando preenchido). */
    private Long priceOverrideCents;
    private String billingEmail;
    /** Dias de tolerância após o vencimento antes da suspensão (padrão 10). */
    private Integer graceDays;
    /** Limite de armazenamento negociado em MB (substitui o do plano). */
    private Integer maxStorageMb;
    /** Suspensão causada por fatura em atraso (reativa ao registrar o pagamento). */
    private boolean billingSuspended;
    /** Espaço ocupado pelos arquivos da central documental (todas as versões). */
    private Long storageBytes;
}
