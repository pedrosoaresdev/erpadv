package br.com.erpadv.models;

/** Tipos de peça/documento do Editor Jurídico, com a categoria usada ao salvar na central documental. */
public enum LegalDocKind {
    PROCURACAO("Procuração", DocumentCategory.PROCURACAO),
    CONTRATO_HONORARIOS("Contrato de honorários", DocumentCategory.CONTRATO),
    PETICAO_INICIAL("Petição inicial", DocumentCategory.PETICAO),
    CONTESTACAO("Contestação", DocumentCategory.PETICAO),
    REPLICA("Réplica", DocumentCategory.PETICAO),
    RECURSO("Recurso", DocumentCategory.RECURSO),
    CONTRARRAZOES("Contrarrazões", DocumentCategory.RECURSO),
    DECLARACAO_HIPOSSUFICIENCIA("Declaração de hipossuficiência", DocumentCategory.DOCUMENTO_PESSOAL),
    SUBSTABELECIMENTO("Substabelecimento", DocumentCategory.PROCURACAO),
    NOTIFICACAO_EXTRAJUDICIAL("Notificação extrajudicial", DocumentCategory.OUTRO),
    REQUERIMENTO("Requerimento", DocumentCategory.PETICAO),
    PARECER("Parecer", DocumentCategory.OUTRO),
    RELATORIO_PROCESSUAL("Relatório processual", DocumentCategory.OUTRO),
    OUTRO("Outro documento", DocumentCategory.OUTRO);

    private final String label;
    private final DocumentCategory category;

    LegalDocKind(String label, DocumentCategory category) {
        this.label = label;
        this.category = category;
    }

    public String label() {
        return label;
    }

    public DocumentCategory category() {
        return category;
    }
}
