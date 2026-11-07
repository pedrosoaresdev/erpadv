package br.com.erpadv.models;

public enum DocumentCategory {
    PROCURACAO("Procurações", false),
    CONTRATO("Contratos", false),
    PETICAO("Petições", false),
    RECURSO("Recursos", false),
    DECISAO("Decisões", false),
    SENTENCA("Sentenças", false),
    DOCUMENTO_PESSOAL("Documentos pessoais", false),
    COMPROVANTE("Comprovantes", false),
    LAUDO("Laudos", false),
    INQUERITO("Inquéritos", true),
    SIGILOSO("Documentos sigilosos", true),
    OUTRO("Outros", false);

    private final String label;
    /** Categorias sempre tratadas como sigilosas. */
    private final boolean alwaysConfidential;

    DocumentCategory(String label, boolean alwaysConfidential) {
        this.label = label;
        this.alwaysConfidential = alwaysConfidential;
    }

    public String label() {
        return label;
    }

    public boolean alwaysConfidential() {
        return alwaysConfidential;
    }
}
