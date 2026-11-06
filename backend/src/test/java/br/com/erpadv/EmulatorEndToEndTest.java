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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fluxo completo contra o Firebase Emulator Suite (Auth + Firestore reais, tokens reais):
 * cadastro → criação do escritório → clientes → processos → isolamento → dashboard → auditoria.
 * Executado por: {@code npm run test:backend-e2e} na pasta firebase/ (sobe os emuladores).
 */
@EnabledIfEnvironmentVariable(named = "FIREBASE_AUTH_EMULATOR_HOST", matches = ".+")
@SpringBootTest(properties = {
        "app.firebase.emulator=true",
        "app.firebase.project-id=demo-erpadv",
        "app.security.require-email-verified=false",
        "app.security.data-encryption-key=ZGV2LW9ubHkta2V5LWNoYW5nZS1tZS0zMi1ieXRlcyE=",
        "app.security.search-hmac-key=ZGV2LW9ubHktaG1hYy1rZXktY2hhbmdlLW1lLTMyYnl0ZXM=",
        "app.security.profile-cache-seconds=1"
})
@AutoConfigureMockMvc
class EmulatorEndToEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String PASSWORD = "Senha-Forte-123";

    @Autowired
    MockMvc mvc;

    @Test
    void fluxoCompletoComIsolamentoEntreEscritorios() throws Exception {
        String emailA = "socio-" + UUID.randomUUID() + "@escritorio-a.test";
        String emailB = "socio-" + UUID.randomUUID() + "@escritorio-b.test";

        // 1. Cadastro no Firebase Auth e primeiro acesso
        String tokenA = authCall("accounts:signUp", emailA);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingRequired").value(true));

        mvc.perform(post("/api/onboarding/office").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Escritório A\",\"responsibleName\":\"Dra. Ana Sócia\",\"oabNumber\":\"SP 123456\"}"))
                .andExpect(status().isCreated());

        // 2. Novo token com os claims officeId/role
        tokenA = authCall("accounts:signInWithPassword", emailA);
        JsonNode me = body(mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk()).andReturn());
        assertThat(me.get("role").asText()).isEqualTo("SOCIO_ADMIN");
        String uidA = me.get("uid").asText();

        // 3. Cliente
        JsonNode client = body(mvc.perform(post("/api/clients").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"PF\",\"name\":\"João da Conceição\",\"document\":\"529.982.247-25\","
                                + "\"phone\":\"(11) 98888-7777\",\"email\":\"joao@cliente.test\"}"))
                .andExpect(status().isCreated()).andReturn());
        String clientId = client.get("id").asText();
        assertThat(client.get("document").asText()).isEqualTo("529.982.247-25");

        mvc.perform(post("/api/clients").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"PF\",\"name\":\"Outro\",\"document\":\"52998224725\"}"))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/clients").param("search", "conceicao").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/clients").param("search", "529.982.247-25").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(clientId));
        mvc.perform(get("/api/clients").param("search", "98888-7777").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));

        // 4. Processo + movimentação
        JsonNode legalCase = body(mvc.perform(post("/api/cases").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "cnjNumber", "1234567-13.2024.8.26.0100",
                                "clientId", clientId,
                                "opposingParty", "Banco Exemplo S.A.",
                                "court", "TJSP",
                                "area", "CONSUMIDOR",
                                "responsibleUid", uidA,
                                "claimValueCents", 1500000,
                                "status", "EM_ANDAMENTO"))))
                .andExpect(status().isCreated()).andReturn());
        String caseId = legalCase.get("id").asText();
        assertThat(legalCase.get("clientName").asText()).isEqualTo("João da Conceição");

        mvc.perform(post("/api/cases/" + caseId + "/movements").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"DECISAO\",\"date\":\"2024-05-10\",\"title\":\"Tutela deferida\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/cases/" + caseId + "/movements").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(get("/api/cases").param("search", "12345671320248260100").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(caseId));

        // 5. Escritório B não enxerga nada do escritório A
        String tokenB = authCall("accounts:signUp", emailB);
        mvc.perform(post("/api/onboarding/office").header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Escritório B\",\"responsibleName\":\"Dr. Bruno\"}"))
                .andExpect(status().isCreated());
        tokenB = authCall("accounts:signInWithPassword", emailB);
        mvc.perform(get("/api/clients/" + clientId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/cases/" + caseId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/clients").param("search", "conceicao").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));

        // 6. Dashboard e auditoria
        mvc.perform(get("/api/dashboard").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clients.total").value(1))
                .andExpect(jsonPath("$.cases.inProgress").value(1));
        mvc.perform(get("/api/audit/verify").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
        mvc.perform(get("/api/audit").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].hash").isNotEmpty());

        // 7. Convite de funcionário (estagiário) e bloqueio
        JsonNode invite = body(mvc.perform(post("/api/employees").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"estagiario-" + UUID.randomUUID() + "@escritorio-a.test\","
                                + "\"displayName\":\"Estagiário Teste\",\"role\":\"ESTAGIARIO\"}"))
                .andExpect(status().isCreated()).andReturn());
        assertThat(invite.get("passwordSetupLink").asText()).contains("oobCode");
        String internUid = invite.get("employee").get("uid").asText();
        mvc.perform(post("/api/employees/" + internUid + "/block").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"teste\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"));
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    /** Chama a API REST do Auth Emulator (signUp / signInWithPassword) e devolve o ID token. */
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
