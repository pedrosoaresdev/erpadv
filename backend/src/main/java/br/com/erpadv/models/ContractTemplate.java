package br.com.erpadv.models;

import br.com.erpadv.models.ContractEnums.ContractType;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Modelo de documento com campos dinâmicos ({{cliente.nome}}, {{contrato.valor}}…):
 * {@code offices/{officeId}/contract_templates/{id}}. Modelos do sistema têm id "system:*".
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ContractTemplate extends BaseEntity {
    private String name;
    private ContractType type;
    private String description;
    private String body;
    private boolean active = true;
    private boolean system;
}
