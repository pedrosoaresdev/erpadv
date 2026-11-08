package br.com.erpadv.services.integrations;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.dto.DocumentDtos.DocumentResponse;
import br.com.erpadv.dto.DocumentDtos.FileContent;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.models.BaseEntity;
import br.com.erpadv.models.IntegrationProvider;
import br.com.erpadv.repositories.FirestoreRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Permission;
import br.com.erpadv.security.Role;
import br.com.erpadv.services.DocumentService;
import br.com.erpadv.services.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.cloud.firestore.Firestore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Assinatura eletrônica pela ZapSign (API oficial): envia um PDF da central de documentos aos signatários, acompanha
 * o status e, quando todos assinam, guarda a via assinada como nova versão do mesmo documento.
 * Documentação: https://docs.zapsign.com.br
 */
@Service
public class SignatureService {

    @Data
    public static class Signer {
        private String name;
        private String email;
        private String phone;
        private String status;
        private String signUrl;
        private String token;
        private Instant signedAt;
    }

    /** Pedido de assinatura ({@code offices/{officeId}/signature_requests/{id}}). */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class SignatureRequest extends BaseEntity {
        private String provider = "ZAPSIGN";
        private String documentId;
        private String documentTitle;
        private String externalToken;
        private String status;
        private List<Signer> signers = new ArrayList<>();
        private boolean signedStored;
        private Integer signedVersion;
        private String createdByName;
        private Instant completedAt;
        private Instant lastCheckedAt;
        private boolean sandbox;
        /** URL temporária da via assinada (do provedor); usada só para baixar e guardar. */
        private String signedFileUrl;
    }

