package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** Modelo de mensagem ({@code offices/{officeId}/message_templates/{id}}) com campos {{grupo.campo}}. */
@Data
@EqualsAndHashCode(callSuper = true)
public class MessageTemplate extends BaseEntity {
    private String name;
    private Communication.Channel channel;
    private String subject;
    private String body;
    /** Nome do modelo aprovado na Meta (WhatsApp), quando o envio for fora da janela de 24 h. */
    private String whatsappTemplate;
    private String createdByName;
}
