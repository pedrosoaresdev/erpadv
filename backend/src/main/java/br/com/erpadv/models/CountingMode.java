package br.com.erpadv.models;

public enum CountingMode {
    /** Dias úteis (CPC, art. 219). */
    UTEIS,
    /** Dias corridos com prorrogação do termo final (ex.: CPP, art. 798). */
    CORRIDOS,
    /** Data de vencimento informada manualmente. */
    MANUAL
}
