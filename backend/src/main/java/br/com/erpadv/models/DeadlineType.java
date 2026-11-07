package br.com.erpadv.models;

public enum DeadlineType {
    CONTESTACAO("Contestação"),
    REPLICA("Réplica"),
    RECURSO("Recurso"),
    CONTRARRAZOES("Contrarrazões"),
    EMBARGOS_DECLARACAO("Embargos de declaração"),
    MANIFESTACAO("Manifestação"),
    CUMPRIMENTO("Cumprimento de decisão"),
    RESPOSTA_ACUSACAO("Resposta à acusação"),
    ALEGACOES_FINAIS("Alegações finais"),
    PRAZO_ADMINISTRATIVO("Prazo administrativo"),
    OUTRO("Outro");

    private final String label;

    DeadlineType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
