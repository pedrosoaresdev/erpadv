package br.com.erpadv.validation;

/** Validação de CPF, CNPJ e número único de processo (CNJ — Resolução 65/2008). */
public final class BrazilianDocuments {

    private BrazilianDocuments() {
    }

    public static String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    public static boolean isValidCpf(String value) {
        String cpf = digits(value);
        if (cpf.length() != 11 || cpf.chars().distinct().count() == 1) {
            return false;
        }
        int d1 = cpfDigit(cpf, 9);
        int d2 = cpfDigit(cpf, 10);
        return d1 == cpf.charAt(9) - '0' && d2 == cpf.charAt(10) - '0';
    }

    private static int cpfDigit(String cpf, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += (cpf.charAt(i) - '0') * (length + 1 - i);
        }
        int mod = (sum * 10) % 11;
        return mod == 10 ? 0 : mod;
    }

    public static boolean isValidCnpj(String value) {
        String cnpj = digits(value);
        if (cnpj.length() != 14 || cnpj.chars().distinct().count() == 1) {
            return false;
        }
        int[] w1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] w2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        return cnpjDigit(cnpj, w1) == cnpj.charAt(12) - '0' && cnpjDigit(cnpj, w2) == cnpj.charAt(13) - '0';
    }

    private static int cnpjDigit(String cnpj, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (cnpj.charAt(i) - '0') * weights[i];
        }
        int mod = sum % 11;
        return mod < 2 ? 0 : 11 - mod;
    }

    public static boolean isValidCpfOrCnpj(String value) {
        String d = digits(value);
        return d.length() == 11 ? isValidCpf(d) : d.length() == 14 && isValidCnpj(d);
    }

    /** Máscara que preserva apenas os dígitos finais: ***.***.789-09 / **.***.***\/0001-81. */
    public static String mask(String value) {
        String d = digits(value);
        if (d.length() == 11) {
            return "***.***." + d.substring(6, 9) + "-" + d.substring(9);
        }
        if (d.length() == 14) {
            return "**.***.***/" + d.substring(8, 12) + "-" + d.substring(12);
        }
        return "***";
    }

    public static String format(String value) {
        String d = digits(value);
        if (d.length() == 11) {
            return d.substring(0, 3) + "." + d.substring(3, 6) + "." + d.substring(6, 9) + "-" + d.substring(9);
        }
        if (d.length() == 14) {
            return d.substring(0, 2) + "." + d.substring(2, 5) + "." + d.substring(5, 8) + "/" + d.substring(8, 12) + "-" + d.substring(12);
        }
        return value;
    }

    /**
     * Número CNJ: NNNNNNN-DD.AAAA.J.TR.OOOO. Dígito verificador pelo módulo 97 (ISO 7064):
     * DD = 98 − ((NNNNNNN AAAA J TR OOOO × 100) mod 97).
     */
    public static boolean isValidCnj(String value) {
        String d = digits(value);
        if (d.length() != 20) {
            return false;
        }
        String n = d.substring(0, 7);
        int dv = Integer.parseInt(d.substring(7, 9));
        String rest = d.substring(9); // AAAA J TR OOOO
        return cnjCheckDigits(n + rest) == dv;
    }

    static int cnjCheckDigits(String numberWithoutDv) {
        String full = numberWithoutDv + "00";
        int mod = 0;
        for (char c : full.toCharArray()) {
            mod = (mod * 10 + (c - '0')) % 97;
        }
        return 98 - mod;
    }

    public static String formatCnj(String value) {
        String d = digits(value);
        if (d.length() != 20) {
            return value;
        }
        return d.substring(0, 7) + "-" + d.substring(7, 9) + "." + d.substring(9, 13) + "." + d.charAt(13) + "."
                + d.substring(14, 16) + "." + d.substring(16);
    }
}
