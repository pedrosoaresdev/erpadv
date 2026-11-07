package br.com.erpadv.security;

import br.com.erpadv.config.AppProperties;
import br.com.erpadv.models.Office;
import br.com.erpadv.repositories.OfficeRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Situação do escritório (ativo/suspenso pela plataforma), consultada a cada requisição. */
@Component
public class OfficeStatusCache {

    public static final String SUSPENDED = "SUSPENDED";

    private final OfficeRepository offices;
    private final TtlCache<String, String> cache;

    public OfficeStatusCache(OfficeRepository offices, AppProperties properties) {
        this.offices = offices;
        this.cache = new TtlCache<>(Duration.ofSeconds(Math.max(1, properties.security().profileCacheSeconds())), 5_000);
    }

    public boolean isSuspended(String officeId) {
        return cache.get(officeId, id -> offices.findById(id).map(Office::getStatus))
                .map(SUSPENDED::equals)
                .orElse(false);
    }

    public void evict(String officeId) {
        cache.evict(officeId);
    }
}
