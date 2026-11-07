package br.com.erpadv.repositories;

import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.Office;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Repository;

import java.util.List;
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

    public Office save(Office office) {
        Futures.await(ref(office.getId()).set(mapper.toMap(office)));
        return office;
    }

    /** Todos os escritórios da plataforma (painel master), mais recentes primeiro. */
    public List<Office> all(int max) {
        return Futures.await(db.collection("offices").orderBy("createdAt", com.google.cloud.firestore.Query.Direction.DESCENDING)
                        .limit(Math.max(1, Math.min(max, 1000))).get())
                .getDocuments().stream().map(d -> mapper.fromSnapshot(d, Office.class)).toList();
    }

    public long count(String status) {
        com.google.cloud.firestore.Query q = db.collection("offices");
        if (status != null) {
            q = q.whereEqualTo("status", status);
        }
        return Futures.await(q.count().get()).getCount();
    }

    public Optional<Office> findById(String officeId) {
        if (officeId == null || officeId.isBlank() || officeId.contains("/")) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.fromSnapshot(Futures.await(ref(officeId).get()), Office.class));
    }
}
