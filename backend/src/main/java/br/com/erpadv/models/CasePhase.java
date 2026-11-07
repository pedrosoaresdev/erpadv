package br.com.erpadv.models;

/** Fase processual (independente da situação: um processo em andamento pode estar em execução). */
public enum CasePhase {
    POSTULATORIA("Postulatória"),
    INSTRUTORIA("Instrução"),
    DECISORIA("Sentença/decisão"),
    RECURSAL("Recursal"),
    CUMPRIMENTO("Cumprimento de sentença"),
    EXECUCAO("Execução"),
    ENCERRAMENTO("Encerramento");

    private final String label;

    CasePhase(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
