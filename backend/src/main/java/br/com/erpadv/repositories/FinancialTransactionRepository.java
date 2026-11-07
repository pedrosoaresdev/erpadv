package br.com.erpadv.repositories;

import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.FinancialTransaction;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.WriteBatch;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class FinancialTransactionRepository extends FirestoreRepository<FinancialTransaction> {

    public FinancialTransactionRepository(Firestore db, FirestoreMapper mapper) {
        super(db, mapper, FinancialTransaction.class, "financial_transactions");
    }

    /** Grava vários lançamentos atomicamente (parcelamentos/recorrências; máx. 60). */
    public List<FinancialTransaction> saveAll(String officeId, List<FinancialTransaction> items) {
        WriteBatch batch = db.batch();
        for (FinancialTransaction t : items) {
            if (t.getId() == null) {
                t.setId(collection(officeId).document().getId());
            }
            t.setOfficeId(officeId);
            batch.set(collection(officeId).document(t.getId()), mapper.toMap(t));
        }
        Futures.await(batch.commit());
        return items;
    }
}
