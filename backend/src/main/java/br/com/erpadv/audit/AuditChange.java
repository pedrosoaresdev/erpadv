package br.com.erpadv.audit;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Um campo alterado: valor anterior e posterior em texto (truncado). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditChange {
    private String field;
    private String before;
    private String after;
}
