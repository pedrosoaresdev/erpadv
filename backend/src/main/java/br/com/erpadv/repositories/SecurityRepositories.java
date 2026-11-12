package br.com.erpadv.repositories;

import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.models.SecurityEvent;
import br.com.erpadv.models.SecurityPolicy;
import br.com.erpadv.models.UserSession;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Repository;

/** Repositórios do módulo de Segurança. */
public final class SecurityRepositories {

    private SecurityRepositories() {
    }

    @Repository
    public static class Policies extends FirestoreRepository<SecurityPolicy> {
        public Policies(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, SecurityPolicy.class, "security_settings");
        }
    }

    @Repository
    public static class Sessions extends FirestoreRepository<UserSession> {
        public Sessions(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, UserSession.class, "user_sessions");
        }
    }

    @Repository
    public static class Events extends FirestoreRepository<SecurityEvent> {
        public Events(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, SecurityEvent.class, "security_events");
        }
    }
}
