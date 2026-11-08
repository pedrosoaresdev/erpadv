package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Pagamento de comissões de um período ({@code offices/{officeId}/commission_payouts/{id}}), gerado como despesa. */
@Data
@EqualsAndHashCode(callSuper = true)
public class CommissionPayout extends BaseEntity {
    private String beneficiaryUid;
    private String beneficiaryName;
    private LocalDate periodFrom;
    private LocalDate periodTo;
    private long amountCents;
    /** Recebimentos (receitas) cobertos por este pagamento — evita comissão em dobro. */
    private List<String> receiptIds = new ArrayList<>();
    /** Conta a pagar gerada no financeiro. */
    private String expenseId;
    private String createdByName;
}
