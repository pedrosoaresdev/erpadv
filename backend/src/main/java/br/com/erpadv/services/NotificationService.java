package br.com.erpadv.services;

import br.com.erpadv.firebase.Futures;
import com.google.cloud.firestore.Firestore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Notificações internas em {@code offices/{officeId}/notifications}. O navegador as recebe em tempo
 * real (Security Rules liberam leitura apenas ao destinatário). O envio push via FCM para
 * dispositivos registrados entra com o módulo de prazos (Etapa 9).
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final Firestore db;

    public NotificationService(Firestore db) {
        this.db = db;
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
            Futures.await(db.collection("offices").document(officeId).collection("notifications").add(data));
        } catch (RuntimeException e) {
            // Notificação é acessória: não deve desfazer a operação principal.
            log.warn("Falha ao registrar notificação type={} recipient={}", type, recipientUid, e);
        }
    }
}
