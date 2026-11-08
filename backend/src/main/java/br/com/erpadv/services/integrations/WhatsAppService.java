package br.com.erpadv.services.integrations;

import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.models.IntegrationProvider;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WhatsApp Business pela Cloud API oficial da Meta (Graph API). Envia texto (dentro da janela de 24 h aberta pelo
 * cliente) ou modelo aprovado; recebe mensagens pelo webhook com assinatura {@code X-Hub-Signature-256}.
 */
@Service
public class WhatsAppService {

    public record Incoming(String messageId, String from, String name, String text, long timestamp) {
    }

    private final IntegrationService integrations;
    private final String graphUrl;

    public WhatsAppService(IntegrationService integrations,
                           @Value("${app.integrations.whatsapp.graph-url:https://graph.facebook.com/v20.0}") String graphUrl) {
        this.integrations = integrations;
        this.graphUrl = graphUrl.endsWith("/") ? graphUrl.substring(0, graphUrl.length() - 1) : graphUrl;
    }

    public boolean available(String officeId) {
        return integrations.active(officeId, IntegrationProvider.WHATSAPP);
    }

    /** Número no formato E.164 sem "+" (Brasil: 55 + DDD + número). */
    public static String normalize(String phone) {
        String d = phone == null ? "" : phone.replaceAll("\\D", "");
        if (d.length() == 10 || d.length() == 11) {
            d = "55" + d;
        }
        if (d.length() < 12 || d.length() > 15) {
            throw new BusinessException("INVALID_PHONE", "Telefone inválido para WhatsApp (use DDD + número).");
        }
        return d;
    }

    /** Envia texto livre ou, se {@code template} vier preenchido, o modelo aprovado com os parâmetros. Retorna o id da mensagem. */
    public String send(String officeId, String phone, String text, String template, List<String> params) {
        if (!available(officeId)) {
            throw new BusinessException("WHATSAPP_NOT_CONFIGURED", "Configure o WhatsApp Business na Central de Integrações.");
        }
        String phoneId = integrations.config(officeId, IntegrationProvider.WHATSAPP, "phoneNumberId");
        String token = integrations.secret(officeId, IntegrationProvider.WHATSAPP, "accessToken");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("to", normalize(phone));
        if (template != null && !template.isBlank()) {
            body.put("type", "template");
            Map<String, Object> tpl = new LinkedHashMap<>();
            tpl.put("name", template.trim());
            tpl.put("language", Map.of("code", "pt_BR"));
            if (params != null && !params.isEmpty()) {
                List<Map<String, String>> p = new ArrayList<>();
                params.forEach(v -> p.add(Map.of("type", "text", "text", v)));
                tpl.put("components", List.of(Map.of("type", "body", "parameters", p)));
            }
            body.put("template", tpl);
        } else {
            body.put("type", "text");
            body.put("text", Map.of("preview_url", false, "body", text));
        }
        try {
            JsonNode r = HttpJson.post(graphUrl + "/" + phoneId + "/messages", Map.of("Authorization", "Bearer " + token), body);
            integrations.recordSuccess(officeId, IntegrationProvider.WHATSAPP);
            return r.path("messages").path(0).path("id").asText(null);
        } catch (HttpJson.ExternalServiceException e) {
            integrations.recordError(officeId, IntegrationProvider.WHATSAPP, e.getMessage());
            throw new BusinessException("WHATSAPP_FAILED", e.getMessage()
                    + " Fora da janela de 24 h após a última mensagem do cliente, a Meta só aceita modelos aprovados.");
        }
    }

    /** Verificação do webhook (GET hub.mode=subscribe): devolve o desafio quando o token confere. */
    public String verify(String officeId, String mode, String token, String challenge) {
        String expected = integrations.secret(officeId, IntegrationProvider.WHATSAPP, "verifyToken");
        if (!"subscribe".equals(mode) || expected == null || token == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        return challenge;
    }

    /** Confere a assinatura HMAC-SHA256 do corpo com o App Secret do aplicativo da Meta. */
    public boolean validSignature(String officeId, byte[] body, String header) {
        String secret = integrations.secret(officeId, IntegrationProvider.WHATSAPP, "appSecret");
        if (secret == null || header == null || !header.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            return false;
        }
    }

    /** Extrai as mensagens de texto recebidas do payload do webhook. */
    public static List<Incoming> parse(JsonNode payload) {
        List<Incoming> out = new ArrayList<>();
        for (JsonNode entry : payload.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                JsonNode value = change.path("value");
                Map<String, String> names = new LinkedHashMap<>();
                for (JsonNode c : value.path("contacts")) {
                    names.put(c.path("wa_id").asText(), c.path("profile").path("name").asText(null));
                }
                for (JsonNode m : value.path("messages")) {
                    String type = m.path("type").asText();
                    String text = switch (type) {
                        case "text" -> m.path("text").path("body").asText("");
                        case "button" -> m.path("button").path("text").asText("");
                        case "interactive" -> m.path("interactive").path("button_reply").path("title").asText("[resposta interativa]");
                        default -> "[" + type + " recebido — abra no WhatsApp para ver]";
                    };
                    String from = m.path("from").asText();
                    out.add(new Incoming(m.path("id").asText(), from, names.get(from), text, m.path("timestamp").asLong(0)));
                }
            }
        }
        return out;
    }
}
