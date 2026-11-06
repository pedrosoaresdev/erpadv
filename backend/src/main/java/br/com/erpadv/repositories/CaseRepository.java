package br.com.erpadv.repositories;

import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.models.LegalCase;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class CaseRepository extends FirestoreRepository<LegalCase> {

    public CaseRepository(Firestore db, FirestoreMapper mapper) {
        super(db, mapper, LegalCase.class, "cases");
    }

    public Optional<LegalCase> findByCnj(String officeId, String cnjNumber) {
        return list(collection(officeId).whereEqualTo("cnjNumber", cnjNumber), 1).stream().findFirst();
    }
}
