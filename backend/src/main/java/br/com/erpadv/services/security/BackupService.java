package br.com.erpadv.services.security;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.firebase.CryptoService;
import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.firebase.StorageGateway;
import br.com.erpadv.models.Backup;
import br.com.erpadv.models.SecurityEvent;
import br.com.erpadv.repositories.FirestoreRepository;
import br.com.erpadv.security.AuthenticatedUser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.Blob;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldPath;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.GeoPoint;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.WriteBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Backup automático e política de recuperação. O backup percorre todas as coleções do escritório (e os perfis da
 * equipe), grava linhas JSON compactadas e cifradas no Storage e aplica a retenção definida na política. A
 * recuperação é não destrutiva: recria apenas os registros que não existem mais, nunca sobrescreve dados atuais.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Coleções que não entram no backup (o próprio histórico de backups e sessões de login, que são efêmeras). */
    private static final Set<String> SKIP = Set.of("backups", "user_sessions");
    /** Coleções aninhadas conhecidas (subcoleções por documento). */
    private static final Map<String, List<String>> NESTED = Map.of("clients", List.of("interactions"));
    /** Nunca recriadas na recuperação: a trilha de auditoria é imutável e encadeada; perfis dependem do Firebase Auth. */
    private static final Set<String> NO_RESTORE = Set.of("audit_logs", "audit_meta", "security_events", "@users");
    private static final int KEEP_AT_LEAST = 3;

    public record CollectionDiff(String collection, long inBackup, long missingNow, boolean restorable) {
    }

    public record Inspection(String backupId, Instant createdAt, List<CollectionDiff> collections) {
    }

    public record RestoreResult(String backupId, Map<String, Long> restored, long total) {
    }

    @Repository
    public static class Backups extends FirestoreRepository<Backup> {
        public Backups(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, Backup.class, "backups");
        }

        public void delete(String officeId, String id) {
            Futures.await(collection(officeId).document(id).delete());
        }
    }

    private final Firestore db;
    private final Backups backups;
    private final StorageGateway storage;
    private final CryptoService crypto;
    private final SecurityPolicyService policies;
    private final SecurityEventService events;
    private final AuditService audit;

    public BackupService(Firestore db, Backups backups, StorageGateway storage, CryptoService crypto, SecurityPolicyService policies,
                         SecurityEventService events, AuditService audit) {
        this.db = db;
        this.backups = backups;
        this.storage = storage;
        this.crypto = crypto;
        this.policies = policies;
        this.events = events;
        this.audit = audit;
    }

    public List<Backup> list(AuthenticatedUser user) {
        return backups.list(backups.collection(user.requireOffice()).orderBy("createdAt", Query.Direction.DESCENDING), 200);
    }

    public Backup manual(AuthenticatedUser user) {
        Backup b = create(user.requireOffice(), "MANUAL", user.displayName());
        audit.record(user, "BACKUP_CREATED", "backup", b.getId(), "Backup manual: " + b.getDocuments() + " registro(s)");
        if (b.getStatus() == Backup.Status.FALHOU) {
            throw new BusinessException("BACKUP_FAILED", "Não foi possível gerar o backup: " + b.getError());
        }
        return b;
    }

    // ------------------------------------------------------------------ geração

    public Backup create(String officeId, String trigger, String byName) {
        Backup b = new Backup();
        b.setId(Instant.now().toString().replace(":", "").replace(".", "") + "-" + UUID.randomUUID().toString().substring(0, 6));
        b.setTrigger(trigger);
        b.setCreatedByName(byName);
        b.setCreatedAt(Instant.now());
        backups.save(officeId, b);
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            Map<String, Long> counts = new TreeMap<>();
            try (Writer w = new OutputStreamWriter(new GZIPOutputStream(raw), StandardCharsets.UTF_8)) {
                Map<String, Object> header = new LinkedHashMap<>();
                header.put("format", "erpadv-backup");
                header.put("version", 1);
                header.put("officeId", officeId);
                header.put("createdAt", b.getCreatedAt().toString());
                w.write(JSON.writeValueAsString(header));
                w.write('\n');
                DocumentReference office = db.collection("offices").document(officeId);
                DocumentSnapshot officeDoc = Futures.await(office.get());
                if (officeDoc.exists()) {
                    line(w, "@office", officeDoc.getData());
                    counts.merge("@office", 1L, Long::sum);
                }
                for (DocumentSnapshot u : Futures.await(db.collection("users").whereEqualTo("officeId", officeId).get()).getDocuments()) {
                    line(w, "@users/" + u.getId(), u.getData());
                    counts.merge("@users", 1L, Long::sum);
                }
                for (CollectionReference col : office.listCollections()) {
                    if (SKIP.contains(col.getId())) {
                        continue;
                    }
                    dump(w, col, col.getId(), counts, NESTED.getOrDefault(col.getId(), List.of()));
                }
            }
            byte[] encrypted = crypto.encryptBytes(raw.toByteArray());
            String path = "offices/" + officeId + "/backups/" + b.getId() + ".jsonl.gz.enc";
            storage.put(path, encrypted, "application/octet-stream", Map.of("officeId", officeId, "backupId", b.getId()));
            b.setStoragePath(path);
            b.setSizeBytes(encrypted.length);
            b.setSha256(sha256(encrypted));
            b.setCollections(counts);
            b.setDocuments(counts.values().stream().mapToLong(Long::longValue).sum());
            b.setStatus(Backup.Status.CONCLUIDO);
            b.setCompletedAt(Instant.now());
            backups.save(officeId, b);
            events.record(officeId, "BACKUP_CREATED", SecurityEvent.Severity.INFO, "Backup " + trigger.toLowerCase() + " concluído: "
                    + b.getDocuments() + " registro(s), " + (b.getSizeBytes() / 1024) + " KB", null, byName, Map.of("backupId", b.getId()));
            prune(officeId);
        } catch (IOException | RuntimeException e) {
            log.error("Falha no backup do escritório {}", officeId, e);
            b.setStatus(Backup.Status.FALHOU);
            b.setError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            b.setCompletedAt(Instant.now());
            backups.save(officeId, b);
            events.record(officeId, "BACKUP_FAILED", SecurityEvent.Severity.CRITICO, "Falha no backup: " + b.getError(), null, byName,
                    Map.of("backupId", b.getId()));
        }
        return b;
    }

    private void dump(Writer w, CollectionReference col, String prefix, Map<String, Long> counts, List<String> nested) throws IOException {
        String last = null;
        while (true) {
            Query q = col.orderBy(FieldPath.documentId()).limit(500);
            if (last != null) {
                q = q.startAfter(last);
            }
            List<QueryDocumentSnapshot> docs = Futures.await(q.get()).getDocuments();
            for (QueryDocumentSnapshot d : docs) {
                line(w, prefix + "/" + d.getId(), d.getData());
                counts.merge(prefix.contains("/") ? prefix.substring(0, prefix.indexOf('/')) + "/" + col.getId() : col.getId(), 1L, Long::sum);
                for (String sub : nested) {
                    dump(w, d.getReference().collection(sub), prefix + "/" + d.getId() + "/" + sub, counts, List.of());
                }
            }
            if (docs.size() < 500) {
                return;
            }
            last = docs.get(docs.size() - 1).getId();
        }
    }

    private static void line(Writer w, String path, Map<String, Object> data) throws IOException {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("p", path);
        l.put("d", encode(data));
        w.write(JSON.writeValueAsString(l));
        w.write('\n');
    }

    /** Retenção: apaga backups mais antigos que o prazo da política, mantendo sempre os 3 mais recentes concluídos. */
    void prune(String officeId) {
        int days = Math.max(7, policies.get(officeId).getBackupRetentionDays());
        Instant limit = Instant.now().minus(Duration.ofDays(days));
        List<Backup> all = backups.list(backups.collection(officeId), 1000).stream()
                .sorted(Comparator.comparing(Backup::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
        int kept = 0;
        for (Backup b : all) {
            boolean done = b.getStatus() == Backup.Status.CONCLUIDO;
            if (done && kept < KEEP_AT_LEAST) {
                kept++;
                continue;
            }
            if (b.getCreatedAt() != null && b.getCreatedAt().isBefore(limit)) {
                try {
                    if (b.getStoragePath() != null) {
                        storage.delete(b.getStoragePath());
                    }
                    backups.delete(officeId, b.getId());
                } catch (RuntimeException e) {
                    log.warn("Não foi possível remover o backup antigo {}", b.getId(), e);
                }
            }
        }
    }

    // ------------------------------------------------------------------ download, inspeção e recuperação

    private Backup load(String officeId, String id) {
        Backup b = backups.findById(officeId, id).orElseThrow(() -> new NotFoundException("Backup"));
        if (b.getStatus() != Backup.Status.CONCLUIDO || b.getStoragePath() == null) {
            throw new BusinessException("BACKUP_UNAVAILABLE", "Este backup não foi concluído.");
        }
        return b;
    }

    private byte[] plain(String officeId, Backup b) {
        byte[] enc = storage.get(b.getStoragePath());
        if (b.getSha256() != null && !b.getSha256().equals(sha256(enc))) {
            throw new BusinessException("BACKUP_CORRUPTED", "O arquivo do backup não confere com o resumo (SHA-256) registrado.");
        }
        return crypto.decryptBytes(enc);
    }

    /** Arquivo .jsonl.gz (já decifrado). Dados sensíveis continuam cifrados campo a campo. */
    public byte[] download(AuthenticatedUser user, String id) {
        String officeId = user.requireOffice();
        Backup b = load(officeId, id);
        byte[] data = plain(officeId, b);
        events.record(user, "BACKUP_DOWNLOADED", SecurityEvent.Severity.ATENCAO, "Backup " + id + " baixado por " + user.displayName());
        audit.record(user, "BACKUP_DOWNLOADED", "backup", id, "Backup baixado (" + b.getDocuments() + " registros)");
        return data;
    }

    /** Compara o backup com o banco atual: quantos registros de cada coleção não existem mais. */
    public Inspection inspect(AuthenticatedUser user, String id) {
        String officeId = user.requireOffice();
        Backup b = load(officeId, id);
        Map<String, long[]> stats = new TreeMap<>();
        byte[] data = plain(officeId, b);
        forEachLine(data, (path, d) -> {
            String col = collectionOf(path);
            long[] s = stats.computeIfAbsent(col, k -> new long[2]);
            s[0]++;
        });
        Map<String, Long> missing = missing(officeId, data, null);
        List<CollectionDiff> out = new ArrayList<>();
        stats.forEach((col, s) -> out.add(new CollectionDiff(col, s[0], missing.getOrDefault(col, 0L), !NO_RESTORE.contains(col)
                && !col.equals("@office"))));
        return new Inspection(id, b.getCreatedAt(), out);
    }

    /** Recria os registros ausentes das coleções escolhidas (nunca sobrescreve). */
    public RestoreResult restore(AuthenticatedUser user, String id, List<String> collections) {
        String officeId = user.requireOffice();
        if (collections == null || collections.isEmpty()) {
            throw new BusinessException("COLLECTIONS_REQUIRED", "Escolha ao menos uma coleção para recuperar.");
        }
        for (String c : collections) {
            if (NO_RESTORE.contains(c) || c.equals("@office")) {
                throw new BusinessException("NOT_RESTORABLE", "A coleção " + c + " não pode ser recuperada por aqui.");
            }
        }
        Backup b = load(officeId, id);
        byte[] data = plain(officeId, b);
        Map<String, Long> restored = new TreeMap<>();
        DocumentReference office = db.collection("offices").document(officeId);
        List<Map.Entry<String, Map<String, Object>>> pending = new ArrayList<>();
        forEachLine(data, (path, d) -> {
            if (collections.contains(collectionOf(path)) && !path.startsWith("@")) {
                pending.add(Map.entry(path, d));
            }
        });
        for (int i = 0; i < pending.size(); i += 100) {
            List<Map.Entry<String, Map<String, Object>>> chunk = pending.subList(i, Math.min(i + 100, pending.size()));
            DocumentReference[] refs = chunk.stream().map(e -> office.collection(first(e.getKey())).document(rest(e.getKey()))).toArray(DocumentReference[]::new);
            List<DocumentSnapshot> snaps = Futures.await(db.getAll(refs));
            WriteBatch batch = db.batch();
            int ops = 0;
            for (int j = 0; j < snaps.size(); j++) {
                if (!snaps.get(j).exists()) {
                    batch.create(refs[j], decode(chunk.get(j).getValue()));
                    restored.merge(collectionOf(chunk.get(j).getKey()), 1L, Long::sum);
                    ops++;
                }
            }
            if (ops > 0) {
                Futures.await(batch.commit());
            }
        }
        long total = restored.values().stream().mapToLong(Long::longValue).sum();
        events.record(user, "BACKUP_RESTORED", SecurityEvent.Severity.CRITICO, total + " registro(s) recuperado(s) do backup " + id
                + " por " + user.displayName());
        Map<String, String> details = new TreeMap<>();
        restored.forEach((k, v) -> details.put(k, String.valueOf(v)));
        audit.record(user, "BACKUP_RESTORED", "backup", id, "Recuperação de " + total + " registro(s) ausente(s)", details);
        return new RestoreResult(id, restored, total);
    }

    private Map<String, Long> missing(String officeId, byte[] data, List<String> only) {
        Map<String, Long> out = new TreeMap<>();
        DocumentReference office = db.collection("offices").document(officeId);
        List<String> paths = new ArrayList<>();
        forEachLine(data, (path, d) -> {
            if (!path.startsWith("@") && (only == null || only.contains(collectionOf(path)))) {
                paths.add(path);
            }
        });
        for (int i = 0; i < paths.size(); i += 100) {
            List<String> chunk = paths.subList(i, Math.min(i + 100, paths.size()));
            DocumentReference[] refs = chunk.stream().map(p -> office.collection(first(p)).document(rest(p))).toArray(DocumentReference[]::new);
            List<DocumentSnapshot> snaps = Futures.await(db.getAll(refs));
            for (int j = 0; j < snaps.size(); j++) {
                if (!snaps.get(j).exists()) {
                    out.merge(collectionOf(chunk.get(j)), 1L, Long::sum);
                }
            }
        }
        return out;
    }

    private interface LineConsumer {
        void accept(String path, Map<String, Object> data);
    }

    private static void forEachLine(byte[] gz, LineConsumer consumer) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(new ByteArrayInputStream(gz)), StandardCharsets.UTF_8))) {
            String header = r.readLine();
            if (header == null || !header.contains("erpadv-backup")) {
                throw new BusinessException("BACKUP_FORMAT", "Arquivo de backup em formato desconhecido.");
            }
            String l;
            while ((l = r.readLine()) != null) {
                if (l.isBlank()) {
                    continue;
                }
                Map<String, Object> m = JSON.readValue(l, new TypeReference<>() {
                });
                @SuppressWarnings("unchecked")
                Map<String, Object> d = (Map<String, Object>) m.get("d");
                consumer.accept((String) m.get("p"), d);
            }
        } catch (IOException e) {
            throw new BusinessException("BACKUP_FORMAT", "Não foi possível ler o backup: " + e.getMessage());
        }
    }

    /** "clients/abc" → "clients"; "clients/abc/interactions/x" → "clients/interactions"; "@users/u" → "@users". */
    static String collectionOf(String path) {
        String[] parts = path.split("/");
        if (parts.length >= 4) {
            return parts[0] + "/" + parts[2];
        }
        return parts[0];
    }

    private static String first(String path) {
        return path.substring(0, path.indexOf('/'));
    }

    private static String rest(String path) {
        return path.substring(path.indexOf('/') + 1);
    }

    // ------------------------------------------------------------------ codificação dos valores do Firestore

    @SuppressWarnings("unchecked")
    static Object encode(Object v) {
        if (v instanceof Timestamp t) {
            return Map.of("$ts", t.toString());
        }
        if (v instanceof Blob b) {
            return Map.of("$blob", Base64.getEncoder().encodeToString(b.toBytes()));
        }
        if (v instanceof DocumentReference r) {
            return Map.of("$ref", r.getPath());
        }
        if (v instanceof GeoPoint g) {
            return Map.of("$geo", List.of(g.getLatitude(), g.getLongitude()));
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) m).forEach((k, x) -> out.put(k, encode(x)));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(x -> out.add(encode(x)));
            return out;
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> decode(Map<String, Object> data) {
        return (Map<String, Object>) decodeValue(data);
    }

    @SuppressWarnings("unchecked")
    private Object decodeValue(Object v) {
        if (v instanceof Map<?, ?> m) {
            if (m.size() == 1) {
                if (m.get("$ts") instanceof String s) {
                    return Timestamp.parseTimestamp(s);
                }
                if (m.get("$blob") instanceof String s) {
                    return Blob.fromBytes(Base64.getDecoder().decode(s));
                }
                if (m.get("$ref") instanceof String s) {
                    return db.document(s);
                }
                if (m.get("$geo") instanceof List<?> g && g.size() == 2) {
                    return new GeoPoint(((Number) g.get(0)).doubleValue(), ((Number) g.get(1)).doubleValue());
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) m).forEach((k, x) -> out.put(k, decodeValue(x)));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(x -> out.add(decodeValue(x)));
            return out;
        }
        if (v instanceof Integer i) {
            return i.longValue();
        }
        return v;
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
