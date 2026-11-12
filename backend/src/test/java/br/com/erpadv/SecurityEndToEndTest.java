package br.com.erpadv;

import br.com.erpadv.services.security.Totp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 5 — auditoria avançada (antes/depois, justificativa, filtros, exportação), segurança (2FA com TOTP e códigos de
 * recuperação, sessões com encerramento remoto, política do escritório, eventos) e backup com recuperação não destrutiva.
 */
@EnabledIfEnvironmentVariable(named = "FIREBASE_AUTH_EMULATOR_HOST", matches = ".+")
@SpringBootTest(properties = {
        "app.firebase.emulator=true",
        "app.firebase.project-id=demo-erpadv",
        "app.security.require-email-verified=false",
        "app.security.data-encryption-key=ZGV2LW9ubHkta2V5LWNoYW5nZS1tZS0zMi1ieXRlcyE=",
        "app.security.search-hmac-key=ZGV2LW9ubHktaG1hYy1rZXktY2hhbmdlLW1lLTMyYnl0ZXM=",
        "app.jobs.enabled=false",
        "app.security.profile-cache-seconds=1",
        "app.security.rate-limit-per-minute=5000"
})
@AutoConfigureMockMvc
class SecurityEndToEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String PASSWORD = "Senha-Forte-123";

    @Autowired
    MockMvc mvc;

    @Test
    void auditoriaSegurancaEBackup() throws Exception {
        String email = "seg-" + UUID.randomUUID() + "@escritorio-s.test";
        String t1 = authCall("accounts:signUp", email, PASSWORD);
        mvc.perform(json(post("/api/onboarding/office"), t1, Map.of("name", "Escritório Seguro", "responsibleName", "Dra. Sofia Segura",
                "oabNumber", "PE 7777"))).andExpect(status().isCreated());
        t1 = authCall("accounts:signInWithPassword", email, PASSWORD);
        String ownerUid = body(mvc.perform(auth(get("/api/auth/me"), t1)).andReturn()).get("uid").asText();
        mvc.perform(auth(get("/api/security/session"), t1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaEnabled").value(false)).andExpect(jsonPath("$.mfaRequired").value(false));

        // ------------------------------------------------------------------ 1. Auditoria com antes/depois
        Map<String, Object> client = new HashMap<>(Map.of("type", "PF", "name", "Bruno Auditado", "document", "529.982.247-25",
                "email", "bruno@antes.test"));
        String clientId = body(mvc.perform(json(post("/api/clients"), t1, client)).andExpect(status().isCreated()).andReturn()).get("id").asText();
        client.put("email", "bruno@depois.test");
        client.remove("document");
        mvc.perform(json(put("/api/clients/" + clientId), t1, client)).andExpect(status().isOk());
        String today = LocalDate.now().toString();
        JsonNode logs = body(mvc.perform(auth(get("/api/audit"), t1).param("module", "Clientes").param("action", "client_updated")
                .param("from", today).param("to", today)).andExpect(status().isOk()).andReturn()).get("items");
        assertThat(logs).hasSize(1);
        JsonNode upd = logs.get(0);
        assertThat(upd.get("actorName").asText()).isEqualTo("Dra. Sofia Segura");
        assertThat(upd.get("origin").asText()).isEqualTo("API");
        assertThat(upd.get("changes").toString()).contains("\"field\":\"email\"").contains("bruno@antes.test").contains("bruno@depois.test");
        mvc.perform(auth(get("/api/audit"), t1).param("from", LocalDate.now().plusDays(2).toString())).andExpect(jsonPath("$.items.length()").value(0));
        MvcResult pdf = mvc.perform(auth(get("/api/audit/export"), t1).param("module", "Clientes")).andExpect(status().isOk()).andReturn();
        assertThat(new String(pdf.getResponse().getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        MvcResult xlsx = mvc.perform(auth(get("/api/audit/export"), t1).param("format", "xlsx")).andExpect(status().isOk()).andReturn();
        assertThat(new String(xlsx.getResponse().getContentAsByteArray(), 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");
        mvc.perform(auth(get("/api/audit/verify"), t1)).andExpect(jsonPath("$.valid").value(true));

        // ------------------------------------------------------------------ 2. Política: justificativa e 2FA
        Map<String, Object> policy = new HashMap<>(Map.of("requireJustificationOnDelete", true, "mfaRequirement", "EQUIPE",
                "sessionMaxHours", 0, "idleMinutes", 0, "newDeviceAlert", true, "backupEnabled", true, "backupRetentionDays", 30));
        mvc.perform(json(put("/api/security/policy"), t1, policy)).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("MFA_SELF_FIRST"));
        policy.put("mfaRequirement", "NENHUM");
        policy.put("idleMinutes", 2);
        mvc.perform(json(put("/api/security/policy"), t1, policy)).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("INVALID_POLICY"));
        policy.put("idleMinutes", 0);
        mvc.perform(json(put("/api/security/policy"), t1, policy)).andExpect(status().isOk())
                .andExpect(jsonPath("$.requireJustificationOnDelete").value(true));

        // ------------------------------------------------------------------ 3. Backup antes da exclusão
        String doomed = body(mvc.perform(json(post("/api/clients"), t1, Map.of("type", "PF", "name", "Carla Excluída",
                "document", "111.444.777-35"))).andExpect(status().isCreated()).andReturn()).get("id").asText();
        JsonNode backup = body(mvc.perform(auth(post("/api/security/backups"), t1)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONCLUIDO")).andReturn());
        String backupId = backup.get("id").asText();
        assertThat(backup.get("collections").get("clients").asLong()).isEqualTo(2);
        assertThat(backup.get("collections").get("@users").asLong()).isEqualTo(1);
        assertThat(backup.get("collections").get("audit_logs").asLong()).isGreaterThan(3);
        assertThat(backup.get("sha256").asText()).hasSize(64);

        // ------------------------------------------------------------------ 4. Exclusão exige justificativa
        mvc.perform(auth(delete("/api/clients/" + doomed), t1)).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("JUSTIFICATION_REQUIRED"));
        mvc.perform(auth(delete("/api/clients/" + doomed), t1).header("X-Audit-Justification",
                URLEncoder.encode("Cadastro em duplicidade — pedido da sócia", StandardCharsets.UTF_8))).andExpect(status().isNoContent());
        JsonNode del = body(mvc.perform(auth(get("/api/audit"), t1).param("entityId", doomed)).andReturn()).get("items").get(0);
        assertThat(del.get("action").asText()).isEqualTo("CLIENT_DELETED");
        assertThat(del.get("justification").asText()).isEqualTo("Cadastro em duplicidade — pedido da sócia");
        mvc.perform(auth(get("/api/clients/" + doomed), t1)).andExpect(status().isNotFound());

        // ------------------------------------------------------------------ 5. Recuperação não destrutiva
        JsonNode inspection = body(mvc.perform(auth(get("/api/security/backups/" + backupId + "/inspect"), t1)).andExpect(status().isOk()).andReturn());
        JsonNode clientsDiff = null;
        for (JsonNode c : inspection.get("collections")) {
            if (c.get("collection").asText().equals("clients")) {
                clientsDiff = c;
            }
            if (c.get("collection").asText().equals("audit_logs")) {
                assertThat(c.get("restorable").asBoolean()).isFalse();
            }
        }
        assertThat(clientsDiff).isNotNull();
        assertThat(clientsDiff.get("inBackup").asLong()).isEqualTo(2);
        assertThat(clientsDiff.get("missingNow").asLong()).isEqualTo(1);
        mvc.perform(json(post("/api/security/backups/" + backupId + "/restore"), t1, Map.of("collections", List.of("audit_logs"))))
                .andExpect(status().is4xxClientError()).andExpect(jsonPath("$.code").value("NOT_RESTORABLE"));
        mvc.perform(json(post("/api/security/backups/" + backupId + "/restore"), t1, Map.of("collections", List.of("clients"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(auth(get("/api/clients/" + doomed), t1)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Carla Excluída"));
        // o cliente editado depois do backup não foi sobrescrito
        mvc.perform(auth(get("/api/clients/" + clientId), t1)).andExpect(jsonPath("$.email").value("bruno@depois.test"));
        mvc.perform(json(post("/api/security/backups/" + backupId + "/restore"), t1, Map.of("collections", List.of("clients"))))
                .andExpect(jsonPath("$.total").value(0));
        MvcResult file = mvc.perform(auth(get("/api/security/backups/" + backupId + "/download"), t1)).andExpect(status().isOk()).andReturn();
        String content = new String(new GZIPInputStream(new ByteArrayInputStream(file.getResponse().getContentAsByteArray())).readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(content).startsWith("{\"format\":\"erpadv-backup\"").contains("Carla Excluída").doesNotContain("529.982.247-25");
        mvc.perform(auth(get("/api/security/backups"), t1)).andExpect(jsonPath("$.length()").value(1));

        // ------------------------------------------------------------------ 6. Ativar o 2FA
        JsonNode setup = body(mvc.perform(auth(post("/api/security/mfa/setup"), t1)).andExpect(status().isOk()).andReturn());
        String secret = setup.get("secret").asText().replace(" ", "");
        assertThat(setup.get("otpauthUri").asText()).startsWith("otpauth://totp/ERPAdv:");
        assertThat(setup.get("qrSvg").asText()).startsWith("<svg");
        mvc.perform(json(post("/api/security/mfa/confirm"), t1, Map.of("code", "000000"))).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("MFA_INVALID_CODE"));
        long step = Instant.now().getEpochSecond() / Totp.PERIOD;
        JsonNode enabled = body(mvc.perform(json(post("/api/security/mfa/confirm"), t1, Map.of("code", Totp.code(Totp.decode(secret), step))))
                .andExpect(status().isOk()).andReturn());
        assertThat(enabled.get("recoveryCodes")).hasSize(8);
        String recovery = enabled.get("recoveryCodes").get(0).asText();
        mvc.perform(auth(get("/api/security/session"), t1)).andExpect(jsonPath("$.mfaEnabled").value(true)).andExpect(jsonPath("$.mfaVerified").value(true));
        mvc.perform(auth(get("/api/clients"), t1)).andExpect(status().isOk());

        // ------------------------------------------------------------------ 7. Novo login exige o segundo fator
        Thread.sleep(1100);
        String t2 = authCall("accounts:signInWithPassword", email, PASSWORD);
        mvc.perform(auth(get("/api/clients"), t2)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
        mvc.perform(auth(get("/api/auth/me"), t2)).andExpect(status().isOk());
        mvc.perform(auth(get("/api/security/session"), t2)).andExpect(jsonPath("$.mfaVerified").value(false));
        mvc.perform(json(post("/api/security/mfa/verify"), t2, Map.of("code", "123456"))).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("MFA_INVALID_CODE"));
        // o código do passo já usado na ativação não vale de novo
        mvc.perform(json(post("/api/security/mfa/verify"), t2, Map.of("code", Totp.code(Totp.decode(secret), step))))
                .andExpect(status().is4xxClientError());
        mvc.perform(json(post("/api/security/mfa/verify"), t2, Map.of("code", Totp.code(Totp.decode(secret), step + 1))))
                .andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/clients"), t2)).andExpect(status().isOk());

        // ------------------------------------------------------------------ 8. Sessões: listar e encerrar remotamente
        JsonNode mine = body(mvc.perform(auth(get("/api/security/sessions/mine"), t2)).andExpect(status().isOk()).andReturn());
        assertThat(mine.size()).isGreaterThanOrEqualTo(2);
        String other = null;
        for (JsonNode s : mine) {
            if (!s.get("current").asBoolean()) {
                other = s.get("id").asText();
            }
        }
        assertThat(other).isNotNull();
        mvc.perform(auth(delete("/api/security/sessions/mine/" + other), t2)).andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/clients"), t1)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
        mvc.perform(auth(get("/api/clients"), t2)).andExpect(status().isOk());

        // ------------------------------------------------------------------ 9. Código de recuperação (uso único)
        Thread.sleep(1100);
        String t3 = authCall("accounts:signInWithPassword", email, PASSWORD);
        mvc.perform(json(post("/api/security/mfa/verify"), t3, Map.of("code", recovery))).andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/clients"), t3)).andExpect(status().isOk());
        Thread.sleep(1100);
        String t4 = authCall("accounts:signInWithPassword", email, PASSWORD);
        mvc.perform(json(post("/api/security/mfa/verify"), t4, Map.of("code", recovery))).andExpect(status().is4xxClientError());

        // ------------------------------------------------------------------ 10. Política exige 2FA da equipe
        policy.put("mfaRequirement", "EQUIPE");
        mvc.perform(json(put("/api/security/policy"), t3, policy)).andExpect(status().isOk());
        mvc.perform(json(post("/api/security/mfa/disable"), t3, Map.of("code", "000000"))).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("MFA_REQUIRED_BY_POLICY"));
        String memberEmail = "membro-" + UUID.randomUUID() + "@escritorio-s.test";
        String memberUid = body(mvc.perform(json(post("/api/employees"), t3, Map.of("email", memberEmail, "displayName", "Marcos Membro",
                "role", "SECRETARIA", "password", "Senha-Membro-123"))).andExpect(status().isCreated()).andReturn()).get("employee").get("uid").asText();
        String tm = authCall("accounts:signInWithPassword", memberEmail, "Senha-Membro-123");
        mvc.perform(auth(get("/api/clients"), tm)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MFA_ENROLLMENT_REQUIRED"));
        mvc.perform(auth(get("/api/security/session"), tm)).andExpect(jsonPath("$.enrollmentRequired").value(true));
        JsonNode mSetup = body(mvc.perform(auth(post("/api/security/mfa/setup"), tm)).andExpect(status().isOk()).andReturn());
        String mSecret = mSetup.get("secret").asText().replace(" ", "");
        mvc.perform(json(post("/api/security/mfa/confirm"), tm, Map.of("code",
                Totp.code(Totp.decode(mSecret), Instant.now().getEpochSecond() / Totp.PERIOD)))).andExpect(status().isOk());
        mvc.perform(auth(get("/api/clients"), tm)).andExpect(status().isOk());
        // a secretária não administra a segurança do escritório
        mvc.perform(json(put("/api/security/policy"), tm, policy)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/security/sessions"), tm)).andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/security/backups"), tm)).andExpect(status().isForbidden());

        JsonNode team = body(mvc.perform(auth(get("/api/security/users/mfa"), t3)).andExpect(status().isOk()).andReturn());
        assertThat(team.toString()).contains("Marcos Membro").contains("\"mfaEnabled\":true");
        JsonNode office = body(mvc.perform(auth(get("/api/security/sessions"), t3)).andExpect(status().isOk()).andReturn());
        assertThat(office.findValuesAsText("uid")).contains(memberUid, ownerUid);

        // o administrador redefine o 2FA de quem perdeu o celular: sessões do integrante encerradas
        mvc.perform(auth(post("/api/security/users/" + ownerUid + "/mfa/reset"), t3)).andExpect(status().isForbidden());
        Thread.sleep(1100);
        mvc.perform(auth(post("/api/security/users/" + memberUid + "/mfa/reset"), t3)).andExpect(status().isNoContent());
        Thread.sleep(1100);
        mvc.perform(auth(get("/api/clients"), tm)).andExpect(status().isUnauthorized());

        // ------------------------------------------------------------------ 11. Eventos de segurança
        JsonNode events = body(mvc.perform(auth(get("/api/security/events"), t3)).andExpect(status().isOk()).andReturn());
        assertThat(events.findValuesAsText("type")).contains("SESSION_STARTED", "MFA_ENABLED", "MFA_FAILED", "SESSION_REVOKED",
                "MFA_RECOVERY_USED", "POLICY_CHANGED", "BACKUP_CREATED", "BACKUP_RESTORED", "MFA_RESET", "ACCESS_DENIED");
        mvc.perform(auth(get("/api/security/events"), t3).param("severity", "CRITICO")).andExpect(jsonPath("$[0].severity").value("CRITICO"));
        mvc.perform(auth(get("/api/audit/verify"), t3)).andExpect(jsonPath("$.valid").value(true));
    }

    // ------------------------------------------------------------------ helpers

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String token, Object body) throws Exception {
        return auth(builder, token).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String authCall(String operation, String email, String password) throws Exception {
        String host = System.getenv("FIREBASE_AUTH_EMULATOR_HOST");
        String payload = JSON.writeValueAsString(Map.of("email", email, "password", password, "returnSecureToken", true));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + "/identitytoolkit.googleapis.com/v1/" + operation + "?key=fake-api-key"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).get("idToken").asText();
    }
}
