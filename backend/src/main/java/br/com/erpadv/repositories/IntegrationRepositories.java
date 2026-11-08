package br.com.erpadv.repositories;

import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.IntegrationRun;
import br.com.erpadv.models.IntegrationSetting;
import br.com.erpadv.models.Publication;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Repository;

/** Repositórios da Central de Integrações. */
public final class IntegrationRepositories {

    private IntegrationRepositories() {
    }

    @Repository
    public static class Settings extends FirestoreRepository<IntegrationSetting> {
        public Settings(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, IntegrationSetting.class, "integration_settings");
        }
    }

    @Repository
    public static class Runs extends FirestoreRepository<IntegrationRun> {
        public Runs(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, IntegrationRun.class, "integration_runs");
        }
    }

    @Repository
    public static class Publications extends FirestoreRepository<Publication> {
        public Publications(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, Publication.class, "publications");
        }

        public void delete(String officeId, String id) {
            Futures.await(collection(officeId).document(id).delete());
        }
    }
}
