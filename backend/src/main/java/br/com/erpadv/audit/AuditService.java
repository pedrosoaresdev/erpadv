package br.com.erpadv.audit;

import br.com.erpadv.dto.CursorPage;
import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.security.AuthenticatedUser;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Trilha de auditoria imutável. Cada registro é gravado em transação junto com a "cabeça" da
 * cadeia ({@code audit_meta/chain}), com ID sequencial e {@code create} (falha se já existir).
 * Não existe operação de alteração ou exclusão.
 */
@Service
public class AuditService {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final Firestore db;
    private final FirestoreMapper mapper;

    public AuditService(Firestore db, FirestoreMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public record VerifyResult(boolean valid, long checked, Long brokenAtSeq, String message) {
    }

    public void record(AuthenticatedUser actor, String action, String entityType, String entityId, String summary) {
        record(actor, actor.officeId(), action, entityType, entityId, summary, Map.of());
    }

    public void record(AuthenticatedUser actor, String action, String entityType, String entityId, String summary,
                       Map<String, String> details) {
        record(actor, actor.officeId(), action, entityType, entityId, summary, details);
    }

    public void record(AuthenticatedUser actor, String officeId, String action, String entityType, String entityId,
                       String summary, Map<String, String> details) {
        record(actor, officeId, action, entityType, entityId, summary, details, List.of());
    }

    /** Retrato do registro no formato gravado (para comparar antes × depois). */
    public Map<String, Object> snapshot(Object entity) {
        return entity == null ? null : new java.util.HashMap<>(mapper.toMap(entity));
    }

    /** Registra uma alteração com os valores anteriores e posteriores de cada campo modificado. */
    public void recordChange(AuthenticatedUser actor, String action, String entityType, String entityId, String summary,
                             Map<String, String> details, Map<String, Object> before, Object after) {
        List<AuditChange> changes = AuditDiff.diff(before, after == null ? null : snapshot(after));
        record(actor, actor.officeId(), action, entityType, entityId, summary, details, changes);
    }

    /** Alteração feita pela plataforma (ou rotina) registrada na auditoria do escritório afetado. */
    public void recordChange(AuthenticatedUser actor, String officeId, String action, String entityType, String entityId, String summary,
                             Map<String, String> details, Map<String, Object> before, Object after) {
        List<AuditChange> changes = AuditDiff.diff(before, after == null ? null : snapshot(after));
        record(actor, officeId, action, entityType, entityId, summary, details, changes);
    }

    private void record(AuthenticatedUser actor, String officeId, String action, String entityType, String entityId,
                        String summary, Map<String, String> details, List<AuditChange> changes) {
        AuditLog entry = new AuditLog();
        entry.setActorName(actor == null ? "Sistema" : actor.displayName());
        entry.setModule(AuditDiff.module(entityType));
        entry.setOrigin(RequestInfo.origin());
        entry.setJustification(RequestInfo.justification());
        entry.setChanges(changes == null ? new ArrayList<>() : new ArrayList<>(changes));
        entry.setOfficeId(officeId);
        entry.setAt(Instant.now());
        entry.setActorUid(actor == null ? "system" : actor.uid());
        entry.setActorEmail(actor == null ? null : actor.email());
        entry.setAction(action);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setSummary(summary);
        entry.setDetails(details == null ? new TreeMap<>() : new TreeMap<>(details));
        entry.setIp(RequestInfo.ip());
        entry.setUserAgent(RequestInfo.userAgent());
        AUDIT.info("action={} entity={}:{} actor={} office={} summary={}",
                action, entityType, entityId, entry.getActorUid(), officeId, summary);
        try {
            append(officeId, entry);
        } catch (RuntimeException e) {
            // A operação de negócio já foi concluída; a falha fica registrada no log estruturado
            // (coletado externamente) para reconciliação.
            log.error("AUDIT_FAILURE action={} entity={}:{} office={}", action, entityType, entityId, officeId, e);
        }
    }

    private void append(String officeId, AuditLog entry) {
        DocumentReference office = db.collection("offices").document(officeId);
        DocumentReference head = office.collection("audit_meta").document("chain");
        CollectionReference logs = office.collection("audit_logs");
        Futures.await(db.runTransaction(tx -> {
            DocumentSnapshot meta = tx.get(head).get();
            Long lastSeq = meta.exists() ? meta.getLong("lastSeq") : null;
            String lastHash = meta.exists() ? meta.getString("lastHash") : null;
            long seq = lastSeq == null ? 1 : lastSeq + 1;
            entry.setSeq(seq);
            entry.setId(String.format("%012d", seq));
            entry.setPrevHash(lastHash == null ? AuditHasher.GENESIS : lastHash);
            entry.setHash(AuditHasher.hash(entry.getPrevHash(), entry));
            tx.create(logs.document(entry.getId()), mapper.toMap(entry));
            tx.set(head, Map.of("lastSeq", seq, "lastHash", entry.getHash(), "updatedAt", entry.getAt().toEpochMilli()));
            return null;
        }));
    }

    public CursorPage<AuditLog> list(String officeId, String entityType, String entityId, String actorUid,
                                     String cursor, int size) {
        return list(officeId, new Filter(entityType, entityId, actorUid, null, null, null, null), cursor, size);
    }

    /** Filtros da consulta. Período é convertido em faixa de {@code seq} (a sequência cresce com o tempo). */
    public record Filter(String entityType, String entityId, String actorUid, String module, String action,
                         Instant from, Instant to) {
    }

    public CursorPage<AuditLog> list(String officeId, Filter f, String cursor, int size) {
        int limit = Math.max(1, Math.min(size, 500));
        String entityType = f.entityType();
        String entityId = f.entityId();
        String actorUid = f.actorUid();
        CollectionReference col = db.collection("offices").document(officeId).collection("audit_logs");
        Query query = col;
        if (f.module() != null && !f.module().isBlank()) {
            query = query.whereEqualTo("module", f.module());
        }
        if (f.action() != null && !f.action().isBlank()) {
            query = query.whereEqualTo("action", f.action().trim().toUpperCase(java.util.Locale.ROOT));
        }
        Long minSeq = f.from() == null ? null : seqAt(col, f.from(), true);
        Long maxSeq = f.to() == null ? null : seqAt(col, f.to(), false);
        if ((f.from() != null && minSeq == null) || (f.to() != null && maxSeq == null)) {
            return new CursorPage<>(List.of(), null);
        }
        if (minSeq != null) {
            query = query.whereGreaterThanOrEqualTo("seq", minSeq);
        }
        if (maxSeq != null) {
            query = query.whereLessThanOrEqualTo("seq", maxSeq);
        }
        if (entityType != null && !entityType.isBlank()) {
            query = query.whereEqualTo("entityType", entityType);
        }
        if (entityId != null && !entityId.isBlank()) {
            query = query.whereEqualTo("entityId", entityId);
        }
        if (actorUid != null && !actorUid.isBlank()) {
            query = query.whereEqualTo("actorUid", actorUid);
        }
        query = query.orderBy("seq", Query.Direction.DESCENDING);
        if (cursor != null && !cursor.isBlank()) {
            try {
                query = query.startAfter(Long.parseLong(cursor));
            } catch (NumberFormatException e) {
                throw new br.com.erpadv.exceptions.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        "INVALID_CURSOR", "Cursor de paginação inválido.");
            }
        }
        List<QueryDocumentSnapshot> docs = Futures.await(query.limit(limit + 1).get()).getDocuments();
        List<AuditLog> items = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, docs.size()); i++) {
            items.add(mapper.fromSnapshot(docs.get(i), AuditLog.class));
        }
        String next = docs.size() > limit ? String.valueOf(items.get(items.size() - 1).getSeq()) : null;
        return new CursorPage<>(items, next);
    }

    /** Primeiro registro a partir de {@code at} (ou último até {@code at}); nulo se não houver. */
    private Long seqAt(CollectionReference col, Instant at, boolean first) {
        Query q = first ? col.whereGreaterThanOrEqualTo("at", at.toEpochMilli()).orderBy("at").limit(1)
                : col.whereLessThanOrEqualTo("at", at.toEpochMilli()).orderBy("at", Query.Direction.DESCENDING).limit(1);
        List<QueryDocumentSnapshot> docs = Futures.await(q.get()).getDocuments();
        return docs.isEmpty() ? null : docs.get(0).getLong("seq");
    }

    /** Recalcula a cadeia inteira (em lotes) e aponta o primeiro registro adulterado ou ausente. */
    public VerifyResult verify(String officeId) {
        CollectionReference logs = db.collection("offices").document(officeId).collection("audit_logs");
        String prev = AuditHasher.GENESIS;
        long expectedSeq = 1;
        long checked = 0;
        Long lastSeq = null;
        while (true) {
            Query query = logs.orderBy("seq").limit(500);
            if (lastSeq != null) {
                query = query.startAfter(lastSeq);
            }
            List<QueryDocumentSnapshot> docs = Futures.await(query.get()).getDocuments();
            if (docs.isEmpty()) {
                break;
            }
            for (QueryDocumentSnapshot doc : docs) {
                AuditLog entry = mapper.fromSnapshot(doc, AuditLog.class);
                if (entry.getSeq() != expectedSeq) {
                    return new VerifyResult(false, checked, expectedSeq, "Registro ausente na sequência.");
                }
                if (!prev.equals(entry.getPrevHash()) || !AuditHasher.hash(prev, entry).equals(entry.getHash())) {
                    return new VerifyResult(false, checked, entry.getSeq(), "Registro adulterado.");
                }
                prev = entry.getHash();
                expectedSeq++;
                checked++;
                lastSeq = entry.getSeq();
            }
        }
        DocumentSnapshot head = Futures.await(db.collection("offices").document(officeId)
                .collection("audit_meta").document("chain").get());
        Long headSeq = head.exists() ? head.getLong("lastSeq") : null;
        if (headSeq != null && headSeq != checked) {
            return new VerifyResult(false, checked, checked + 1, "Registros finais removidos da cadeia.");
        }
        return new VerifyResult(true, checked, null, "Cadeia íntegra.");
    }
}
