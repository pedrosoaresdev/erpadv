package br.com.erpadv.services;

import br.com.erpadv.config.AppProperties;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.security.AuthenticatedUser;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.WebpushConfig;
import com.google.firebase.messaging.WebpushFcmOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Notificações internas em {@code offices/{officeId}/notifications} (lidas em tempo real pelo
 * navegador; Security Rules liberam apenas o destinatário) + push via Firebase Cloud Messaging para
 * os dispositivos registrados em {@code offices/{officeId}/device_tokens}.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final DateTimeFormatter BR_DATETIME = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm")
            .withZone(CourtCalendarService.ZONE);

    private final Firestore db;
    private final FirebaseMessaging messaging;
    private final boolean emulator;

    public NotificationService(Firestore db, FirebaseMessaging messaging, AppProperties properties) {
        this.db = db;
        this.messaging = messaging;
        this.emulator = properties.firebase().emulator();
    }

    public static String format(Instant instant) {
        return BR_DATETIME.format(instant);
    }

    public void notify(String officeId, String recipientUid, String type, String title, String body, String link) {
        Map<String, Object> data = new HashMap<>();
        data.put("officeId", officeId);
        data.put("recipientUid", recipientUid);
        data.put("type", type);
        data.put("title", title);
        data.put("body", body);
        data.put("read", false);
        data.put("createdAt", System.currentTimeMillis());
        if (link != null) {
            data.put("link", link);
        }
        try {
            Futures.await(office(officeId).collection("notifications").add(data));
        } catch (RuntimeException e) {
            // Notificação é acessória: não deve desfazer a operação principal.
            log.warn("Falha ao registrar notificação type={} recipient={}", type, recipientUid, e);
        }
        push(officeId, recipientUid, title, body, link);
    }

    // ------------------------------------------------------------------ dispositivos (FCM)

    public void registerDevice(AuthenticatedUser user, String token, String platform) {
        Map<String, Object> data = new HashMap<>();
        data.put("officeId", user.officeId());
        data.put("uid", user.uid());
        data.put("token", token);
        data.put("platform", platform == null ? "WEB" : platform);
        data.put("updatedAt", System.currentTimeMillis());
        Futures.await(tokens(user.requireOffice()).document(tokenId(token)).set(data));
    }

    public void unregisterDevice(AuthenticatedUser user, String token) {
        var ref = tokens(user.requireOffice()).document(tokenId(token));
        var snapshot = Futures.await(ref.get());
        if (snapshot.exists() && user.uid().equals(snapshot.getString("uid"))) {
            Futures.await(ref.delete());
        }
    }

    private void push(String officeId, String uid, String title, String body, String link) {
        if (emulator) {
            log.debug("FCM desativado no Emulator Suite (push para {} não enviado)", uid);
            return;
        }
        try {
            List<QueryDocumentSnapshot> docs = Futures.await(tokens(officeId).whereEqualTo("uid", uid).limit(10).get())
                    .getDocuments();
            if (docs.isEmpty()) {
                return;
            }
            List<String> registrationTokens = docs.stream().map(d -> d.getString("token")).toList();
            MulticastMessage.Builder message = MulticastMessage.builder()
                    .addAllTokens(registrationTokens)
                    .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                    .putData("link", link == null ? "/" : link);
            if (link != null) {
                message.setWebpushConfig(WebpushConfig.builder()
                        .setFcmOptions(WebpushFcmOptions.withLink(link)).build());
            }
            BatchResponse response = messaging.sendEachForMulticast(message.build());
            for (int i = 0; i < response.getResponses().size(); i++) {
                FirebaseMessagingException error = response.getResponses().get(i).getException();
                if (error != null && (error.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED
                        || error.getMessagingErrorCode() == MessagingErrorCode.INVALID_ARGUMENT)) {
                    docs.get(i).getReference().delete();
                }
            }
        } catch (FirebaseMessagingException | RuntimeException e) {
            log.warn("Falha no envio push para {}", uid, e);
        }
    }

    private com.google.cloud.firestore.DocumentReference office(String officeId) {
        return db.collection("offices").document(officeId);
    }

    private CollectionReference tokens(String officeId) {
        return office(officeId).collection("device_tokens");
    }

    static String tokenId(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
