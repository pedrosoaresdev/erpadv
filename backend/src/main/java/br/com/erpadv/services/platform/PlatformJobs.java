package br.com.erpadv.services.platform;

import br.com.erpadv.services.JobLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Cobrança diária das assinaturas (06:00): fim de avaliação, faturas no vencimento, atrasos e suspensões. */
@Component
@ConditionalOnProperty(prefix = "app.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PlatformJobs {

    private static final Logger log = LoggerFactory.getLogger(PlatformJobs.class);
    private final BillingService billing;
    private final JobLock lock;

    public PlatformJobs(BillingService billing, JobLock lock) {
        this.billing = billing;
        this.lock = lock;
    }

    @Scheduled(cron = "${app.jobs.billing-cron:0 0 6 * * *}", zone = "America/Sao_Paulo")
    public void dailyBilling() {
        if (!lock.tryAcquire("daily-billing", Duration.ofHours(1))) {
            return;
        }
        try {
            log.info("Cobrança diária: {} ação(ões)", billing.runDaily(BillingService.today()));
        } finally {
            lock.release("daily-billing");
        }
    }
}
