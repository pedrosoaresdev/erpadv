package br.com.erpadv.models;

import br.com.erpadv.security.Permission;
import br.com.erpadv.security.Role;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Documento {@code users/{uid}}. O id é o uid do Firebase Authentication. */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserProfile extends BaseEntity {
    private String email;
    private String displayName;
    private String displayNameLower;
    private Role role;
    private UserStatus status;
    private List<Permission> permissionsGranted = new ArrayList<>();
    private List<Permission> permissionsRevoked = new ArrayList<>();
    private String oabNumber;
    private String phone;
    private String position;
    /** CPF criptografado (cadastro pelo painel master / equipe). */
    private String cpfEnc;
    private String cpfHash;
    private String cpfMasked;
    /** Cargo personalizado do escritório (permissões vêm do cargo). */
    private String customRoleId;
    private String customRoleName;
    /** Preenchido apenas para usuários do portal do cliente. */
    private String clientId;
    private Instant invitedAt;
    private String invitedBy;
    private Instant activatedAt;
    private Instant lastLoginAt;
    private Instant blockedAt;
    private String blockedReason;
    /** Tokens emitidos antes deste instante são rejeitados (revogação de sessões). */
    private Instant tokensRevokedAt;
}
