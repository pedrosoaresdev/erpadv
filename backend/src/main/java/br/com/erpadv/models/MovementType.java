package br.com.erpadv.models;

public enum MovementType {
    MOVIMENTACAO("Movimentação"),
    DESPACHO("Despacho"),
    DECISAO("Decisão"),
    SENTENCA("Sentença"),
    ACORDAO("Acórdão"),
    AUDIENCIA("Audiência"),
    PETICAO("Petição"),
    NOTA_INTERNA("Nota interna"),
    SISTEMA("Registro do sistema");

    private final String label;

    MovementType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
