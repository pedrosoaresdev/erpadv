package br.com.erpadv.repositories;

import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.Office;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Documento raiz do tenant: {@code offices/{officeId}}. */
@Repository
public class OfficeRepository {

    private final Firestore db;
    private final FirestoreMapper mapper;

    public OfficeRepository(Firestore db, FirestoreMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public DocumentReference newRef() {
        return db.collection("offices").document();
    }

    public DocumentReference ref(String officeId) {
        return db.collection("offices").document(officeId);
    }

    public Optional<Office> findById(String officeId) {
        if (officeId == null || officeId.isBlank() || officeId.contains("/")) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.fromSnapshot(Futures.await(ref(officeId).get()), Office.class));
    }
}
