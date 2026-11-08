package br.com.erpadv.repositories;

import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.BankAccount;
import br.com.erpadv.models.CostCenter;
import br.com.erpadv.models.FinanceCollection;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Repository;

/** Cadastros auxiliares do financeiro. */
public final class FinanceRepositories {

    private FinanceRepositories() {
    }

    @Repository
    public static class CostCenters extends FirestoreRepository<CostCenter> {
        public CostCenters(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, CostCenter.class, "cost_centers");
        }

        public void delete(String officeId, String id) {
            Futures.await(collection(officeId).document(id).delete());
        }
    }

    @Repository
    public static class BankAccounts extends FirestoreRepository<BankAccount> {
        public BankAccounts(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, BankAccount.class, "bank_accounts");
        }

        public void delete(String officeId, String id) {
            Futures.await(collection(officeId).document(id).delete());
        }
    }

    @Repository
    public static class Collections extends FirestoreRepository<FinanceCollection> {
        public Collections(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, FinanceCollection.class, "finance_collections");
        }
    }
}
