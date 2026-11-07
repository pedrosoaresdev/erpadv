package br.com.erpadv.services;

import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.FinanceDirection;
import br.com.erpadv.models.FinanceScope;
import br.com.erpadv.models.TransactionStatus;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Diariamente marca como inadimplentes os clientes com contas a receber que venceram (todas as
 * instâncias de escritório). A desmarcação ocorre na baixa/cancelamento do lançamento.
 */
@Component
@ConditionalOnProperty(prefix = "app.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FinanceJobs {

    private static final Logger log = LoggerFactory.getLogger(FinanceJobs.class);
    private static final int BATCH = 2000;

    private final Firestore db;
    private final FinanceService finance;
    private final JobLock lock;

    public FinanceJobs(Firestore db, FinanceService finance, JobLock lock) {
        this.db = db;
        this.finance = finance;
        this.lock = lock;
    }

    @Scheduled(cron = "${app.jobs.delinquency-cron:0 30 6 * * *}", zone = "America/Sao_Paulo")
    public void delinquencyJob() {
        if (!lock.tryAcquire("delinquency-sync", Duration.ofMinutes(15))) {
            return;
        }
        try {
            int clients = syncDelinquency(FinanceService.today());
            if (clients > 0) {
                log.info("Inadimplência sincronizada para {} cliente(s)", clients);
            }
        } catch (RuntimeException e) {
            log.error("Falha no job de inadimplência", e);
        } finally {
            lock.release("delinquency-sync");
        }
    }

    /** Retorna a quantidade de clientes verificados. */
    public int syncDelinquency(LocalDate today) {
        Set<String> keys = new LinkedHashSet<>();
        for (QueryDocumentSnapshot doc : Futures.await(db.collectionGroup("financial_transactions")
                .whereEqualTo("direction", FinanceDirection.RECEITA.name())
                .whereEqualTo("status", TransactionStatus.ABERTO.name())
                .whereLessThan("dueDate", today.toString())
                .limit(BATCH).get()).getDocuments()) {
            String officeId = doc.getString("officeId");
            String clientId = doc.getString("clientId");
            if (officeId != null && clientId != null && FinanceScope.ESCRITORIO.name().equals(doc.getString("scope"))) {
                keys.add(officeId + "/" + clientId);
            }
        }
        for (String key : keys) {
            String[] parts = key.split("/", 2);
            finance.refreshClientDefaulting(parts[0], parts[1], FinanceScope.ESCRITORIO);
        }
        return keys.size();
    }
}
