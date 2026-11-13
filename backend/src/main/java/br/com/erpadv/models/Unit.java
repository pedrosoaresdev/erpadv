package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** Unidade ou filial do escritório ({@code offices/{officeId}/units}). */
@Data
@EqualsAndHashCode(callSuper = true)
public class Unit extends BaseEntity {
    private String name;
    private String cnpj;
    private String phone;
    private String email;
    private Address address;
    private String managerUid;
    private String managerName;
    private boolean active = true;
}
