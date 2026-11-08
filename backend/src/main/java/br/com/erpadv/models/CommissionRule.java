package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * Regra de comissão sobre honorários recebidos ({@code offices/{officeId}/commission_rules/{id}}): percentual para
 * um integrante, opcionalmente restrito a um cliente, a um processo ou aos processos em que ele é o responsável.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CommissionRule extends BaseEntity {
    private String beneficiaryUid;
    private String beneficiaryName;
    private String description;
    /** Percentual em pontos-base (1000 = 10%). */
    private int percentBp;
    private List<FinanceCategory> categories = new ArrayList<>();
    private String clientId;
    private String clientName;
    private String caseId;
    private String caseNumber;
    /** Somente processos em que o beneficiário é o advogado responsável. */
    private boolean responsibleOnly;
    private boolean active = true;
}
