package br.com.erpadv.validation;

import br.com.erpadv.exceptions.BusinessException;

/** Política mínima de senha definida pelo administrador: 8+ caracteres com letras e números. */
public final class PasswordPolicy {

    private PasswordPolicy() {
    }

    public static boolean isStrong(String password) {
        if (password == null || password.length() < 8 || password.length() > 128) {
            return false;
        }
        boolean letter = password.chars().anyMatch(Character::isLetter);
        boolean digit = password.chars().anyMatch(Character::isDigit);
        return letter && digit && !password.isBlank();
    }

    public static void check(String password) {
        if (!isStrong(password)) {
            throw new BusinessException("WEAK_PASSWORD", "A senha deve ter ao menos 8 caracteres, com letras e números.");
        }
    }
}
