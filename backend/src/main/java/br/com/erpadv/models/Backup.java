package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Backup lógico do escritório ({@code offices/{officeId}/backups/{id}}): todas as coleções em JSON compactado e
 * cifrado (AES-256-GCM), guardado no Storage.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Backup extends BaseEntity {

    public enum Status { EM_ANDAMENTO, CONCLUIDO, FALHOU }

    private String trigger;
    private Status status = Status.EM_ANDAMENTO;
    private String createdByName;
    private String storagePath;
    private long sizeBytes;
    private String sha256;
    private long documents;
    private Map<String, Long> collections = new HashMap<>();
    private Instant completedAt;
    private String error;
}
