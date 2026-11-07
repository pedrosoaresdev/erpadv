package br.com.erpadv.security;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static br.com.erpadv.security.Permission.*;

/** Matriz padrão Papel → Permissões (RBAC). Ajustes por usuário são feitos com concessões/revogações. */
public final class RolePermissions {

    private static final Map<Role, Set<Permission>> MATRIX = new EnumMap<>(Role.class);

    static {
        MATRIX.put(Role.MASTER, EnumSet.complementOf(EnumSet.of(PORTAL_ACCESS)));
        MATRIX.put(Role.SOCIO_ADMIN, EnumSet.complementOf(EnumSet.of(PORTAL_ACCESS, PLATFORM_ADMIN)));
        MATRIX.put(Role.ADVOGADO, EnumSet.of(
                DASHBOARD_VIEW, CLIENTS_READ, CLIENTS_WRITE, CLIENTS_SENSITIVE_READ,
                CASES_READ, CASES_WRITE, CASES_ARCHIVE, DEADLINES_READ, DEADLINES_WRITE,
                CALENDAR_READ, CALENDAR_WRITE, CONTRACTS_READ, CONTRACTS_WRITE,
                DOCUMENTS_READ, DOCUMENTS_WRITE, DOCUMENTS_CONFIDENTIAL, CRIMINAL_READ, CRIMINAL_WRITE,
                AI_USE, MESSAGES_USE, REPORTS_VIEW, EMPLOYEES_READ, BOARDS_USE, CRM_USE));
        MATRIX.put(Role.ADVOGADO_ASSOCIADO, EnumSet.of(
                DASHBOARD_VIEW, CLIENTS_READ, CLIENTS_WRITE, CLIENTS_SENSITIVE_READ,
                CASES_READ, CASES_WRITE, DEADLINES_READ, DEADLINES_WRITE, CALENDAR_READ, CALENDAR_WRITE,
                CONTRACTS_READ, DOCUMENTS_READ, DOCUMENTS_WRITE, AI_USE, MESSAGES_USE, EMPLOYEES_READ, BOARDS_USE, CRM_USE));
        MATRIX.put(Role.ESTAGIARIO, EnumSet.of(
                DASHBOARD_VIEW, CLIENTS_READ, CASES_READ, DEADLINES_READ, CALENDAR_READ,
                DOCUMENTS_READ, AI_USE, MESSAGES_USE, BOARDS_USE));
        MATRIX.put(Role.ASSISTENTE_JURIDICO, EnumSet.of(
                DASHBOARD_VIEW, CLIENTS_READ, CLIENTS_WRITE, CASES_READ, CASES_WRITE,
                DEADLINES_READ, DEADLINES_WRITE, CALENDAR_READ, CALENDAR_WRITE,
                DOCUMENTS_READ, DOCUMENTS_WRITE, AI_USE, MESSAGES_USE, BOARDS_USE));
        MATRIX.put(Role.SECRETARIA, EnumSet.of(
                DASHBOARD_VIEW, CLIENTS_READ, CLIENTS_WRITE, CASES_READ, DEADLINES_READ,
                CALENDAR_READ, CALENDAR_WRITE, DOCUMENTS_READ, MESSAGES_USE, BOARDS_USE, CRM_USE));
        MATRIX.put(Role.FINANCEIRO, EnumSet.of(
                DASHBOARD_VIEW, FINANCE_DASHBOARD_VIEW, FINANCE_READ, FINANCE_WRITE,
                CONTRACTS_READ, CLIENTS_READ, REPORTS_VIEW, MESSAGES_USE, BOARDS_USE));
        MATRIX.put(Role.ADMINISTRATIVO, EnumSet.of(
                DASHBOARD_VIEW, CLIENTS_READ, CASES_READ, CALENDAR_READ, CALENDAR_WRITE,
                EMPLOYEES_READ, MESSAGES_USE, REPORTS_VIEW, BOARDS_USE, CRM_USE));
        MATRIX.put(Role.TI_SUPORTE, EnumSet.of(
                ADMIN_PANEL, AUDIT_READ, EMPLOYEES_READ, EMPLOYEES_MANAGE, SETTINGS_MANAGE, MESSAGES_USE, BOARDS_USE));
        MATRIX.put(Role.CLIENTE, EnumSet.of(PORTAL_ACCESS));
    }

    private RolePermissions() {
    }

    public static Set<Permission> defaultsFor(Role role) {
        if (role == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(MATRIX.get(role));
    }

    /**
     * Permissões efetivas = padrão do papel + concessões − revogações. Clientes do portal nunca
     * recebem permissões internas, e permissões de plataforma só existem para o MASTER.
     */
    /** Permissões de um cargo personalizado (nunca incluem plataforma ou portal). */
    public static Set<Permission> effectiveCustom(Collection<Permission> rolePermissions, Collection<Permission> granted,
                                                  Collection<Permission> revoked) {
        EnumSet<Permission> result = EnumSet.noneOf(Permission.class);
        if (rolePermissions != null) {
            result.addAll(rolePermissions);
        }
        if (granted != null) {
            result.addAll(granted);
        }
        if (revoked != null) {
            result.removeAll(revoked);
        }
        result.remove(PORTAL_ACCESS);
        result.remove(PLATFORM_ADMIN);
        return Collections.unmodifiableSet(result);
    }

    public static Set<Permission> effective(Role role, Collection<Permission> granted, Collection<Permission> revoked) {
        if (role == null) {
            return Collections.emptySet();
        }
        EnumSet<Permission> result = EnumSet.copyOf(MATRIX.get(role));
        if (role != Role.CLIENTE && granted != null) {
            result.addAll(granted);
            result.remove(PORTAL_ACCESS);
            if (role != Role.MASTER) {
                result.remove(PLATFORM_ADMIN);
            }
        }
        if (revoked != null) {
            result.removeAll(revoked);
        }
        return Collections.unmodifiableSet(result);
    }
}