    @Repository
    public static class Requests extends FirestoreRepository<SignatureRequest> {
        public Requests(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, SignatureRequest.class, "signature_requests");
        }
    }

    public record SignerInput(String name, String email, String phone) {
    }

    public record SendRequest(String documentId, List<SignerInput> signers) {
    }

    private final Requests requests;
    private final DocumentService documents;
    private final IntegrationService integrations;
    private final NotificationService notifications;
    private final AuditService audit;
    private final String prodUrl;
    private final String sandboxUrl;
    private final boolean localProvider;

    public SignatureService(Requests requests, DocumentService documents, IntegrationService integrations, NotificationService notifications,
                            AuditService audit,
                            @Value("${app.integrations.zapsign.url:https://api.zapsign.com.br}") String prodUrl,
                            @Value("${app.integrations.zapsign.sandbox-url:https://sandbox.api.zapsign.com.br}") String sandboxUrl) {
        this.requests = requests;
        this.documents = documents;
        this.integrations = integrations;
        this.notifications = notifications;
        this.audit = audit;
        this.prodUrl = prodUrl;
        this.sandboxUrl = sandboxUrl;
        this.localProvider = isLocal(prodUrl) || isLocal(sandboxUrl);
    }

    private boolean sandbox(String officeId) {
        return "true".equalsIgnoreCase(integrations.config(officeId, IntegrationProvider.ZAPSIGN, "sandbox"));
    }

    private String base(String officeId) {
        return sandbox(officeId) ? sandboxUrl : prodUrl;
    }

    private Map<String, String> auth(String officeId) {
        return Map.of("Authorization", "Bearer " + integrations.secret(officeId, IntegrationProvider.ZAPSIGN, "apiToken"));
    }

    public List<SignatureRequest> list(AuthenticatedUser user, String documentId) {
        var q = requests.collection(user.requireOffice());
        var query = documentId == null || documentId.isBlank() ? q.orderBy("createdAt", com.google.cloud.firestore.Query.Direction.DESCENDING)
                : q.whereEqualTo("documentId", documentId);
        return requests.list(query, 200).stream()
                .sorted(Comparator.comparing(SignatureRequest::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
    }

    public SignatureRequest send(AuthenticatedUser user, SendRequest req) {
        String officeId = user.requireOffice();
        if (!integrations.active(officeId, IntegrationProvider.ZAPSIGN)) {
            throw new BusinessException("SIGNATURE_NOT_CONFIGURED", "Configure a assinatura eletrônica (ZapSign) na Central de Integrações.");
        }
        if (req.signers() == null || req.signers().isEmpty() || req.signers().size() > 20) {
            throw new BusinessException("SIGNERS_REQUIRED", "Informe de 1 a 20 signatários.");
        }
        DocumentResponse doc = documents.get(user, req.documentId());
        if (!"application/pdf".equals(doc.contentType())) {
            throw new BusinessException("PDF_REQUIRED", "A assinatura eletrônica aceita documentos em PDF (exporte do editor ou envie o PDF).");
        }
        FileContent file = documents.content(user, req.documentId(), null, true);
        SignatureRequest r = new SignatureRequest();
        r.setId(UUID.randomUUID().toString());
        List<Map<String, Object>> signers = new ArrayList<>();
        for (SignerInput s : req.signers()) {
            if (s.name() == null || s.name().isBlank() || s.email() == null || !s.email().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                throw new BusinessException("INVALID_SIGNER", "Cada signatário precisa de nome e e-mail válido.");
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name().trim());
            m.put("email", s.email().trim());
            String phone = s.phone() == null ? "" : s.phone().replaceAll("\\D", "");
            if (phone.length() >= 10) {
                m.put("phone_country", "55");
                m.put("phone_number", phone.length() > 11 ? phone.substring(phone.length() - 11) : phone);
            }
            m.put("auth_mode", "assinaturaTela");
            m.put("send_automatic_email", true);
            signers.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", doc.title());
        body.put("base64_pdf", Base64.getEncoder().encodeToString(file.bytes()));
        body.put("external_id", r.getId());
        body.put("lang", "pt-br");
        body.put("signers", signers);
        JsonNode resp;
        try {
            resp = HttpJson.post(base(officeId) + "/api/v1/docs/", auth(officeId), body);
        } catch (HttpJson.ExternalServiceException e) {
            integrations.recordError(officeId, IntegrationProvider.ZAPSIGN, e.getMessage());
            throw new BusinessException("SIGNATURE_FAILED", e.getMessage());
        }
        integrations.recordSuccess(officeId, IntegrationProvider.ZAPSIGN);
        r.setDocumentId(doc.id());
        r.setDocumentTitle(doc.title());
        r.setSandbox(sandbox(officeId));
        apply(r, resp);
        r.setCreatedAt(Instant.now());
        r.setCreatedBy(user.uid());
        r.setCreatedByName(user.displayName());
        requests.save(officeId, r);
        audit.record(user, "SIGNATURE_SENT", "document", doc.id(), "Enviado para assinatura eletrônica: " + doc.title(),
                Map.of("signers", String.valueOf(signers.size()), "provider", "ZapSign"));
        return r;
    }

    /** Atualiza o status no provedor e, se tudo estiver assinado, guarda a via assinada. */
    public SignatureRequest refresh(AuthenticatedUser user, String id) {
        String officeId = user.requireOffice();
        SignatureRequest r = requests.findById(officeId, id).orElseThrow(() -> new NotFoundException("Pedido de assinatura"));
        try {
            apply(r, HttpJson.get(base(officeId) + "/api/v1/docs/" + r.getExternalToken() + "/", auth(officeId)));
        } catch (HttpJson.ExternalServiceException e) {
            throw new BusinessException("SIGNATURE_FAILED", e.getMessage());
        }
        storeIfSigned(officeId, r, user);
        requests.save(officeId, r);
        return r;
    }

    /** Webhook do provedor (segredo na URL). Atualiza status e guarda a via assinada em nome de quem enviou. */
    public boolean webhook(String officeId, String secret, JsonNode payload) {
        String expected = integrations.secret(officeId, IntegrationProvider.ZAPSIGN, "webhookSecret");
        if (expected == null || secret == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8))) {
            return false;
        }
        String token = payload.path("token").asText(null);
        if (token == null) {
            return true;
        }
        SignatureRequest r = requests.list(requests.collection(officeId).whereEqualTo("externalToken", token), 1).stream().findFirst().orElse(null);
        if (r == null) {
            return true;
        }
        try {
            apply(r, HttpJson.get(base(officeId) + "/api/v1/docs/" + token + "/", auth(officeId)));
        } catch (HttpJson.ExternalServiceException e) {
            apply(r, payload);
        }
        AuthenticatedUser actor = new AuthenticatedUser(r.getCreatedBy(), null, "Assinatura eletrônica (ZapSign)", true, officeId, Role.ADVOGADO, null,
                EnumSet.of(Permission.DOCUMENTS_READ, Permission.DOCUMENTS_WRITE, Permission.DOCUMENTS_CONFIDENTIAL, Permission.CRIMINAL_READ));
        boolean justSigned = !r.isSignedStored() && "ASSINADO".equals(r.getStatus());
        storeIfSigned(officeId, r, actor);
        requests.save(officeId, r);
        if (justSigned && r.getCreatedBy() != null) {
            notifications.notify(officeId, r.getCreatedBy(), "SIGNATURE", "Documento assinado por todos", r.getDocumentTitle(),
                    "/documentos?doc=" + r.getDocumentId());
        }
        return true;
    }

    private void storeIfSigned(String officeId, SignatureRequest r, AuthenticatedUser actor) {
        if (!"ASSINADO".equals(r.getStatus()) || r.isSignedStored() || r.getSignedFileUrl() == null) {
            return;
        }
        byte[] pdf = HttpJson.download(r.getSignedFileUrl(), Map.of(), 30L * 1024 * 1024);
        DocumentResponse d = documents.newVersion(actor, r.getDocumentId(), pdf, "assinado-" + r.getDocumentTitle() + ".pdf",
                "Via assinada eletronicamente (ZapSign)");
        r.setSignedStored(true);
        r.setSignedVersion(d.currentVersion());
        r.setCompletedAt(Instant.now());
        audit.record(actor, "SIGNATURE_COMPLETED", "document", r.getDocumentId(), "Via assinada guardada (v" + d.currentVersion() + "): "
                + r.getDocumentTitle());
    }

    private static boolean isLocal(String url) {
        return url != null && (url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:"));
    }

    private void apply(SignatureRequest r, JsonNode resp) {
        if (resp.hasNonNull("token")) {
            r.setExternalToken(resp.get("token").asText());
        }
        String st = resp.path("status").asText("pending");
        r.setStatus(switch (st) {
            case "signed" -> "ASSINADO";
            case "refused" -> "RECUSADO";
            case "canceled", "cancelled" -> "CANCELADO";
            default -> "ENVIADO";
        });
        String signed = resp.path("signed_file").asText(null);
        // só HTTPS; endereço local apenas quando o próprio provedor configurado é local (servidor de teste)
        r.setSignedFileUrl(signed != null && (signed.startsWith("https://") || (localProvider && isLocal(signed))) ? signed : null);
        if (resp.path("signers").isArray() && !resp.path("signers").isEmpty()) {
            List<Signer> out = new ArrayList<>();
            for (JsonNode s : resp.path("signers")) {
                Signer x = new Signer();
                x.setName(s.path("name").asText(null));
                x.setEmail(s.path("email").asText(null));
                x.setToken(s.path("token").asText(null));
                x.setSignUrl(s.path("sign_url").asText(null));
                x.setStatus(switch (s.path("status").asText("new")) {
                    case "signed" -> "ASSINOU";
                    case "refused" -> "RECUSOU";
                    case "link-opened" -> "ABRIU";
                    default -> "PENDENTE";
                });
                String at = s.path("signed_at").asText(null);
                if (at != null && !at.isBlank()) {
                    try {
                        x.setSignedAt(java.time.OffsetDateTime.parse(at).toInstant());
                    } catch (RuntimeException ignored) {
                        // formato inesperado
                    }
                }
                out.add(x);
            }
            r.setSigners(out);
        }
        r.setLastCheckedAt(Instant.now());
    }
}
