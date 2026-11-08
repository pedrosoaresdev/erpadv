package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Execução de sincronização ({@code offices/{officeId}/integration_runs/{id}}). */
@Data
@EqualsAndHashCode(callSuper = true)
public class IntegrationRun extends BaseEntity {

    public enum Status { EXECUTANDO, SUCESSO, PARCIAL, ERRO }

    private IntegrationProvider provider;
    private String trigger;
    private Status status = Status.EXECUTANDO;
    private Instant startedAt;
    private Instant finishedAt;
    private int processed;
    private int created;
    private int updated;
    private int errors;
    private String message;
    private List<String> details = new ArrayList<>();
    private String triggeredByName;
}
