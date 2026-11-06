package br.com.erpadv.models;

import lombok.Data;

import java.time.Instant;

/** Campos comuns às entidades persistidas. {@code officeId} garante o isolamento multiempresa. */
@Data
public abstract class BaseEntity {
    private String id;
    private String officeId;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
