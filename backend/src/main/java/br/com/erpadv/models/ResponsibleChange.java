package br.com.erpadv.models;

import lombok.Data;

import java.time.Instant;

/** Período em que um advogado foi responsável pelo processo. */
@Data
public class ResponsibleChange {
    private String uid;
    private String name;
    private Instant from;
    private Instant to;
    private String changedBy;
}
