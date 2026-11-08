package br.com.erpadv.repositories;

import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.LegalDraft;
import br.com.erpadv.models.LegalDraftVersion;
import br.com.erpadv.models.LegalTemplate;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Repository;

/** Repositórios do Editor Jurídico. */
public final class LegalEditorRepositories {

    private LegalEditorRepositories() {
    }

    @Repository
    public static class Templates extends FirestoreRepository<LegalTemplate> {
        public Templates(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, LegalTemplate.class, "legal_templates");
        }

        public void delete(String officeId, String id) {
            Futures.await(collection(officeId).document(id).delete());
        }
    }

    @Repository
    public static class Letterheads extends FirestoreRepository<br.com.erpadv.models.Letterhead> {
        public Letterheads(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, br.com.erpadv.models.Letterhead.class, "letterheads");
        }

        public void delete(String officeId, String id) {
            Futures.await(collection(officeId).document(id).delete());
        }
    }

    @Repository
    public static class Drafts extends FirestoreRepository<LegalDraft> {
        public Drafts(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, LegalDraft.class, "legal_documents");
        }

        public void delete(String officeId, String id) {
            Futures.await(collection(officeId).document(id).delete());
        }
    }

    @Repository
    public static class Versions extends FirestoreRepository<LegalDraftVersion> {
        public Versions(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, LegalDraftVersion.class, "legal_document_versions");
        }

        public void delete(String officeId, String id) {
            Futures.await(collection(officeId).document(id).delete());
        }
    }
}
