package br.com.erpadv.models;

import br.com.erpadv.security.Permission;
import br.com.erpadv.security.Role;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * Cargo personalizado do escritório ({@code offices/{officeId}/roles/{id}}): nome livre
 * (ex.: "Advogado júnior", "Estagiário 2º ano") com um conjunto próprio de permissões. O papel-base
 * define o comportamento do sistema (ex.: se a pessoa aparece como advogado responsável).
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CustomRole extends BaseEntity {
    private String name;
    private String nameLower;
    private String description;
    private String color;
    private Role baseRole;
    private List<Permission> permissions = new ArrayList<>();
}
