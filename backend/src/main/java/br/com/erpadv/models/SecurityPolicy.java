package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Política de segurança do escritório ({@code offices/{officeId}/security_settings/policy}). Valores padrão
 * preservam o comportamento anterior: nada é exigido até o administrador ativar.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SecurityPolicy extends BaseEntity {

    public enum MfaRequirement { NENHUM, ADMINISTRADORES, EQUIPE }

    /** Exclusões (e eliminações definitivas) exigem justificativa registrada na auditoria. */
    private boolean requireJustificationOnDelete;
    private MfaRequirement mfaRequirement = MfaRequirement.NENHUM;
    /** Duração máxima da sessão desde o login, em horas (0 = sem limite). */
    private int sessionMaxHours;
    /** Encerramento por inatividade, em minutos (0 = desativado). */
    private int idleMinutes;
    /** Avisar o usuário e registrar evento quando entrar por um dispositivo novo. */
    private boolean newDeviceAlert = true;
    private boolean backupEnabled = true;
    private int backupRetentionDays = 30;
    private String updatedByName;
}
