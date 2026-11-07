package br.com.erpadv.models;

/** Plano de contas gerencial simplificado (receitas e despesas do escritório). */
public enum FinanceCategory {
    // Receitas
    HONORARIOS(FinanceDirection.RECEITA, "Honorários", "Receita de serviços jurídicos"),
    HONORARIOS_EXITO(FinanceDirection.RECEITA, "Honorários de êxito", "Receita de serviços jurídicos"),
    CONSULTORIA(FinanceDirection.RECEITA, "Consultorias", "Receita de serviços jurídicos"),
    CONTRATO(FinanceDirection.RECEITA, "Contratos (partido mensal)", "Receita de serviços jurídicos"),
    SERVICO_JURIDICO(FinanceDirection.RECEITA, "Serviços jurídicos avulsos", "Receita de serviços jurídicos"),
    REEMBOLSO(FinanceDirection.RECEITA, "Reembolso de despesas", "Outras receitas"),
    OUTRA_RECEITA(FinanceDirection.RECEITA, "Outras receitas", "Outras receitas"),
    // Despesas
    IMPOSTOS(FinanceDirection.DESPESA, "Impostos sobre receita", "Deduções"),
    ALUGUEL(FinanceDirection.DESPESA, "Aluguel e condomínio", "Despesas de ocupação"),
    ENERGIA(FinanceDirection.DESPESA, "Energia", "Despesas de ocupação"),
    INTERNET(FinanceDirection.DESPESA, "Internet e telefonia", "Despesas de ocupação"),
    PESSOAL(FinanceDirection.DESPESA, "Funcionários e encargos", "Despesas com pessoal"),
    PRO_LABORE(FinanceDirection.DESPESA, "Pró-labore", "Despesas com pessoal"),
    SISTEMAS(FinanceDirection.DESPESA, "Sistemas e tecnologia", "Despesas administrativas"),
    MARKETING(FinanceDirection.DESPESA, "Marketing", "Despesas comerciais"),
    FORNECEDORES(FinanceDirection.DESPESA, "Fornecedores", "Despesas administrativas"),
    CUSTAS(FinanceDirection.DESPESA, "Custas e despesas processuais", "Despesas operacionais"),
    OPERACIONAL(FinanceDirection.DESPESA, "Despesas operacionais", "Despesas operacionais"),
    OUTRA_DESPESA(FinanceDirection.DESPESA, "Outras despesas", "Despesas operacionais");

    private final FinanceDirection direction;
    private final String label;
    private final String group;

    FinanceCategory(FinanceDirection direction, String label, String group) {
        this.direction = direction;
        this.label = label;
        this.group = group;
    }

    public FinanceDirection direction() {
        return direction;
    }

    public String label() {
        return label;
    }

    /** Grupo da DRE gerencial. */
    public String group() {
        return group;
    }

    public boolean isFee() {
        return this == HONORARIOS || this == HONORARIOS_EXITO;
    }
}
