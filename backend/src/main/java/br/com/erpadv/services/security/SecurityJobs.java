package br.com.erpadv.services.security;

import br.com.erpadv.models.Office;
import br.com.erpadv.repositories.OfficeRepository;
import br.com.erpadv.services.JobLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Backup diário de cada escritório com a opção ativa na política de segurança (02:30, horário de Brasília). */
@Component
@ConditionalOnProperty(prefix = "app.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SecurityJobs {

    private static final Logger log = LoggerFactory.getLogger(SecurityJobs.class);

    private final OfficeRepository offices;
    private final SecurityPolicyService policies;
    private final BackupService backups;
    private final JobLock lock;
    @org.springframework.beans.factory.annotation.Autowired
    private br.com.erpadv.services.lgpd.LgpdService lgpd;

    public SecurityJobs(OfficeRepository offices, SecurityPolicyService policies, BackupService backups, JobLock lock) {
        this.offices = offices;
        this.policies = policies;
        this.backups = backups;
        this.lock = lock;
    }

    @Scheduled(cron = "${app.jobs.backup-cron:0 30 2 * * *}", zone = "America/Sao_Paulo")
    public void dailyBackup() {
        if (!lock.tryAcquire("daily-backup", Duration.ofHours(2))) {
            return;
        }
        try {
            for (Office o : offices.all(1000)) {
                if (policies.get(o.getId()).isBackupEnabled()) {
                    try {
                        backups.create(o.getId(), "AUTOMATICO", "Rotina diária");
                    } catch (RuntimeException e) {
                        log.error("Backup diário falhou para {}", o.getId(), e);
                    }
                }
            }
        } finally {
            lock.release("daily-backup");
        }
    }

    /** Prazos de solicitações de titulares (15 dias) e de comunicação de incidentes à ANPD. */
    @Scheduled(cron = "${app.jobs.lgpd-cron:0 10 8 * * *}", zone = "America/Sao_Paulo")
    public void lgpdDeadlines() {
        if (!lock.tryAcquire("lgpd-deadlines", Duration.ofMinutes(30))) {
            return;
        }
        try {
            for (Office o : offices.all(1000)) {
                try {
                    lgpd.notifyDeadlines(o.getId());
                } catch (RuntimeException e) {
                    log.error("Alertas LGPD falharam para {}", o.getId(), e);
                }
            }
        } finally {
            lock.release("lgpd-deadlines");
        }
    }
}
