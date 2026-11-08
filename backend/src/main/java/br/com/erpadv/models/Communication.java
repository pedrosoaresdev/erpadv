package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Registro de comunicação com cliente ou terceiro ({@code offices/{officeId}/communications/{id}}). */
@Data
@EqualsAndHashCode(callSuper = true)
public class Communication extends BaseEntity {

    public enum Channel { EMAIL, WHATSAPP, TELEFONE, PRESENCIAL, CARTA, INTERNO }

    public enum Direction { SAIDA, ENTRADA }

    public enum Status { ENVIADO, FALHOU, RECEBIDO, REGISTRADO }

    private Channel channel;
    private Direction direction = Direction.SAIDA;
    private Status status;
    private String clientId;
    private String clientName;
    private String caseId;
    private String caseNumber;
    /** E-mail ou telefone do destinatário/remetente. */
    private String address;
    private String subject;
    private String body;
    private String error;
    private List<String> attachments = new ArrayList<>();
    private String externalId;
    private String templateName;
    /** Lembrete de retorno ao cliente. */
    private LocalDate followUpDate;
    private boolean followUpDone;
    private boolean followUpNotified;
    private String createdByName;
    private boolean restricted;
}
