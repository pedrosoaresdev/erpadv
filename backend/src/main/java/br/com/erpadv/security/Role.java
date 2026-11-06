package br.com.erpadv.security;

/** Papéis do sistema. As permissões de cada papel estão em {@link RolePermissions}. */
public enum Role {
    MASTER("Master da plataforma", true, true),
    SOCIO_ADMIN("Sócio administrador", true, true),
    ADVOGADO("Advogado", true, true),
    ADVOGADO_ASSOCIADO("Advogado associado", true, true),
    ESTAGIARIO("Estagiário", true, false),
    ASSISTENTE_JURIDICO("Assistente jurídico", true, false),
    SECRETARIA("Secretária", true, false),
    FINANCEIRO("Financeiro", true, false),
    ADMINISTRATIVO("Administrativo", true, false),
    TI_SUPORTE("TI/Suporte", true, false),
    CLIENTE("Cliente (portal)", false, false);

    private final String label;
    private final boolean staff;
    private final boolean lawyer;

    Role(String label, boolean staff, boolean lawyer) {
        this.label = label;
        this.staff = staff;
        this.lawyer = lawyer;
    }

    public String label() {
        return label;
    }

    /** Integrante da equipe interna (não é cliente do portal). */
    public boolean isStaff() {
        return staff;
    }

    /** Pode ser advogado responsável por processos. */
    public boolean isLawyer() {
        return lawyer && this != MASTER;
    }

    /** Papéis que somente um sócio administrador (ou master) pode atribuir. */
    public boolean isPrivileged() {
        return this == MASTER || this == SOCIO_ADMIN;
    }

    public static Role parse(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Role.valueOf(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
