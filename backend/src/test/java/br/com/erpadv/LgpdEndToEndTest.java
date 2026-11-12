package br.com.erpadv;

import br.com.erpadv.services.lgpd.LgpdService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fase 5 — LGPD: titulares, consentimentos, registro de tratamento, incidentes, compartilhamentos, política pública, retenção e anonimização. */
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
class LgpdEndToEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    MockMvc mvc;

    private String token;

    @Test
    void lgpdCompleta() throws Exception {
        String email = "lgpd-" + UUID.randomUUID() + "@escritorio-l.test";
        token = authCall("accounts:signUp", email, "Senha-Forte-123");
        mvc.perform(json(post("/api/onboarding/office"), Map.of("name", "Escritório Privacidade", "responsibleName", "Dra. Lia Encarregada",
                "oabNumber", "PE 8888"))).andExpect(status().isCreated());
        token = authCall("accounts:signInWithPassword", email, "Senha-Forte-123");
        JsonNode me = body(mvc.perform(auth(get("/api/auth/me"))).andReturn());
        String officeId = me.get("officeId").asText();
        assertThat(me.get("permissions").toString()).contains("LGPD_MANAGE");
        String ownerUid = me.get("uid").asText();
        LocalDate today = LocalDate.now(ZoneId.of("America/Sao_Paulo"));

        JsonNode options = body(mvc.perform(auth(get("/api/lgpd/options"))).andExpect(status().isOk()).andReturn());
        assertThat(options.get("legalBases")).hasSize(10);
        assertThat(options.get("requestTypes").toString()).contains("PORTABILIDADE");

        String clientA = body(mvc.perform(json(post("/api/clients"), Map.of("type", "PF", "name", "Ana Titular", "document", "529.982.247-25",
                "email", "ana@titular.test", "phone", "(81) 98888-0000"))).andExpect(status().isCreated()).andReturn()).get("id").asText();
        String clientB = body(mvc.perform(json(post("/api/clients"), Map.of("type", "PF", "name", "Beto Processo", "document", "111.444.777-35")))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        mvc.perform(json(post("/api/cases"), Map.of("clientId", clientB, "area", "CIVEL", "status", "EM_ANDAMENTO", "responsibleUid", ownerUid,
                "court", "TJPE"))).andExpect(status().isCreated());

        // ------------------------------------------------------------------ 1. Solicitação de titular
        JsonNode req = body(mvc.perform(json(post("/api/lgpd/requests"), Map.of("type", "ACESSO", "requesterName", "Ana Titular",
                "requesterEmail", "ana@titular.test", "clientId", clientA, "channel", "E-mail", "description", "Quero saber quais dados meus vocês têm.")))
                .andExpect(status().isCreated()).andReturn());
        String reqId = req.get("id").asText();
        assertThat(req.get("protocol").asText()).startsWith("LGPD-" + today.getYear() + "-");
        assertThat(LocalDate.parse(req.get("dueDate").asText())).isEqualTo(today.plusDays(LgpdService.REQUEST_DAYS));
        assertThat(req.get("clientName").asText()).isEqualTo("Ana Titular");
        mvc.perform(json(post("/api/lgpd/requests"), Map.of("type", "ACESSO", "requesterName", "X", "receivedDate", today.plusDays(1).toString())))
                .andExpect(status().is4xxClientError()).andExpect(jsonPath("$.code").value("INVALID_DATE"));
        mvc.perform(json(put("/api/lgpd/requests/" + reqId), Map.of("status", "ATENDIDA"))).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("RESPONSE_REQUIRED"));
        mvc.perform(json(put("/api/lgpd/requests/" + reqId), Map.of("status", "ATENDIDA", "response", "Relatório enviado por e-mail.")))
                .andExpect(status().is4xxClientError()).andExpect(jsonPath("$.code").value("IDENTITY_NOT_VERIFIED"));
        MvcResult pdf = mvc.perform(auth(get("/api/lgpd/clients/" + clientA + "/export"))).andExpect(status().isOk()).andReturn();
        assertThat(new String(pdf.getResponse().getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        MvcResult xlsx = mvc.perform(auth(get("/api/lgpd/clients/" + clientA + "/export")).param("format", "xlsx")).andExpect(status().isOk()).andReturn();
        assertThat(new String(xlsx.getResponse().getContentAsByteArray(), 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");
        JsonNode done = body(mvc.perform(json(put("/api/lgpd/requests/" + reqId), Map.of("status", "ATENDIDA", "identityVerified", true,
                "response", "Relatório com todos os dados enviado ao e-mail cadastrado."))).andExpect(status().isOk()).andReturn());
        assertThat(done.get("status").asText()).isEqualTo("ATENDIDA");
        assertThat(done.get("respondedByName").asText()).isEqualTo("Dra. Lia Encarregada");
        assertThat(done.get("history").size()).isGreaterThanOrEqualTo(3);
        mvc.perform(auth(get("/api/audit")).param("action", "LGPD_DATA_EXPORTED")).andExpect(jsonPath("$.items.length()").value(2));

        // ------------------------------------------------------------------ 2. Consentimento
        String consent = body(mvc.perform(json(post("/api/lgpd/consents"), Map.of("clientId", clientA, "purpose", "Receber informativos jurídicos por WhatsApp",
                "channel", "Termo assinado", "evidence", "Termo na central documental"))).andExpect(status().isCreated()).andReturn()).get("id").asText();
        mvc.perform(json(post("/api/lgpd/consents"), Map.of("clientId", clientA, "purpose", " "))).andExpect(status().is4xxClientError());
        mvc.perform(json(post("/api/lgpd/consents/" + consent + "/revoke"), Map.of("note", "Pedido por telefone"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));
        mvc.perform(json(post("/api/lgpd/consents/" + consent + "/revoke"), Map.of())).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("ALREADY_REVOKED"));

        // ------------------------------------------------------------------ 3. Registro das operações de tratamento
        JsonNode seeded = body(mvc.perform(auth(post("/api/lgpd/activities/suggestions"))).andExpect(status().isCreated()).andReturn());
        assertThat(seeded).hasSize(6);
        assertThat(seeded.get(0).get("suggestion").asBoolean()).isTrue();
        mvc.perform(auth(post("/api/lgpd/activities/suggestions"))).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("ALREADY_HAS_ACTIVITIES"));
        Map<String, Object> act = new HashMap<>(Map.of("name", "Patrocínio de processos criminais", "purpose", "Defesa em processos criminais",
                "legalBasis", "EXERCICIO_DIREITOS", "sensitiveData", true, "dataCategories", List.of("Antecedentes", "Saúde"),
                "dataSubjects", List.of("Clientes"), "sharedWith", List.of("Tribunais"), "internationalTransfer", false, "active", true));
        mvc.perform(json(post("/api/lgpd/activities"), act)).andExpect(status().is4xxClientError()).andExpect(jsonPath("$.code").value("SENSITIVE_BASIS_REQUIRED"));
        act.put("sensitiveBasis", "Exercício regular de direitos (art. 11, II, d)");
        mvc.perform(json(post("/api/lgpd/activities"), act)).andExpect(status().isCreated()).andExpect(jsonPath("$.suggestion").value(false));
        String sugId = seeded.get(0).get("id").asText();
        Map<String, Object> rev = new HashMap<>(Map.of("name", seeded.get(0).get("name").asText(), "purpose", "Finalidade revisada pelo escritório",
                "legalBasis", "EXECUCAO_CONTRATO", "sensitiveData", false, "internationalTransfer", false, "active", true));
        mvc.perform(json(put("/api/lgpd/activities/" + sugId), rev)).andExpect(status().isOk()).andExpect(jsonPath("$.suggestion").value(false));

        // ------------------------------------------------------------------ 4. Incidente
        Instant discovered = Instant.now().minusSeconds(3600);
        JsonNode inc = body(mvc.perform(json(post("/api/lgpd/incidents"), Map.of("title", "Notebook extraviado", "description",
                "Notebook de colaborador com documentos de clientes foi furtado.", "discoveredAt", discovered.toString(), "dataCategories", List.of("Documentos"),
                "affectedSubjects", 12, "sensitiveData", false, "risk", "ALTO", "anpdNotificationRequired", false))).andExpect(status().isCreated()).andReturn());
        String incId = inc.get("id").asText();
        assertThat(inc.get("anpdNotificationRequired").asBoolean()).isTrue();
        assertThat(LocalDate.parse(inc.get("anpdDeadline").asText()))
                .isEqualTo(LgpdService.plusBusinessDays(discovered.atZone(ZoneId.of("America/Sao_Paulo")).toLocalDate(), 3));
        mvc.perform(json(put("/api/lgpd/incidents/" + incId), Map.of("status", "ENCERRADO"))).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("ANPD_PENDING"));
        mvc.perform(json(put("/api/lgpd/incidents/" + incId), Map.of("anpdNotifiedDate", today.toString(), "anpdProtocol", "ANPD-123",
                "subjectsNotifiedDate", today.toString(), "containmentMeasures", "Senhas trocadas e sessões encerradas"))).andExpect(status().isOk());
        JsonNode closed = body(mvc.perform(json(put("/api/lgpd/incidents/" + incId), Map.of("status", "ENCERRADO"))).andExpect(status().isOk()).andReturn());
        assertThat(closed.get("timeline").toString()).contains("Comunicado à ANPD").contains("ANPD-123").contains("Titulares comunicados");
        mvc.perform(auth(get("/api/security/events")).param("severity", "CRITICO")).andExpect(jsonPath("$[0].type").value("LGPD_INCIDENT"));

        // ------------------------------------------------------------------ 5. Compartilhamento
        mvc.perform(json(post("/api/lgpd/shares"), Map.of("recipient", "Perito Contábil X", "recipientType", "Perito", "clientId", clientA,
                "dataDescription", "Extratos bancários", "purpose", "Perícia contábil", "legalBasis", "EXERCICIO_DIREITOS"))).andExpect(status().isCreated());
        mvc.perform(auth(get("/api/lgpd/shares")).param("clientId", clientA)).andExpect(jsonPath("$.length()").value(1));

        // ------------------------------------------------------------------ 6. Política de privacidade pública
        mvc.perform(json(post("/api/lgpd/policies"), Map.of("title", "Política", "content", "<p>curta</p>"))).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("POLICY_TOO_SHORT"));
        JsonNode tpl = body(mvc.perform(auth(get("/api/lgpd/policies/template"))).andExpect(status().isOk()).andReturn());
        assertThat(tpl.get("content").asText()).contains("Escritório Privacidade").contains("art. 18");
        String content = tpl.get("content").asText() + "<script>alert(1)</script><p onclick=\"x()\">fim</p>";
        String pol = body(mvc.perform(json(post("/api/lgpd/policies"), Map.of("title", tpl.get("title").asText(), "content", content)))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        mvc.perform(get("/api/public/privacy/" + officeId)).andExpect(status().isNotFound());
        mvc.perform(auth(post("/api/lgpd/policies/" + pol + "/publish"))).andExpect(status().isOk()).andExpect(jsonPath("$.published").value(true));
        JsonNode pub = body(mvc.perform(get("/api/public/privacy/" + officeId)).andExpect(status().isOk()).andReturn());
        assertThat(pub.get("version").asInt()).isEqualTo(1);
        assertThat(pub.get("officeName").asText()).isEqualTo("Escritório Privacidade");
        assertThat(pub.get("content").asText()).doesNotContain("<script").doesNotContain("onclick").contains("fim");
        mvc.perform(json(put("/api/lgpd/policies/" + pol), Map.of("title", "x", "content", content))).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("POLICY_PUBLISHED"));

        // ------------------------------------------------------------------ 7. Retenção documental
        mvc.perform(json(put("/api/lgpd/retention/rules"), Map.of("category", "DOCUMENTO_PESSOAL", "years", 1, "legalReference", "Política interna")))
                .andExpect(status().isOk());
        String docId = body(mvc.perform(auth(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "rg.pdf", "application/pdf", "%PDF-1.4\n% rg\n".getBytes(StandardCharsets.US_ASCII)))
                        .file(new MockMultipartFile("meta", "", "application/json",
                                JSON.writeValueAsBytes(Map.of("title", "RG da cliente", "category", "DOCUMENTO_PESSOAL", "clientId", clientA))))))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        mvc.perform(auth(get("/api/lgpd/retention/report"))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(json(post("/api/lgpd/retention/trash"), Map.of("ids", List.of(docId)))).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("NOT_EXPIRED"));
        mvc.perform(auth(get("/api/lgpd/retention/rules"))).andExpect(jsonPath("$[0].years").value(1));

        // ------------------------------------------------------------------ 8. Anonimização
        mvc.perform(auth(post("/api/lgpd/clients/" + clientB + "/anonymize"))).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("LEGAL_RETENTION"));
        JsonNode anon = body(mvc.perform(auth(post("/api/lgpd/clients/" + clientA + "/anonymize")).param("requestId", reqId))
                .andExpect(status().isOk()).andReturn());
        String label = anon.get("label").asText();
        assertThat(label).startsWith("Titular anonimizado ");
        JsonNode a = body(mvc.perform(auth(get("/api/clients/" + clientA))).andExpect(status().isOk()).andReturn());
        assertThat(a.get("name").asText()).isEqualTo(label);
        assertThat(a.path("email").isMissingNode() || a.get("email").isNull()).isTrue();
        assertThat(a.toString()).doesNotContain("Ana Titular").doesNotContain("98888");
        mvc.perform(json(put("/api/clients/" + clientA), Map.of("type", "PF", "name", "Ana de Novo"))).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("CLIENT_ANONYMIZED"));
        mvc.perform(auth(post("/api/lgpd/clients/" + clientA + "/anonymize"))).andExpect(jsonPath("$.code").value("ALREADY_ANONYMIZED"));
        assertThat(body(mvc.perform(auth(get("/api/lgpd/consents")).param("clientId", clientA)).andReturn()).get(0).get("clientName").asText()).isEqualTo(label);
        assertThat(body(mvc.perform(auth(get("/api/lgpd/requests"))).andReturn()).toString()).doesNotContain("Ana Titular");
        // a busca não encontra mais o nome original
        mvc.perform(auth(get("/api/clients")).param("search", "Ana Titular")).andExpect(jsonPath("$.items.length()").value(0));

        // ------------------------------------------------------------------ 9. Painel e permissão
        JsonNode ov = body(mvc.perform(auth(get("/api/lgpd/overview"))).andExpect(status().isOk()).andReturn());
        assertThat(ov.get("openRequests").asLong()).isZero();
        assertThat(ov.get("activities").asLong()).isEqualTo(7);
        assertThat(ov.get("pendingSuggestions").asLong()).isEqualTo(5);
        assertThat(ov.get("publishedPolicyVersion").asInt()).isEqualTo(1);
        assertThat(ov.get("openIncidents").asLong()).isZero();
        String memberEmail = "sec-" + UUID.randomUUID() + "@escritorio-l.test";
        mvc.perform(json(post("/api/employees"), Map.of("email", memberEmail, "displayName", "Sara Secretária", "role", "SECRETARIA",
                "password", "Senha-Membro-123"))).andExpect(status().isCreated());
        String owner = token;
        token = authCall("accounts:signInWithPassword", memberEmail, "Senha-Membro-123");
        mvc.perform(auth(get("/api/lgpd/overview"))).andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/lgpd/clients/" + clientB + "/anonymize"))).andExpect(status().isForbidden());
        token = owner;
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        return auth(builder).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
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
