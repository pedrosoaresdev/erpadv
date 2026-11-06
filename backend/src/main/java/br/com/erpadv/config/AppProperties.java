package br.com.erpadv.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configurações da aplicação (prefixo {@code app}). Valores sensíveis vêm sempre de variáveis
 * de ambiente — ver {@code .env.example} na raiz do projeto.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Firebase firebase, Security security, Cors cors) {

    /**
     * @param emulatorPublicAuthUrl URL do Auth Emulator vista pelo navegador (ex.: http://localhost:9099),
     *                              usada para reescrever links gerados com o host interno do Docker.
     */
    public record Firebase(String projectId, String credentialsPath, String storageBucket, boolean emulator,
                           String emulatorPublicAuthUrl) {
    }

    public record Security(
            String dataEncryptionKey,
            String searchHmacKey,
            int rateLimitPerMinute,
            long profileCacheSeconds,
            boolean requireEmailVerified,
            boolean production) {
    }

    public record Cors(List<String> allowedOrigins) {
    }
}
