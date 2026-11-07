package br.com.erpadv;

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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 1 — fundação: clientes PF/PJ completos (contatos, origem, duplicidade, ficha, exclusão com
 * vínculos), processos avançados (partes, instância, fase, sentença/recurso, abas relacionadas,
 * relatório, duplicação, exclusão após arquivamento), dashboard com filtro por advogado e exportação,
 * e pesquisa global — contra o Firebase Emulator Suite.
 */
@EnabledIfEnvironmentVariable(named = "FIREBASE_AUTH_EMULATOR_HOST", matches = ".+")
@SpringBootTest(properties = {
        "app.firebase.emulator=true",
        "app.firebase.project-id=demo-erpadv",
        "app.security.require-email-verified=false",
        "app.security.data-encryption-key=ZGV2LW9ubHkta2V5LWNoYW5nZS1tZS0zMi1ieXRlcyE=",
        "app.security.search-hmac-key=ZGV2LW9ubHktaG1hYy1rZXktY2hhbmdlLW1lLTMyYnl0ZXM=",
        "app.jobs.enabled=false",
        "app.security.profile-cache-seconds=1"
})
@AutoConfigureMockMvc
class FoundationEndToEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String PASSWORD = "Senha-Forte-123";

    @Autowired
    MockMvc mvc;

    private String token;

    @Test
    void fundacaoClientesProcessosDashboardEPesquisa() throws Exception {
        String email = "fundacao-" + UUID.randomUUID() + "@escritorio-f.test";
        token = authCall("accounts:signUp", email);
        mvc.perform(json(post("/api/onboarding/office"), Map.of("name", "Escritório F", "responsibleName", "Dra. Fernanda Fundação",
                "oabNumber", "PE 9999"))).andExpect(status().isCreated());
        token = authCall("accounts:signInWithPassword", email);
        String ownerUid = body(mvc.perform(auth(get("/api/auth/me"))).andReturn()).get("uid").asText();

        // 1. Cliente PJ completo (inscrições, representante, contatos e origem)
        Map<String, Object> pj = new HashMap<>(Map.of("type", "PJ", "name", "Construtora Horizonte Ltda", "document", "11.222.333/0001-81",
                "phone", "(81) 3333-4444", "stateRegistration", "123.456.789", "legalRepresentative", "Carlos Horizonte",
                "legalRepresentativeRole", "Sócio-administrador", "source", "INDICACAO", "sourceDetail", "Indicado pela Dra. Ana"));
        pj.put("contacts", List.of(Map.of("name", "Paula Financeiro", "role", "Financeiro", "email", "paula@horizonte.test")));
        pj.put("responsibleUid", ownerUid);
        JsonNode client = body(mvc.perform(json(post("/api/clients"), pj)).andExpect(status().isCreated()).andReturn());
        String clientId = client.get("id").asText();
        assertThat(client.get("contacts").size()).isEqualTo(1);
        assertThat(client.get("source").asText()).isEqualTo("INDICACAO");
        assertThat(client.get("lastActivityAt").isNull()).isFalse();

        mvc.perform(auth(get("/api/clients/duplicates")).param("phone", "81 3333-4444"))
                .andExpect(jsonPath("$[0].client.id").value(clientId))
                .andExpect(jsonPath("$[0].reason").value("Mesmo telefone"));
        mvc.perform(auth(get("/api/clients/duplicates")).param("name", "construtora horizonte ltda").param("excludeId", clientId))
                .andExpect(jsonPath("$.length()").value(0));

        MvcResult sheet = mvc.perform(auth(get("/api/clients/" + clientId + "/sheet"))).andExpect(status().isOk()).andReturn();
        assertThat(new String(sheet.getResponse().getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        MvcResult xlsx = mvc.perform(auth(get("/api/clients/export")).param("format", "xlsx")).andExpect(status().isOk()).andReturn();
        assertThat(xlsx.getResponse().getContentAsByteArray()[0]).isEqualTo((byte) 'P');
        mvc.perform(auth(get("/api/clients/" + clientId + "/history")))
                .andExpect(jsonPath("$.items[0].action").value("CLIENT_SHEET_EXPORTED"));

        // 2. Processo com os novos campos e movimentações estruturadas
        Map<String, Object> caseReq = new HashMap<>(Map.of("clientId", clientId, "area", "CIVEL", "status", "EM_ANDAMENTO",
                "responsibleUid", ownerUid, "court", "TJPE", "instance", "1ª instância", "judgingBody", "3ª Vara Cível do Recife",
                "activeParty", "Construtora Horizonte Ltda", "passiveParty", "Banco Exemplo S.A.", "freeJustice", true));
        caseReq.put("phase", "POSTULATORIA");
        caseReq.put("secondarySubjects", List.of("Dano moral", "Repetição de indébito"));
        caseReq.put("confidential", false);
        JsonNode legalCase = body(mvc.perform(json(post("/api/cases"), caseReq)).andExpect(status().isCreated()).andReturn());
        String caseId = legalCase.get("id").asText();
        assertThat(legalCase.get("lawyerOab").asText()).isEqualTo("PE 9999");
        assertThat(legalCase.get("responsibleHistory").size()).isEqualTo(1);
        assertThat(legalCase.get("secondarySubjects").size()).isEqualTo(2);

        String today = LocalDate.now().toString();
        mvc.perform(json(post("/api/cases/" + caseId + "/movements"), Map.of("type", "SENTENCA", "date", today,
                "title", "Sentença de procedência"))).andExpect(status().isCreated()).andExpect(jsonPath("$.important").value(true));
        mvc.perform(json(post("/api/cases/" + caseId + "/movements"), Map.of("type", "RECURSO", "date", today,
                "title", "Apelação interposta pelo réu"))).andExpect(status().isCreated());
        mvc.perform(auth(get("/api/cases/" + caseId)))
                .andExpect(jsonPath("$.sentenceDate").value(today))
                .andExpect(jsonPath("$.status").value("EM_RECURSO"))
                .andExpect(jsonPath("$.phase").value("RECURSAL"))
                .andExpect(jsonPath("$.lastMovementAt").exists());
        mvc.perform(auth(get("/api/cases/" + caseId + "/related")))
                .andExpect(jsonPath("$.deadlines").isArray())
                .andExpect(jsonPath("$.transactions").isArray())
                .andExpect(jsonPath("$.cards").isArray());
        MvcResult report = mvc.perform(auth(get("/api/cases/" + caseId + "/report"))).andExpect(status().isOk()).andReturn();
        assertThat(new String(report.getResponse().getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        mvc.perform(auth(get("/api/cases/export")).param("format", "xlsx")).andExpect(status().isOk());

        // 3. Exclusão respeita vínculos: cliente com processo não pode ser excluído
        mvc.perform(auth(delete("/api/clients/" + clientId)).param("reason", "teste"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLIENT_HAS_LINKS"));

        // 4. Duplicar → arquivar → excluir o processo duplicado
        String copyId = body(mvc.perform(auth(post("/api/cases/" + caseId + "/duplicate"))).andExpect(status().isCreated()).andReturn())
                .get("id").asText();
        mvc.perform(auth(delete("/api/cases/" + copyId)).param("reason", "teste"))
                .andExpect(status().is4xxClientError()).andExpect(jsonPath("$.code").value("CASE_NOT_ARCHIVED"));
        mvc.perform(auth(post("/api/cases/" + copyId + "/archive"))).andExpect(status().isOk());
        mvc.perform(auth(delete("/api/cases/" + copyId)).param("reason", "cadastro de teste")).andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/cases/" + copyId))).andExpect(status().isNotFound());

        // 5. Cliente sem vínculos pode ser excluído
        String loneId = body(mvc.perform(json(post("/api/clients"), Map.of("type", "PF", "name", "Cadastro Duplicado",
                "document", "529.982.247-25"))).andExpect(status().isCreated()).andReturn()).get("id").asText();
        mvc.perform(auth(delete("/api/clients/" + loneId)).param("reason", "duplicado")).andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/clients/" + loneId))).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/clients/stale")).param("days", "90")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        // 6. Dashboard: filtro por advogado, novos indicadores e exportação
        mvc.perform(auth(get("/api/dashboard")).param("lawyerUid", ownerUid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lawyerUid").value(ownerUid))
                .andExpect(jsonPath("$.cases.total").value(1))
                .andExpect(jsonPath("$.casesByCourt[0].label").value("TJPE"))
                .andExpect(jsonPath("$.clientsBySource[0].label").value("Indicação"))
                .andExpect(jsonPath("$.team.length()").value(1))
                .andExpect(jsonPath("$.deadlineWindows.next7Days").value(0))
                .andExpect(jsonPath("$.previous.newCases").value(0));
        MvcResult dash = mvc.perform(auth(get("/api/dashboard/export")).param("format", "pdf")).andExpect(status().isOk()).andReturn();
        assertThat(new String(dash.getResponse().getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        // 7. Pesquisa global agrupada
        JsonNode groups = body(mvc.perform(auth(get("/api/search")).param("q", "horizonte")).andExpect(status().isOk()).andReturn());
        assertThat(groups.findValues("key").stream().map(JsonNode::asText).toList()).contains("clients", "cases");
        JsonNode movementHits = body(mvc.perform(auth(get("/api/search")).param("q", "apelação")).andReturn());
        assertThat(movementHits.findValues("key").stream().map(JsonNode::asText).toList()).contains("movements");
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        return auth(builder).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String authCall(String operation, String email) throws Exception {
        String host = System.getenv("FIREBASE_AUTH_EMULATOR_HOST");
        String payload = JSON.writeValueAsString(Map.of("email", email, "password", PASSWORD, "returnSecureToken", true));
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
