package br.com.erpadv.models;

/** "Atrasado" não é persistido: é ABERTO com vencimento anterior a hoje. */
public enum TransactionStatus {
    ABERTO,
    PAGO,
    CANCELADO
}
