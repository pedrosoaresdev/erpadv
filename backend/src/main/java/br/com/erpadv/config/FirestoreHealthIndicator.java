package br.com.erpadv.config;

import com.google.cloud.firestore.Firestore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Verifica a conectividade com o Firestore para o probe de readiness. O resultado fica em cache
 * por 60 segundos para não gerar leituras a cada health check.
 */
@Component("firestoreHealthIndicator")
public class FirestoreHealthIndicator implements HealthIndicator {

    private static final Duration CACHE = Duration.ofSeconds(60);

    private final Firestore firestore;
    private volatile Health cached;
    private volatile Instant cachedAt = Instant.EPOCH;

    public FirestoreHealthIndicator(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Health health() {
        if (cached != null && cachedAt.plus(CACHE).isAfter(Instant.now())) {
            return cached;
        }
        Health result;
        try {
            firestore.collection("system_health").document("ping").get().get(5, TimeUnit.SECONDS);
            result = Health.up().build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = Health.down().withDetail("error", "interrompido").build();
        } catch (Exception e) {
            result = Health.down().withDetail("error", e.getClass().getSimpleName()).build();
        }
        cached = result;
        cachedAt = Instant.now();
        return result;
    }
}
