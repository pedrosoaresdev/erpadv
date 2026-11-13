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
    private static final String BILLING = SUSPENDED + ":BILLING";

    private final OfficeRepository offices;
    private final TtlCache<String, String> cache;

    public OfficeStatusCache(OfficeRepository offices, AppProperties properties) {
        this.offices = offices;
        this.cache = new TtlCache<>(Duration.ofSeconds(Math.max(1, properties.security().profileCacheSeconds())), 5_000);
    }

    private String state(String officeId) {
        return cache.get(officeId, id -> offices.findById(id)
                        .map(o -> SUSPENDED.equals(o.getStatus()) ? (o.isBillingSuspended() ? BILLING : SUSPENDED) : String.valueOf(o.getStatus())))
                .orElse("");
    }

    public boolean isSuspended(String officeId) {
        return state(officeId).startsWith(SUSPENDED);
    }

    /** Suspensão automática por falta de pagamento (o administrador ainda acessa Plano e Assinatura e Suporte). */
    public boolean isBillingSuspended(String officeId) {
        return BILLING.equals(state(officeId));
    }

    public void evict(String officeId) {
        cache.evict(officeId);
    }
}
