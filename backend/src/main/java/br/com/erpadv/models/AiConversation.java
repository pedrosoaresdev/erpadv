package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Conversa com a IA Jurídica ({@code offices/{officeId}/ai_conversations}). Guarda as perguntas e respostas, não o conteúdo
 * integral dos documentos e processos usados como contexto (eles são relidos a cada pergunta, com as permissões do usuário).
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AiConversation extends BaseEntity {

    private String uid;
    private String userName;
    private String agent;
    private String task;
    private String title;
    private String caseId;
    private String caseNumber;
    private String clientId;
    private String clientName;
    private List<String> documentIds = new ArrayList<>();
    private List<String> documentTitles = new ArrayList<>();
    private boolean restricted;
    private Instant lastMessageAt;
    private long inputTokens;
    private long outputTokens;
    private List<Turn> messages = new ArrayList<>();

    @Data
    public static class Turn {
        private String role;
        private String text;
        private Instant at;
        private String model;
        private String stopReason;
        private List<Source> sources = new ArrayList<>();
        private List<ExtractedItem> extraction = new ArrayList<>();
        private boolean reviewed;
        private String reviewedByName;
        private Instant reviewedAt;
        private String draftId;
    }

    @Data
    public static class Source {
        private String url;
        private String title;
        private String citedText;
    }

    @Data
    public static class ExtractedItem {
        private String date;
        private String time;
        private String kind;
        private String description;
        private String sourceQuote;
        private String confidence;
    }
}
