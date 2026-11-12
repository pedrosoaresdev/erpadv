package br.com.erpadv.services.integrations;

import br.com.erpadv.models.IntegrationProvider;
import br.com.erpadv.models.Office;
import br.com.erpadv.repositories.OfficeRepository;
import br.com.erpadv.services.FinanceService;
import br.com.erpadv.services.JobLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;

/** Sincronizações automáticas diárias (somente escritórios ativos com "sincronização automática" ligada). */
@Component
@ConditionalOnProperty(prefix = "app.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IntegrationJobs {

    private static final Logger log = LoggerFactory.getLogger(IntegrationJobs.class);

    private final OfficeRepository offices;
    private final IntegrationService integrations;
    private final PublicationService publications;
    private final DataJudService dataJud;
    private final JobLock lock;
    @org.springframework.beans.factory.annotation.Autowired
    private br.com.erpadv.services.CommunicationService communications;
    /**
     * Consultas automáticas aos tribunais (DJEN e DataJud). Desligadas por padrão: o acompanhamento processual é feito pelo
     * advogado; para religar, defina INTEGRATIONS_JUDICIAL_SYNC=true.
     */
    @org.springframework.beans.factory.annotation.Value("${app.integrations.judicial-sync-enabled:false}")
    private boolean judicialSync;

    public IntegrationJobs(OfficeRepository offices, IntegrationService integrations, PublicationService publications, DataJudService dataJud,
                           JobLock lock) {
        this.offices = offices;
        this.integrations = integrations;
        this.publications = publications;
        this.dataJud = dataJud;
        this.lock = lock;
    }

    @Scheduled(cron = "${app.jobs.djen-cron:0 30 6,13 * * *}", zone = "America/Sao_Paulo")
    public void djenJob() {
        if (!judicialSync || !lock.tryAcquire("djen-sync", Duration.ofMinutes(50))) {
            return;
        }
        try {
            LocalDate today = FinanceService.today();
            for (Office o : offices.all(1000)) {
                if (!"ACTIVE".equals(o.getStatus()) && o.getStatus() != null) {
                    continue;
                }
                if (integrations.active(o.getId(), IntegrationProvider.DJEN) && integrations.autoSync(o.getId(), IntegrationProvider.DJEN)) {
                    try {
                        publications.sync(o.getId(), today.minusDays(3), today, "AUTOMATICA", "Sistema");
                    } catch (RuntimeException e) {
                        log.warn("DJEN: falha no escritório {}: {}", o.getId(), e.getMessage());
                    }
                }
            }
        } finally {
            lock.release("djen-sync");
        }
    }

    @Scheduled(cron = "${app.jobs.follow-up-cron:0 0 8 * * *}", zone = "America/Sao_Paulo")
    public void followUpJob() {
        if (!lock.tryAcquire("follow-ups", Duration.ofMinutes(20))) {
            return;
        }
        try {
            for (Office o : offices.all(1000)) {
                try {
                    communications.notifyFollowUps(o.getId(), FinanceService.today());
                } catch (RuntimeException e) {
                    log.warn("Lembretes de retorno: falha no escritório {}: {}", o.getId(), e.getMessage());
                }
            }
        } finally {
            lock.release("follow-ups");
        }
    }

    @Scheduled(cron = "${app.jobs.datajud-cron:0 0 3 * * *}", zone = "America/Sao_Paulo")
    public void dataJudJob() {
        if (!judicialSync || !lock.tryAcquire("datajud-sync", Duration.ofHours(3))) {
            return;
        }
        try {
            for (Office o : offices.all(1000)) {
                if (!"ACTIVE".equals(o.getStatus()) && o.getStatus() != null) {
                    continue;
                }
                if (integrations.active(o.getId(), IntegrationProvider.DATAJUD) && integrations.autoSync(o.getId(), IntegrationProvider.DATAJUD)) {
                    try {
                        dataJud.syncAll(o.getId(), "AUTOMATICA", "Sistema");
                    } catch (RuntimeException e) {
                        log.warn("DataJud: falha no escritório {}: {}", o.getId(), e.getMessage());
                    }
                }
            }
        } finally {
            lock.release("datajud-sync");
        }
    }
}
