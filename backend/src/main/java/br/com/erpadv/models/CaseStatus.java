package br.com.erpadv.models;

public enum CaseStatus {
    EM_ANDAMENTO("Em andamento", true),
    AGUARDANDO_JULGAMENTO("Aguardando julgamento", true),
    EM_RECURSO("Em recurso", true),
    SUSPENSO("Suspenso", true),
    ENCERRADO("Encerrado", false),
    ARQUIVADO("Arquivado", false);

    private final String label;
    private final boolean active;

    CaseStatus(String label, boolean active) {
        this.label = label;
        this.active = active;
    }

    public String label() {
        return label;
    }

    public boolean isActive() {
        return active;
    }
}
