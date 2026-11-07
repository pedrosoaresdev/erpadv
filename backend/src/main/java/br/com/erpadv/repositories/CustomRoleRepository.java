package br.com.erpadv.repositories;

import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.CustomRole;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class CustomRoleRepository extends FirestoreRepository<CustomRole> {

    public CustomRoleRepository(Firestore db, FirestoreMapper mapper) {
        super(db, mapper, CustomRole.class, "roles");
    }

    public List<CustomRole> all(String officeId) {
        return list(collection(officeId).orderBy("nameLower"), 200);
    }

    public void delete(String officeId, String id) {
        Futures.await(collection(officeId).document(id).delete());
    }
}
