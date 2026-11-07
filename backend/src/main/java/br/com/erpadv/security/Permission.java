package br.com.erpadv.security;

/**
 * Permissões granulares por módulo, operação e tipo de informação. Viram authorities
 * {@code PERM_<NOME>} no Spring Security.
 */
public enum Permission {
    DASHBOARD_VIEW("dashboard", "Visualizar dashboard"),
    FINANCE_DASHBOARD_VIEW("dashboard", "Visualizar indicadores financeiros"),

    CLIENTS_READ("clients", "Consultar clientes"),
    CLIENTS_WRITE("clients", "Cadastrar e editar clientes"),
    CLIENTS_SENSITIVE_READ("clients", "Ver CPF/CNPJ e RG completos"),
    CLIENTS_INACTIVATE("clients", "Inativar e reativar clientes"),

    CASES_READ("cases", "Consultar processos"),
    CASES_WRITE("cases", "Cadastrar e editar processos e movimentações"),
    CASES_ARCHIVE("cases", "Arquivar processos"),

    DEADLINES_READ("deadlines", "Consultar prazos"),
    DEADLINES_WRITE("deadlines", "Gerenciar prazos"),
    CALENDAR_READ("calendar", "Consultar agenda"),
    CALENDAR_WRITE("calendar", "Gerenciar agenda"),

    FINANCE_READ("finance", "Consultar financeiro"),
    FINANCE_WRITE("finance", "Lançar e baixar contas"),
    CONTRACTS_READ("contracts", "Consultar contratos e honorários"),
    CONTRACTS_WRITE("contracts", "Gerenciar contratos e honorários"),

    DOCUMENTS_READ("documents", "Consultar e baixar documentos"),
    DOCUMENTS_WRITE("documents", "Enviar e versionar documentos"),
    DOCUMENTS_CONFIDENTIAL("documents", "Acessar documentos sigilosos"),

    CRIMINAL_READ("criminal", "Acessar departamento criminal"),
    CRIMINAL_WRITE("criminal", "Gerenciar departamento criminal"),

    AI_USE("ai", "Usar agentes de inteligência artificial"),
    MESSAGES_USE("communication", "Comunicação interna"),
    BOARDS_USE("boards", "Quadros de tarefas (Kanban)"),
    CRM_USE("crm", "CRM comercial (funil de captação)"),
    REPORTS_VIEW("reports", "Emitir relatórios"),

    EMPLOYEES_READ("employees", "Consultar equipe"),
    EMPLOYEES_MANAGE("employees", "Convidar, bloquear e definir permissões"),
    AUDIT_READ("admin", "Consultar trilha de auditoria"),
    ADMIN_PANEL("admin", "Painel master do escritório"),
    SETTINGS_MANAGE("admin", "Configurar módulos e integrações"),
    PLATFORM_ADMIN("platform", "Administração da plataforma (multiescritório)"),

    PORTAL_ACCESS("portal", "Acesso ao portal do cliente");

    private final String module;
    private final String description;

    Permission(String module, String description) {
        this.module = module;
        this.description = description;
    }

    public String module() {
        return module;
    }

    public String description() {
        return description;
    }

    public String authority() {
        return "PERM_" + name();
    }
}
