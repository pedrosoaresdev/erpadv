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
}
