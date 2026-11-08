package br.com.erpadv.repositories;

import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.Communication;
import br.com.erpadv.models.MessageTemplate;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Repository;

/** Comunicações e modelos de mensagem. */
public final class CommunicationRepositories {

    private CommunicationRepositories() {
    }

    @Repository
    public static class Communications extends FirestoreRepository<Communication> {
        public Communications(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, Communication.class, "communications");
        }
    }

    @Repository
    public static class Templates extends FirestoreRepository<MessageTemplate> {
        public Templates(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, MessageTemplate.class, "message_templates");
        }

        public void delete(String officeId, String id) {
            Futures.await(collection(officeId).document(id).delete());
        }
    }
}
