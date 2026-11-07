package br.com.erpadv.repositories;

import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.Contract;
import br.com.erpadv.models.ContractTemplate;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Repository;

import java.time.Year;
import java.util.Map;

public final class ContractRepositories {

    private ContractRepositories() {
    }

    @Repository
    public static class Contracts extends FirestoreRepository<Contract> {
        public Contracts(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, Contract.class, "contracts");
        }

        /** Número sequencial por escritório e ano (CT-2026-0001), gerado em transação. */
        public String nextNumber(String officeId) {
            int year = Year.now().getValue();
            DocumentReference counter = db.collection("offices").document(officeId)
                    .collection("counters").document("contracts-" + year);
            long next = Futures.await(db.runTransaction(tx -> {
                DocumentSnapshot snap = tx.get(counter).get();
                Long current = snap.exists() ? snap.getLong("value") : null;
                long value = current == null ? 1 : current + 1;
                tx.set(counter, Map.of("value", value));
                return value;
            }));
            return String.format("CT-%d-%04d", year, next);
        }
    }

    @Repository
    public static class Templates extends FirestoreRepository<ContractTemplate> {
        public Templates(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, ContractTemplate.class, "contract_templates");
        }

        public void delete(String officeId, String id) {
            Futures.await(collection(officeId).document(id).delete());
        }
    }
}
