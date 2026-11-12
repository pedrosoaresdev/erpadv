package br.com.erpadv.repositories;

import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.Lgpd;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Repository;

/** Repositórios do módulo LGPD. */
public final class LgpdRepositories {

    private LgpdRepositories() {
    }

    @Repository
    public static class Requests extends FirestoreRepository<Lgpd.DataRequest> {
        public Requests(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, Lgpd.DataRequest.class, "lgpd_requests");
        }
    }

    @Repository
    public static class Consents extends FirestoreRepository<Lgpd.Consent> {
        public Consents(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, Lgpd.Consent.class, "consents");
        }
    }

    @Repository
    public static class Activities extends FirestoreRepository<Lgpd.ProcessingActivity> {
        public Activities(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, Lgpd.ProcessingActivity.class, "processing_activities");
        }

        public void delete(String officeId, String id) {
            Futures.await(collection(officeId).document(id).delete());
        }
    }

    @Repository
    public static class Incidents extends FirestoreRepository<Lgpd.Incident> {
        public Incidents(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, Lgpd.Incident.class, "incidents");
        }
    }

    @Repository
    public static class Shares extends FirestoreRepository<Lgpd.DataShare> {
        public Shares(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, Lgpd.DataShare.class, "data_shares");
        }
    }

    @Repository
    public static class Policies extends FirestoreRepository<Lgpd.PrivacyPolicy> {
        public Policies(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, Lgpd.PrivacyPolicy.class, "privacy_policies");
        }
    }

    @Repository
    public static class Retention extends FirestoreRepository<Lgpd.RetentionRule> {
        public Retention(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, Lgpd.RetentionRule.class, "retention_rules");
        }

        public void delete(String officeId, String id) {
            Futures.await(collection(officeId).document(id).delete());
        }
    }
}
