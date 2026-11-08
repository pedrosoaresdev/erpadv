package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** Modelo personalizado do escritório ({@code offices/{officeId}/legal_templates/{id}}). */
@Data
@EqualsAndHashCode(callSuper = true)
public class LegalTemplate extends BaseEntity {
    private String name;
    private String nameLower;
    private LegalDocKind kind;
    private String description;
    /** HTML saneado com campos {{grupo.campo}}. */
    private String content;
    private String createdByName;
}
