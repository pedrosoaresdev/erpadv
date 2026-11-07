package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Comentário ou evento de um cartão ({@code offices/{officeId}/card_activity/{id}}). */
@Data
@EqualsAndHashCode(callSuper = true)
public class CardActivity extends BaseEntity {
    private String cardId;
    private String boardId;
    /** COMMENT ou EVENT */
    private String kind;
    private String text;
    private String actorUid;
    private String actorName;
    private Instant at;
    private List<String> mentionUids = new ArrayList<>();
}
