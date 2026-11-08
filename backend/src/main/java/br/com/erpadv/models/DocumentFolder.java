package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** Pasta da central documental ({@code offices/{officeId}/document_folders/{id}}), com subpastas por parentId. */
@Data
@EqualsAndHashCode(callSuper = true)
public class DocumentFolder extends BaseEntity {
    private String name;
    private String nameLower;
    private String parentId;
    private String color;
}
