package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** Centro de custo ({@code offices/{officeId}/cost_centers/{id}}) — ex.: Contencioso, Consultivo, Unidade Recife. */
@Data
@EqualsAndHashCode(callSuper = true)
public class CostCenter extends BaseEntity {
    private String name;
    private String nameLower;
    private String code;
    private String description;
    private boolean active = true;
}
