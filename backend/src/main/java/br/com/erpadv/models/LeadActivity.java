package br.com.erpadv.models;

import br.com.erpadv.models.CrmEnums.LeadActivityType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/** Interação registrada com a oportunidade ({@code offices/{officeId}/lead_activities/{id}}). */
@Data
@EqualsAndHashCode(callSuper = true)
public class LeadActivity extends BaseEntity {
    private String leadId;
    private LeadActivityType type;
    private String text;
    private String actorUid;
    private String actorName;
    private Instant at;
}
