package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuração de um conector ({@code offices/{officeId}/integration_settings/{PROVIDER}}). Segredos (tokens, senhas)
 * ficam cifrados com AES-256-GCM em {@code secretsEnc} e nunca são devolvidos pela API.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class IntegrationSetting extends BaseEntity {
    private IntegrationProvider provider;
    private boolean enabled;
    private boolean autoSync;
    private Map<String, String> config = new HashMap<>();
    private Map<String, String> secretsEnc = new HashMap<>();
    private Instant lastRunAt;
    private Instant lastSuccessAt;
    private String lastError;
    private Instant lastErrorAt;
}
