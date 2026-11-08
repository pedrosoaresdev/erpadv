package br.com.erpadv.services.integrations;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.firebase.CryptoService;
import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.Futures;
import br.com.erpadv.models.Appointment;
import br.com.erpadv.models.BaseEntity;
import br.com.erpadv.models.IntegrationProvider;
import br.com.erpadv.models.MeetingPlatform;
import br.com.erpadv.repositories.AppointmentRepository;
import br.com.erpadv.repositories.FirestoreRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.CourtCalendarService;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.cloud.firestore.Firestore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Google Agenda e Google Meet por OAuth 2.0 do próprio advogado (escopo {@code calendar.events}): os compromissos em
 * que ele é responsável vão para a agenda dele, e reuniões marcadas como Google Meet sem link recebem a sala
 * automaticamente (conferenceData). O token de atualização fica cifrado; nenhum dado do Google é exposto ao navegador.
 */
@Service
public class GoogleCalendarService {

    private static final Logger log = LoggerFactory.getLogger(GoogleCalendarService.class);
    private static final String SCOPE = "openid email https://www.googleapis.com/auth/calendar.events";

    /** Vínculo do usuário com a conta Google ({@code offices/{officeId}/google_links/{uid}}). */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class GoogleLink extends BaseEntity {
        private String email;
        private String refreshTokenEnc;
        private String accessTokenEnc;
        private Instant accessExpiresAt;
        private Instant connectedAt;
    }

    @Repository
    public static class Links extends FirestoreRepository<GoogleLink> {
        public Links(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, GoogleLink.class, "google_links");
        }

