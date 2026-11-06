package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/** Atendimento registrado em {@code offices/{officeId}/clients/{clientId}/interactions}. */
@Data
@EqualsAndHashCode(callSuper = true)
public class ClientInteraction extends BaseEntity {
    private String clientId;
    private InteractionChannel channel;
    private String subject;
    private String description;
    private Instant occurredAt;
    private String userUid;
    private String userName;
}
