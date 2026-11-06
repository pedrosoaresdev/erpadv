package br.com.erpadv.repositories;

import br.com.erpadv.dto.CursorPage;
import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.BaseEntity;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldPath;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Base de repositório para coleções isoladas por escritório: {@code offices/{officeId}/<coleção>}.
 * Toda operação exige o officeId — não há como consultar dados de outro escritório por engano.
 */
public abstract class FirestoreRepository<T extends BaseEntity> {

    public static final int MAX_PAGE_SIZE = 100;

    protected final Firestore db;
    protected final FirestoreMapper mapper;
    private final Class<T> type;
    private final String collectionName;

    protected FirestoreRepository(Firestore db, FirestoreMapper mapper, Class<T> type, String collectionName) {
        this.db = db;
        this.mapper = mapper;
        this.type = type;
        this.collectionName = collectionName;
    }

    public CollectionReference collection(String officeId) {
        Objects.requireNonNull(officeId, "officeId é obrigatório");
        return db.collection("offices").document(officeId).collection(collectionName);
    }

    public Optional<T> findById(String officeId, String id) {
        if (!isValidId(id)) {
            return Optional.empty();
        }
        DocumentSnapshot snapshot = Futures.await(collection(officeId).document(id).get());
        T entity = mapper.fromSnapshot(snapshot, type);
        // Defesa adicional: o documento precisa pertencer ao escritório solicitado.
        if (entity == null || !officeId.equals(entity.getOfficeId())) {
            return Optional.empty();
        }
        return Optional.of(entity);
    }

    public T save(String officeId, T entity) {
        if (entity.getId() == null) {
            entity.setId(collection(officeId).document().getId());
        }
        entity.setOfficeId(officeId);
        Futures.await(collection(officeId).document(entity.getId()).set(mapper.toMap(entity)));
        return entity;
    }

    public long count(Query query) {
        return Futures.await(query.count().get()).getCount();
    }

    /**
     * Paginação por cursor: ordena pelo campo informado e pelo ID do documento (desempate estável),
     * busca {@code size + 1} itens para saber se existe próxima página.
     */
    public CursorPage<T> page(Query base, String orderField, Query.Direction direction, String cursor, int size) {
        int limit = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        Query query = base.orderBy(orderField, direction).orderBy(FieldPath.documentId(), direction);
        if (cursor != null && !cursor.isBlank()) {
            Object[] values = CursorCodec.decode(cursor);
            query = query.startAfter(values[0], values[1]);
        }
        QuerySnapshot snapshot = Futures.await(query.limit(limit + 1).get());
        List<QueryDocumentSnapshot> docs = snapshot.getDocuments();
        List<T> items = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, docs.size()); i++) {
            items.add(mapper.fromSnapshot(docs.get(i), type));
        }
        String next = null;
        if (docs.size() > limit) {
            QueryDocumentSnapshot last = docs.get(limit - 1);
            next = CursorCodec.encode(last.get(orderField), last.getId());
        }
        return new CursorPage<>(items, next);
    }

    public List<T> list(Query query, int max) {
        QuerySnapshot snapshot = Futures.await(query.limit(Math.max(1, Math.min(max, 500))).get());
        return snapshot.getDocuments().stream().map(d -> mapper.fromSnapshot(d, type)).toList();
    }

    protected static boolean isValidId(String id) {
        return id != null && !id.isBlank() && id.length() <= 128 && !id.contains("/") && !id.startsWith("__");
    }
}