        public void delete(String officeId, String id) {
            Futures.await(collection(officeId).document(id).delete());
        }
    }

    public record Status(boolean available, boolean connected, String email, Instant connectedAt) {
    }

    private final Links links;
    private final AppointmentRepository appointments;
    private final CryptoService crypto;
    private final IntegrationService integrations;
    private final AuditService audit;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String authUrl;
    private final String tokenUrl;
    private final String apiUrl;
    private final String appUrl;

    public GoogleCalendarService(Links links, AppointmentRepository appointments, CryptoService crypto, IntegrationService integrations,
                                 AuditService audit,
                                 @Value("${app.integrations.google.client-id:}") String clientId,
                                 @Value("${app.integrations.google.client-secret:}") String clientSecret,
                                 @Value("${app.integrations.google.redirect-uri:}") String redirectUri,
                                 @Value("${app.integrations.google.auth-url:https://accounts.google.com/o/oauth2/v2/auth}") String authUrl,
                                 @Value("${app.integrations.google.token-url:https://oauth2.googleapis.com}") String tokenUrl,
                                 @Value("${app.integrations.google.api-url:https://www.googleapis.com/calendar/v3}") String apiUrl,
                                 @Value("${app.integrations.public-app-url:https://localhost:8443}") String appUrl) {
        this.links = links;
        this.appointments = appointments;
        this.crypto = crypto;
        this.integrations = integrations;
        this.audit = audit;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.authUrl = authUrl;
        this.tokenUrl = tokenUrl;
        this.apiUrl = apiUrl;
        this.appUrl = appUrl.endsWith("/") ? appUrl.substring(0, appUrl.length() - 1) : appUrl;
    }

    public boolean configured() {
        return notBlank(clientId) && notBlank(clientSecret) && notBlank(redirectUri);
    }

    public Status status(AuthenticatedUser user) {
        GoogleLink l = links.findById(user.requireOffice(), user.uid()).orElse(null);
        return new Status(configured(), l != null, l == null ? null : l.getEmail(), l == null ? null : l.getConnectedAt());
    }

    // ------------------------------------------------------------------ OAuth

    public String authorizationUrl(AuthenticatedUser user) {
        if (!configured()) {
            throw new BusinessException("GOOGLE_NOT_CONFIGURED", "O administrador da plataforma ainda não configurou o aplicativo Google (GOOGLE_CLIENT_ID/SECRET).");
        }
        String payload = user.requireOffice() + "|" + user.uid() + "|" + (Instant.now().getEpochSecond() + 600) + "|" + UUID.randomUUID();
        String state = b64(payload) + "." + crypto.hmac("google-oauth|" + payload);
        return authUrl + "?client_id=" + enc(clientId) + "&redirect_uri=" + enc(redirectUri) + "&response_type=code&scope=" + enc(SCOPE)
                + "&access_type=offline&prompt=consent&include_granted_scopes=true&state=" + enc(state);
    }

    /** Retorno do consentimento: troca o código pelos tokens e devolve a URL do sistema para redirecionar. */
    public String callback(String code, String state, String error) {
        String[] parts = verifyState(state);
        if (parts == null) {
            return appUrl + "/integracoes?google=erro";
        }
        if (error != null || code == null) {
            return appUrl + "/integracoes?google=cancelado";
        }
        String officeId = parts[0];
        String uid = parts[1];
        try {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("code", code);
            form.put("client_id", clientId);
            form.put("client_secret", clientSecret);
            form.put("redirect_uri", redirectUri);
            form.put("grant_type", "authorization_code");
            JsonNode t = HttpJson.postForm(tokenUrl + "/token", form);
            String refresh = t.path("refresh_token").asText(null);
            if (refresh == null) {
                return appUrl + "/integracoes?google=sem-permissao";
            }
            GoogleLink l = links.findById(officeId, uid).orElseGet(GoogleLink::new);
            l.setId(uid);
            l.setEmail(emailFromIdToken(t.path("id_token").asText(null)));
            l.setRefreshTokenEnc(crypto.encrypt(refresh));
            l.setAccessTokenEnc(crypto.encrypt(t.path("access_token").asText()));
            l.setAccessExpiresAt(Instant.now().plusSeconds(Math.max(60, t.path("expires_in").asLong(3600) - 60)));
            l.setConnectedAt(Instant.now());
            l.setCreatedAt(l.getConnectedAt());
            l.setCreatedBy(uid);
            links.save(officeId, l);
            integrations.recordSuccess(officeId, IntegrationProvider.GOOGLE_CALENDAR);
            return appUrl + "/integracoes?google=conectado";
        } catch (HttpJson.ExternalServiceException e) {
            integrations.recordError(officeId, IntegrationProvider.GOOGLE_CALENDAR, e.getMessage());
            return appUrl + "/integracoes?google=erro";
        }
    }

    public void disconnect(AuthenticatedUser user) {
        GoogleLink l = links.findById(user.requireOffice(), user.uid()).orElse(null);
        if (l == null) {
            return;
        }
        try {
            HttpJson.postForm(tokenUrl + "/revoke", Map.of("token", crypto.decrypt(l.getRefreshTokenEnc())));
        } catch (RuntimeException e) {
            log.info("Revogação do token Google falhou (será removido mesmo assim): {}", e.getMessage());
        }
        links.delete(user.officeId(), user.uid());
        audit.record(user, "GOOGLE_DISCONNECTED", "integration", "GOOGLE_CALENDAR", "Conta Google desconectada");
    }

    String[] verifyState(String state) {
        if (state == null || !state.contains(".")) {
            return null;
        }
        String[] s = state.split("\\.", 2);
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(s[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!MessageDigest.isEqual(crypto.hmac("google-oauth|" + payload).getBytes(StandardCharsets.UTF_8), s[1].getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        String[] parts = payload.split("\\|");
        if (parts.length < 4 || Long.parseLong(parts[2]) < Instant.now().getEpochSecond()) {
            return null;
        }
        return parts;
    }

    private String accessToken(String officeId, GoogleLink l) {
        if (l.getAccessTokenEnc() != null && l.getAccessExpiresAt() != null && l.getAccessExpiresAt().isAfter(Instant.now())) {
            return crypto.decrypt(l.getAccessTokenEnc());
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("refresh_token", crypto.decrypt(l.getRefreshTokenEnc()));
        form.put("grant_type", "refresh_token");
        JsonNode t = HttpJson.postForm(tokenUrl + "/token", form);
        l.setAccessTokenEnc(crypto.encrypt(t.path("access_token").asText()));
        l.setAccessExpiresAt(Instant.now().plusSeconds(Math.max(60, t.path("expires_in").asLong(3600) - 60)));
        links.save(officeId, l);
        return t.path("access_token").asText();
    }

    // ------------------------------------------------------------------ eventos

    /**
     * Cria/atualiza o evento na agenda Google de cada responsável conectado. Se a reunião for Google Meet sem link,
     * a sala é criada na agenda do primeiro responsável conectado e o link é gravado no compromisso.
     * Falhas não impedem a operação no ERPAdv (ficam registradas como alerta da integração).
     */
    public void push(String officeId, Appointment a) {
        if (!configured() || !integrations.active(officeId, IntegrationProvider.GOOGLE_CALENDAR)) {
            return;
        }
        boolean wantsMeet = a.getMeetingPlatform() == MeetingPlatform.GOOGLE_MEET && (a.getMeetingUrl() == null || a.getMeetingUrl().isBlank());
        boolean changed = false;
        for (String uid : a.getResponsibleUids()) {
            GoogleLink l = links.findById(officeId, uid).orElse(null);
            if (l == null) {
                continue;
            }
            try {
                String token = accessToken(officeId, l);
                Map<String, String> auth = Map.of("Authorization", "Bearer " + token);
                String eventId = a.getGoogleEventIds().get(uid);
                Map<String, Object> body = event(a, wantsMeet);
                JsonNode r;
                if (eventId == null) {
                    r = HttpJson.post(apiUrl + "/calendars/primary/events?conferenceDataVersion=1&sendUpdates=none", auth, body);
                    a.getGoogleEventIds().put(uid, r.path("id").asText());
                    changed = true;
                } else {
                    r = HttpJson.request("PATCH", apiUrl + "/calendars/primary/events/" + enc(eventId) + "?conferenceDataVersion=1&sendUpdates=none", auth, body);
                }
                String meet = r.path("hangoutLink").asText(null);
                if (wantsMeet && meet != null && meet.startsWith("https://meet.google.com/")) {
                    a.setMeetingUrl(meet);
                    wantsMeet = false;
                    changed = true;
                }
            } catch (HttpJson.ExternalServiceException e) {
                integrations.recordError(officeId, IntegrationProvider.GOOGLE_CALENDAR, "Agenda de " + l.getEmail() + ": " + e.getMessage());
            }
        }
        if (changed) {
            appointments.save(officeId, a);
        }
    }

    public void remove(String officeId, Appointment a) {
        if (!configured() || a.getGoogleEventIds().isEmpty()) {
            return;
        }
        for (var e : a.getGoogleEventIds().entrySet()) {
            GoogleLink l = links.findById(officeId, e.getKey()).orElse(null);
            if (l == null) {
                continue;
            }
            try {
                HttpJson.request("DELETE", apiUrl + "/calendars/primary/events/" + enc(e.getValue()) + "?sendUpdates=none",
                        Map.of("Authorization", "Bearer " + accessToken(officeId, l)), null);
            } catch (HttpJson.ExternalServiceException ex) {
                if (ex.status() != 404 && ex.status() != 410) {
                    integrations.recordError(officeId, IntegrationProvider.GOOGLE_CALENDAR, ex.getMessage());
                }
            }
        }
    }

    static Map<String, Object> event(Appointment a, boolean createMeet) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("summary", a.getType().label() + ": " + a.getTitle());
        StringBuilder d = new StringBuilder("Compromisso do ERPAdv");
        if (a.getClientName() != null) {
            d.append("\nCliente: ").append(a.getClientName());
        }
        if (a.getCaseNumber() != null) {
            d.append("\nProcesso: ").append(a.getCaseNumber());
        }
        if (a.getMeetingUrl() != null) {
            d.append("\nSala: ").append(a.getMeetingUrl());
        }
        if (a.getDescription() != null && !a.isRestricted()) {
            d.append("\n\n").append(a.getDescription());
        }
        body.put("description", d.toString());
        if (a.getLocation() != null) {
            body.put("location", a.getLocation());
        }
        String zone = CourtCalendarService.ZONE.getId();
        if (a.isAllDay()) {
            LocalDate day = LocalDate.ofInstant(a.getStart(), CourtCalendarService.ZONE);
            body.put("start", Map.of("date", day.toString()));
            body.put("end", Map.of("date", day.plusDays(1).toString()));
        } else {
            body.put("start", Map.of("dateTime", a.getStart().toString(), "timeZone", zone));
            body.put("end", Map.of("dateTime", a.getEnd().toString(), "timeZone", zone));
        }
        if (a.getStatus() == br.com.erpadv.models.AppointmentStatus.CANCELADO) {
            body.put("status", "cancelled");
        }
        if (createMeet) {
            body.put("conferenceData", Map.of("createRequest", Map.of("requestId", "erpadv-" + a.getId(),
                    "conferenceSolutionKey", Map.of("type", "hangoutsMeet"))));
        }
        return body;
    }

    private static String emailFromIdToken(String idToken) {
        if (idToken == null || idToken.split("\\.").length < 2) {
            return null;
        }
        try {
            JsonNode n = HttpJson.JSON.readTree(Base64.getUrlDecoder().decode(idToken.split("\\.")[1]));
            return n.path("email").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    @SuppressWarnings("unused")
    private static URI uri(String s) {
        return URI.create(s);
    }

    @SuppressWarnings("unused")
    private static List<String> none() {
        return List.of();
    }
}
