package br.com.erpadv.services.platform;

import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.Office;
import br.com.erpadv.models.SupportTicket;
import br.com.erpadv.repositories.OfficeRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.NotificationService;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Suporte: chamados do escritório para a plataforma, com conversa, situação e aviso de resposta. */
@Service
public class SupportService {

    private static final Set<String> CATEGORIES = Set.of("DUVIDA", "PROBLEMA", "PLANO", "FINANCEIRO", "SUGESTAO");
    private static final Set<String> PRIORITIES = Set.of("BAIXA", "NORMAL", "ALTA");

    public record OpenRequest(String subject, String category, String priority, String message) {
    }

    public record ReplyRequest(String message, SupportTicket.Status status) {
    }

    @Repository
    public static class Tickets {
        private final Firestore db;
        private final FirestoreMapper mapper;

        public Tickets(Firestore db, FirestoreMapper mapper) {
            this.db = db;
            this.mapper = mapper;
        }

        com.google.cloud.firestore.CollectionReference col() {
            return db.collection("support_tickets");
        }

        public List<SupportTicket> list(Query q, int max) {
            List<SupportTicket> out = new ArrayList<>();
            for (QueryDocumentSnapshot d : Futures.await(q.limit(max).get()).getDocuments()) {
                out.add(mapper.fromSnapshot(d, SupportTicket.class));
            }
            return out;
        }

        public java.util.Optional<SupportTicket> find(String id) {
            DocumentSnapshot s = Futures.await(col().document(id).get());
            return s.exists() ? java.util.Optional.of(mapper.fromSnapshot(s, SupportTicket.class)) : java.util.Optional.empty();
        }

        public void save(SupportTicket t) {
            Futures.await(col().document(t.getId()).set(mapper.toMap(t)));
        }

        String nextNumber() {
            int year = Year.now().getValue();
            DocumentReference counter = db.collection("platform_counters").document("tickets-" + year);
            long next = Futures.await(db.runTransaction(tx -> {
                DocumentSnapshot snap = tx.get(counter).get();
                Long current = snap.exists() ? snap.getLong("value") : null;
                long value = current == null ? 1 : current + 1;
                tx.set(counter, Map.of("value", value));
                return value;
            }));
            return String.format("SUP-%d-%05d", year, next);
        }
    }

    private final Tickets tickets;
    private final OfficeRepository offices;
    private final NotificationService notifications;

    public SupportService(Tickets tickets, OfficeRepository offices, NotificationService notifications) {
        this.tickets = tickets;
        this.offices = offices;
        this.notifications = notifications;
    }

    // ------------------------------------------------------------------ escritório

    public List<SupportTicket> mine(AuthenticatedUser user) {
        return tickets.list(tickets.col().whereEqualTo("officeId", user.requireOffice()), 500).stream()
                .sorted(Comparator.comparing(SupportTicket::getLastMessageAt, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
    }

    public SupportTicket open(AuthenticatedUser user, OpenRequest req) {
        String officeId = user.requireOffice();
        String subject = clean(req.subject(), 160);
        String message = clean(req.message(), 8000);
        if (subject == null || message == null) {
            throw new BusinessException("INVALID_TICKET", "Informe o assunto e descreva o que precisa.");
        }
        Office office = offices.findById(officeId).orElseThrow(() -> new NotFoundException("Escritório"));
        SupportTicket t = new SupportTicket();
        t.setId(UUID.randomUUID().toString());
        t.setOfficeId(officeId);
        t.setNumber(tickets.nextNumber());
        t.setOfficeName(office.getName());
        t.setSubject(subject);
        t.setCategory(CATEGORIES.contains(req.category()) ? req.category() : "DUVIDA");
        t.setPriority(PRIORITIES.contains(req.priority()) ? req.priority() : "NORMAL");
        t.setOpenedByUid(user.uid());
        t.setOpenedByName(user.displayName());
        t.setOpenedByEmail(user.email());
        t.setCreatedAt(Instant.now());
        t.getMessages().add(message(user.displayName(), false, message));
        t.setLastMessageAt(Instant.now());
        t.setAwaitingPlatform(true);
        tickets.save(t);
        return t;
    }

    public SupportTicket get(AuthenticatedUser user, String id) {
        SupportTicket t = tickets.find(id).orElseThrow(() -> new NotFoundException("Chamado"));
        if (!t.getOfficeId().equals(user.officeId())) {
            throw new NotFoundException("Chamado");
        }
        return t;
    }

    public SupportTicket reply(AuthenticatedUser user, String id, ReplyRequest req) {
        SupportTicket t = get(user, id);
        String text = clean(req.message(), 8000);
        if (text == null && req.status() != SupportTicket.Status.RESOLVIDO) {
            throw new BusinessException("EMPTY_MESSAGE", "Escreva a mensagem.");
        }
        if (text != null) {
            t.getMessages().add(message(user.displayName(), false, text));
            t.setAwaitingPlatform(true);
            t.setStatus(SupportTicket.Status.EM_ATENDIMENTO);
        }
        if (req.status() == SupportTicket.Status.RESOLVIDO) {
            t.setStatus(SupportTicket.Status.RESOLVIDO);
            t.setAwaitingPlatform(false);
        }
        t.setLastMessageAt(Instant.now());
        tickets.save(t);
        return t;
    }

    // ------------------------------------------------------------------ plataforma

    public List<SupportTicket> all(SupportTicket.Status status) {
        Query q = status == null ? tickets.col() : tickets.col().whereEqualTo("status", status.name());
        return tickets.list(q, 1000).stream()
                .sorted(Comparator.comparing(SupportTicket::isAwaitingPlatform).reversed()
                        .thenComparing(SupportTicket::getLastMessageAt, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
    }

    public SupportTicket platformReply(AuthenticatedUser master, String id, ReplyRequest req) {
        SupportTicket t = tickets.find(id).orElseThrow(() -> new NotFoundException("Chamado"));
        String text = clean(req.message(), 8000);
        if (text != null) {
            t.getMessages().add(message("Suporte ERPAdv (" + master.displayName() + ")", true, text));
        }
        t.setStatus(req.status() != null ? req.status() : text != null ? SupportTicket.Status.AGUARDANDO_ESCRITORIO : t.getStatus());
        t.setAwaitingPlatform(false);
        t.setLastMessageAt(Instant.now());
        tickets.save(t);
        if (text != null) {
            notifications.notify(t.getOfficeId(), t.getOpenedByUid(), "SUPPORT", "Resposta do suporte: " + t.getNumber(),
                    text.length() > 140 ? text.substring(0, 140) + "…" : text, "/suporte");
        }
        return t;
    }

    private static SupportTicket.Message message(String author, boolean platform, String text) {
        SupportTicket.Message m = new SupportTicket.Message();
        m.setAt(Instant.now());
        m.setAuthorName(author);
        m.setFromPlatform(platform);
        m.setText(text);
        return m;
    }

    private static String clean(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
