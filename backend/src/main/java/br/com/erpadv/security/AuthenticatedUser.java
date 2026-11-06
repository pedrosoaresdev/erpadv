package br.com.erpadv.security;

import br.com.erpadv.exceptions.ForbiddenException;

import java.util.Set;

/**
 * Principal do Spring Security construído a partir do ID token do Firebase e do perfil do usuário.
 * O {@code officeId} aqui é a única fonte aceita para escopo de dados.
 */
public record AuthenticatedUser(
        String uid,
        String email,
        String name,
        boolean emailVerified,
        String officeId,
        Role role,
        String clientId,
        Set<Permission> permissions) {

    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }

    public boolean hasOffice() {
        return officeId != null && role != null;
    }

    /** Retorna o officeId ou lança 403 se o usuário ainda não pertence a um escritório. */
    public String requireOffice() {
        if (!hasOffice()) {
            throw new ForbiddenException("ONBOARDING_REQUIRED", "Usuário ainda não vinculado a um escritório.");
        }
        return officeId;
    }

    public String displayName() {
        return name != null && !name.isBlank() ? name : email;
    }
}
