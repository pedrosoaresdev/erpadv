package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Chamado de suporte do escritório para a plataforma ({@code support_tickets}, coleção da plataforma). */
@Data
@EqualsAndHashCode(callSuper = true)
public class SupportTicket extends BaseEntity {
    public enum Status { ABERTO, EM_ATENDIMENTO, AGUARDANDO_ESCRITORIO, RESOLVIDO }

    private String number;
    private String officeName;
    private String subject;
    /** DUVIDA, PROBLEMA, PLANO, FINANCEIRO ou SUGESTAO. */
    private String category;
    /** BAIXA, NORMAL ou ALTA. */
    private String priority;
    private Status status = Status.ABERTO;
    private String openedByUid;
    private String openedByName;
    private String openedByEmail;
    private Instant lastMessageAt;
    private boolean awaitingPlatform = true;
    private List<Message> messages = new ArrayList<>();

    @Data
    public static class Message {
        private Instant at;
        private String authorName;
        private boolean fromPlatform;
        private String text;
    }
}
