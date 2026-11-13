package br.com.erpadv.models;

import br.com.erpadv.security.Permission;

import java.util.Set;

/** Módulos opcionais licenciados por plano. Desligar um módulo retira as permissões dele de todo o escritório. */
public enum AppModule {
    FINANCEIRO("Financeiro, honorários e recibos com valor", Set.of(Permission.FINANCE_READ, Permission.FINANCE_WRITE, Permission.FINANCE_DASHBOARD_VIEW)),
    CONTRATOS("Contratos e honorários contratuais", Set.of(Permission.CONTRACTS_READ, Permission.CONTRACTS_WRITE)),
    CRM("CRM comercial (funil de captação)", Set.of(Permission.CRM_USE)),
    QUADROS("Quadros Kanban e Tarefas", Set.of(Permission.BOARDS_USE)),
    RELATORIOS("Central de relatórios", Set.of(Permission.REPORTS_VIEW)),
    LGPD("Módulo LGPD", Set.of(Permission.LGPD_MANAGE));

    private final String label;
    private final Set<Permission> permissions;

    AppModule(String label, Set<Permission> permissions) {
        this.label = label;
        this.permissions = permissions;
    }

    public String label() {
        return label;
    }

    public Set<Permission> permissions() {
        return permissions;
    }
}
