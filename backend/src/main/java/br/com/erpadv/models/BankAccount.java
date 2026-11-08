package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * Conta bancária ou caixa do escritório ({@code offices/{officeId}/bank_accounts/{id}}). O saldo é calculado a
 * partir do saldo inicial e das baixas registradas na conta (não há conexão automática com o banco).
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BankAccount extends BaseEntity {

    public enum Kind { CORRENTE, POUPANCA, PAGAMENTO, CAIXA, INVESTIMENTO }

    private String name;
    private String bankName;
    private String bankCode;
    private String agency;
    /** Somente os 4 últimos dígitos são exibidos fora do cadastro. */
    private String accountNumber;
    private Kind kind = Kind.CORRENTE;
    private long initialBalanceCents;
    private LocalDate initialBalanceDate;
    private boolean active = true;
}
