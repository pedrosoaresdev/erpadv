package br.com.erpadv.repositories;

import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.CommissionPayout;
import br.com.erpadv.models.CommissionRule;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Repository;

/** Regras e pagamentos de comissões. */
public final class FeeRepositories {

    private FeeRepositories() {
    }

    @Repository
    public static class Rules extends FirestoreRepository<CommissionRule> {
        public Rules(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, CommissionRule.class, "commission_rules");
        }

        public void delete(String officeId, String id) {
            Futures.await(collection(officeId).document(id).delete());
        }
    }

    @Repository
    public static class Payouts extends FirestoreRepository<CommissionPayout> {
        public Payouts(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, CommissionPayout.class, "commission_payouts");
        }
    }
}
