package br.com.erpadv.repositories;

import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.models.DocumentAccessLog;
import br.com.erpadv.models.DocumentVersion;
import br.com.erpadv.models.StoredDocument;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Repository;

/** Repositórios da gestão documental. */
public final class DocumentRepositories {

    private DocumentRepositories() {
    }

    @Repository
    public static class Documents extends FirestoreRepository<StoredDocument> {
        public Documents(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, StoredDocument.class, "documents");
        }
    }

    @Repository
    public static class Folders extends FirestoreRepository<br.com.erpadv.models.DocumentFolder> {
        public Folders(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, br.com.erpadv.models.DocumentFolder.class, "document_folders");
        }

        public void delete(String officeId, String id) {
            br.com.erpadv.firebase.Futures.await(collection(officeId).document(id).delete());
        }
    }

    @Repository
    public static class Versions extends FirestoreRepository<DocumentVersion> {
        public Versions(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, DocumentVersion.class, "document_versions");
        }
    }

    @Repository
    public static class AccessLogs extends FirestoreRepository<DocumentAccessLog> {
        public AccessLogs(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, DocumentAccessLog.class, "document_access_logs");
        }
    }
}
