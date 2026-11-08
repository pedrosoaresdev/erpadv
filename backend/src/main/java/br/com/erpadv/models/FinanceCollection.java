package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Registro de cobrança de valores em atraso ({@code offices/{officeId}/finance_collections/{id}}). */
@Data
@EqualsAndHashCode(callSuper = true)
public class FinanceCollection extends BaseEntity {

    public enum Channel { WHATSAPP, EMAIL, TELEFONE, CARTA, PESSOAL, NOTIFICACAO_EXTRAJUDICIAL }

    public enum Outcome { SEM_RETORNO, PROMESSA_PAGAMENTO, NEGOCIACAO, RECUSA, PAGO }

    private String clientId;
    private String clientName;
    private List<String> transactionIds = new ArrayList<>();
    private long amountCents;
    private Channel channel;
    private Outcome outcome;
    private String message;
    private LocalDate promisedDate;
    private String createdByName;
}
