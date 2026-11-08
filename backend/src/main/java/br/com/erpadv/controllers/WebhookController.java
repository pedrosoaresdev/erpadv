package br.com.erpadv.controllers;

import br.com.erpadv.services.CommunicationService;
import br.com.erpadv.services.integrations.HttpJson;
import br.com.erpadv.services.integrations.WhatsAppService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhooks públicos (sem token de usuário). Cada um valida a origem: assinatura HMAC da Meta (WhatsApp), segredo
 * do provedor (assinatura eletrônica) ou estado assinado (OAuth do Google).
 */
@RestController
@RequestMapping("/api/hooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WhatsAppService whatsapp;
    private final CommunicationService communications;
    @org.springframework.beans.factory.annotation.Autowired
    private br.com.erpadv.services.integrations.GoogleCalendarService google;
    @org.springframework.beans.factory.annotation.Autowired
    private br.com.erpadv.services.integrations.SignatureService signatures;

    @PostMapping("/zapsign/{officeId}")
    public ResponseEntity<Void> zapsign(@PathVariable String officeId, @RequestParam(required = false) String secret, @RequestBody byte[] body) {
        try {
            boolean ok = signatures.webhook(officeId, secret, HttpJson.JSON.readTree(body));
            return ok ? ResponseEntity.ok().build() : ResponseEntity.status(401).build();
        } catch (java.io.IOException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    public WebhookController(WhatsAppService whatsapp, CommunicationService communications) {
        this.whatsapp = whatsapp;
        this.communications = communications;
    }

    /** Retorno do consentimento do Google: valida o estado assinado e redireciona de volta ao sistema. */
    @GetMapping("/google/callback")
    public ResponseEntity<Void> googleCallback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                                               @RequestParam(required = false) String error) {
        return ResponseEntity.status(302).location(java.net.URI.create(google.callback(code, state, error))).build();
    }

    @GetMapping(value = "/whatsapp/{officeId}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verifyWhatsApp(@PathVariable String officeId,
                                                 @RequestParam(name = "hub.mode", required = false) String mode,
                                                 @RequestParam(name = "hub.verify_token", required = false) String token,
                                                 @RequestParam(name = "hub.challenge", required = false) String challenge) {
        String ok = whatsapp.verify(officeId, mode, token, challenge);
        return ok == null ? ResponseEntity.status(403).build() : ResponseEntity.ok(ok);
    }

    @PostMapping("/whatsapp/{officeId}")
    public ResponseEntity<Void> receiveWhatsApp(@PathVariable String officeId, @RequestBody byte[] body,
                                                @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) {
        if (!whatsapp.validSignature(officeId, body, signature)) {
            log.warn("Webhook WhatsApp com assinatura inválida para o escritório {}", officeId);
            return ResponseEntity.status(401).build();
        }
        try {
            JsonNode payload = HttpJson.JSON.readTree(body);
            WhatsAppService.parse(payload).forEach(in -> communications.receiveWhatsApp(officeId, in));
        } catch (java.io.IOException e) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok().build();
    }
}
