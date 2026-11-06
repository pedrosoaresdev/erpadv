package br.com.erpadv.repositories;

import br.com.erpadv.dto.CursorPage;
import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.models.CaseMovement;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import org.springframework.stereotype.Repository;

@Repository
public class CaseMovementRepository extends FirestoreRepository<CaseMovement> {

    public CaseMovementRepository(Firestore db, FirestoreMapper mapper) {
        super(db, mapper, CaseMovement.class, "case_movements");
    }

    public CursorPage<CaseMovement> byCase(String officeId, String caseId, String cursor, int size) {
        return page(collection(officeId).whereEqualTo("caseId", caseId), "sortKey", Query.Direction.DESCENDING, cursor, size);
    }
}
