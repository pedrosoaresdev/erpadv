package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** Documento {@code offices/{officeId}} — o tenant do SaaS. */
@Data
@EqualsAndHashCode(callSuper = true)
public class Office extends BaseEntity {
    private String name;
    private String cnpj;
    private String email;
    private String phone;
    private String city;
    private String state;
    private String ownerUid;
    private String plan;
    private String status;
    private String nameLower;
    /** Limite de usuários ativos/convidados (vazio = ilimitado). */
    private Integer maxUsers;
    private java.time.Instant suspendedAt;
    private String suspendedReason;
    /** true quando criado pelo painel master da plataforma. */
    private Boolean createdByPlatform;
}
