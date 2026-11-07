package br.com.erpadv.services;

import br.com.erpadv.firebase.Futures;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Trava distribuída simples em {@code system_locks/{job}} (transação no Firestore), para que apenas
 * uma réplica da API execute cada job agendado por vez.
 */
@Component
public class JobLock {

    private final Firestore db;
    private final String owner = UUID.randomUUID().toString();

    public JobLock(Firestore db) {
        this.db = db;
    }

    public boolean tryAcquire(String job, Duration ttl) {
        DocumentReference ref = db.collection("system_locks").document(job);
        long now = System.currentTimeMillis();
        return Futures.await(db.runTransaction(tx -> {
            DocumentSnapshot snap = tx.get(ref).get();
            Long until = snap.exists() ? snap.getLong("lockedUntil") : null;
            String holder = snap.exists() ? snap.getString("owner") : null;
            if (until != null && until > now && !owner.equals(holder)) {
                return false;
            }
            tx.set(ref, Map.of("owner", owner, "lockedUntil", now + ttl.toMillis(), "updatedAt", now));
            return true;
        }));
    }

    public void release(String job) {
        DocumentReference ref = db.collection("system_locks").document(job);
        Futures.await(db.runTransaction(tx -> {
            DocumentSnapshot snap = tx.get(ref).get();
            if (snap.exists() && owner.equals(snap.getString("owner"))) {
                tx.update(ref, "lockedUntil", 0L);
            }
            return null;
        }));
    }
}
