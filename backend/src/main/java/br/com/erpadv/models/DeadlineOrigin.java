package br.com.erpadv.models;

/** Origem do prazo (de onde veio a intimação/ciência). */
public enum DeadlineOrigin {
    PUBLICACAO_DJE("Publicação no Diário de Justiça (DJe/DJEN)"),
    INTIMACAO_ELETRONICA("Intimação eletrônica (portal/sistema do tribunal)"),
    INTIMACAO_PESSOAL("Intimação pessoal / por oficial"),
    CITACAO("Citação"),
    AUDIENCIA("Intimação em audiência"),
    DESPACHO("Despacho / decisão"),
    CONTRATUAL("Prazo contratual / extrajudicial"),
    OUTRO("Outra origem");

    private final String label;

    DeadlineOrigin(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
