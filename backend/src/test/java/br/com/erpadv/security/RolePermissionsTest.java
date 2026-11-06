package br.com.erpadv.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RolePermissionsTest {

    @Test
    void clienteSoAcessaPortal() {
        assertThat(RolePermissions.defaultsFor(Role.CLIENTE)).containsExactly(Permission.PORTAL_ACCESS);
        // Concessões nunca dão permissões internas a clientes.
        assertThat(RolePermissions.effective(Role.CLIENTE, List.of(Permission.CLIENTS_READ), List.of()))
                .containsExactly(Permission.PORTAL_ACCESS);
    }

    @Test
    void estagiarioNaoVeDadosSensiveisNemCriminal() {
        var perms = RolePermissions.defaultsFor(Role.ESTAGIARIO);
        assertThat(perms).contains(Permission.CASES_READ)
                .doesNotContain(Permission.CLIENTS_SENSITIVE_READ, Permission.CRIMINAL_READ, Permission.FINANCE_READ);
    }

    @Test
    void financeiroNaoAcessaProcessos() {
        assertThat(RolePermissions.defaultsFor(Role.FINANCEIRO))
                .contains(Permission.FINANCE_READ)
                .doesNotContain(Permission.CASES_READ);
    }

    @Test
    void socioNaoTemAdministracaoDePlataforma() {
        assertThat(RolePermissions.defaultsFor(Role.SOCIO_ADMIN))
                .contains(Permission.EMPLOYEES_MANAGE, Permission.AUDIT_READ)
                .doesNotContain(Permission.PLATFORM_ADMIN, Permission.PORTAL_ACCESS);
        assertThat(RolePermissions.effective(Role.SOCIO_ADMIN, List.of(Permission.PLATFORM_ADMIN), List.of()))
                .doesNotContain(Permission.PLATFORM_ADMIN);
    }

    @Test
    void concessoesERevogacoesIndividuais() {
        var perms = RolePermissions.effective(Role.ADVOGADO_ASSOCIADO,
                List.of(Permission.CRIMINAL_READ), List.of(Permission.CLIENTS_SENSITIVE_READ));
        assertThat(perms).contains(Permission.CRIMINAL_READ).doesNotContain(Permission.CLIENTS_SENSITIVE_READ);
    }
}
