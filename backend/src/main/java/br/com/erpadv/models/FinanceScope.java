package br.com.erpadv.models;

/**
 * Separação patrimonial: lançamentos do escritório versus finanças pessoais de um sócio. Lançamentos
 * pessoais são visíveis apenas ao próprio sócio e nunca entram em relatórios, DRE ou dashboard do escritório.
 */
public enum FinanceScope {
    ESCRITORIO,
    PESSOAL
}
