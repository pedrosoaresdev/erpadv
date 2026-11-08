package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Papel timbrado do Editor Jurídico ({@code offices/{officeId}/letterheads/{id}}): imagem A4 (PNG/JPEG) usada como
 * fundo de cada página e, opcionalmente, o PDF original (fundo vetorial na exportação em PDF). As margens (mm)
 * delimitam a área em branco onde o texto é escrito.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Letterhead extends BaseEntity {
    private String name;
    private String imagePath;
    private String imageContentType;
    private String pdfPath;
    private int marginTop = 40;
    private int marginBottom = 30;
    private int marginLeft = 30;
    private int marginRight = 20;
    /** Papel timbrado padrão do escritório (aplicado aos novos documentos). */
    private boolean primary;
    private String createdByName;
}
