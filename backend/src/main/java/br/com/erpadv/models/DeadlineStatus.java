package br.com.erpadv.models;

/** "Vencido" não é persistido: é derivado de PENDENTE com vencimento anterior a hoje. */
public enum DeadlineStatus {
    PENDENTE,
    CONCLUIDO,
    CANCELADO
}
