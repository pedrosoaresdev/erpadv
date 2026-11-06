package br.com.erpadv.repositories;

import br.com.erpadv.dto.CursorPage;
import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.models.UserStatus;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.FieldPath;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Perfis globais {@code users/{uid}} — consultas sempre filtradas por officeId. */
@Repository
public class UserRepository {

    private final Firestore db;
    private final FirestoreMapper mapper;

    public UserRepository(Firestore db, FirestoreMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public DocumentReference ref(String uid) {
        return db.collection("users").document(uid);
    }

    public Optional<UserProfile> findById(String uid) {
        if (uid == null || uid.isBlank() || uid.contains("/")) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.fromSnapshot(Futures.await(ref(uid).get()), UserProfile.class));
    }

    public Optional<UserProfile> findInOffice(String officeId, String uid) {
        return findById(uid).filter(u -> officeId.equals(u.getOfficeId()));
    }

    public UserProfile save(UserProfile profile) {
        Futures.await(ref(profile.getId()).set(mapper.toMap(profile)));
        return profile;
    }

    public CursorPage<UserProfile> byOffice(String officeId, String cursor, int size) {
        int limit = Math.max(1, Math.min(size, 100));
        Query query = db.collection("users").whereEqualTo("officeId", officeId)
                .orderBy("displayNameLower").orderBy(FieldPath.documentId());
        if (cursor != null && !cursor.isBlank()) {
            Object[] values = CursorCodec.decode(cursor);
            query = query.startAfter(values[0], values[1]);
        }
        List<QueryDocumentSnapshot> docs = Futures.await(query.limit(limit + 1).get()).getDocuments();
        List<UserProfile> items = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, docs.size()); i++) {
            items.add(mapper.fromSnapshot(docs.get(i), UserProfile.class));
        }
        String next = docs.size() > limit
                ? CursorCodec.encode(docs.get(limit - 1).get("displayNameLower"), docs.get(limit - 1).getId())
                : null;
        return new CursorPage<>(items, next);
    }

    /** Equipe ativa do escritório (para seleção de responsáveis). Limitado a 500 usuários. */
    public List<UserProfile> activeStaff(String officeId) {
        return Futures.await(db.collection("users").whereEqualTo("officeId", officeId)
                        .whereIn("status", List.of(UserStatus.ACTIVE.name(), UserStatus.INVITED.name()))
                        .limit(500).get())
                .getDocuments().stream()
                .map(d -> mapper.fromSnapshot(d, UserProfile.class))
                .filter(u -> u.getRole() != null && u.getRole().isStaff())
                .toList();
    }

    public long countByOffice(String officeId, UserStatus status) {
        Query q = db.collection("users").whereEqualTo("officeId", officeId);
        if (status != null) {
            q = q.whereEqualTo("status", status.name());
        }
        return Futures.await(q.count().get()).getCount();
    }
}
