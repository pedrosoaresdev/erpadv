package br.com.erpadv.repositories;

import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.Deadline;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Repository;

@Repository
public class DeadlineRepository extends FirestoreRepository<Deadline> {

    public DeadlineRepository(Firestore db, FirestoreMapper mapper) {
        super(db, mapper, Deadline.class, "deadlines");
    }

    public void delete(String officeId, String id) {
        Futures.await(collection(officeId).document(id).delete());
    }
}
