package br.com.erpadv.models;

public enum LegalArea {
    CIVEL("Cível"),
    TRABALHISTA("Trabalhista"),
    CRIMINAL("Criminal"),
    TRIBUTARIO("Tributário"),
    FAMILIA("Família e Sucessões"),
    PREVIDENCIARIO("Previdenciário"),
    EMPRESARIAL("Empresarial"),
    CONSUMIDOR("Consumidor"),
    ADMINISTRATIVO("Administrativo"),
    AMBIENTAL("Ambiental"),
    OUTRO("Outro");

    private final String label;

    LegalArea(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
