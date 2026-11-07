package br.com.erpadv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.firestore.Firestore;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Painel master → escritório com administrador → cargo personalizado → integrante com senha →
 * quadros (WIP, automação de conclusão, menções, indicadores) → CRM (funil e conversão em cliente)
 * → limite de usuários → suspensão do escritório. Contra o Firebase Emulator Suite.
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
        "app.platform.master-emails=master@plataforma.test"
})
@AutoConfigureMockMvc
class PlatformBoardsCrmEndToEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    MockMvc mvc;

    @Autowired
    Firestore firestore;

    @Test
    void painelMasterCargosQuadrosECrm() throws Exception {
        // 1. Desenvolvedor da plataforma (e-mail configurado em PLATFORM_MASTER_EMAILS)
        String master = signUpOrIn("master@plataforma.test", "Master-Senha-2026");
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + master))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platformAdmin").value(true))
                .andExpect(jsonPath("$.role").value("MASTER"))
                .andExpect(jsonPath("$.onboardingRequired").value(false));

        // 2. Cria escritório com o advogado administrador (OAB, nome, CPF e senha)
        String adminEmail = "dono-" + UUID.randomUUID() + "@escritorio-c.test";
        Map<String, Object> admin = Map.of("fullName", "Dr. Carlos Dono", "cpf", "529.982.247-25",
                "oabNumber", "SP 654321", "email", adminEmail, "password", "Admin-Senha-2026");
        Map<String, Object> officeReq = new HashMap<>(Map.of("name", "Escritório C Advocacia", "plan", "PROFISSIONAL",
                "state", "SP", "city", "São Paulo", "admin", admin));
        JsonNode created = body(mvc.perform(post("/api/platform/offices").header("Authorization", "Bearer " + master).contentType(MediaType.APPLICATION_JSON).content(toJson(officeReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.admin.cpfMasked").value("***.***.247-25"))
                .andExpect(jsonPath("$.admin.owner").value(true))
                .andReturn());
        String officeId = created.get("office").get("id").asText();
        mvc.perform(post("/api/platform/offices").header("Authorization", "Bearer " + master).contentType(MediaType.APPLICATION_JSON).content(toJson(officeReq)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_IN_USE"));

        // 3. O administrador entra com a senha definida pelo master
        String owner = signIn(adminEmail, "Admin-Senha-2026");
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$.officeId").value(officeId))
                .andExpect(jsonPath("$.role").value("SOCIO_ADMIN"))
                .andExpect(jsonPath("$.platformAdmin").value(false));
        mvc.perform(get("/api/platform/offices").header("Authorization", "Bearer " + owner)).andExpect(status().isForbidden());

        // 4. Cargo personalizado "Advogado júnior"
        JsonNode role = body(mvc.perform(post("/api/roles").header("Authorization", "Bearer " + owner).contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of(
                        "name", "Advogado júnior", "baseRole", "ADVOGADO_ASSOCIADO", "color", "#2f5d9e",
                        "permissions", List.of("DASHBOARD_VIEW", "CLIENTS_READ", "BOARDS_USE", "CRM_USE", "PLATFORM_ADMIN")))))
                .andExpect(status().isCreated()).andReturn());
        assertThat(role.get("permissions").toString()).doesNotContain("PLATFORM_ADMIN");
        String roleId = role.get("id").asText();
        mvc.perform(post("/api/roles").header("Authorization", "Bearer " + owner).contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("name", "advogado JÚNIOR", "baseRole", "ESTAGIARIO",
                "permissions", List.of("CLIENTS_READ"))))).andExpect(status().isConflict());

        // 5. Integrante com cargo e senha inicial (sem link)
        String juniorEmail = "junior-" + UUID.randomUUID() + "@escritorio-c.test";
        JsonNode invite = body(mvc.perform(post("/api/employees").header("Authorization", "Bearer " + owner).contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of(
                        "email", juniorEmail, "displayName", "Júlia Júnior", "customRoleId", roleId,
                        "password", "Junior-Senha-2026", "oabNumber", "SP 111222"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.employee.customRoleName").value("Advogado júnior"))
                .andExpect(jsonPath("$.employee.role").value("ADVOGADO_ASSOCIADO"))
                .andReturn());
        assertThat(invite.get("passwordSetupLink")).isNull();
        String juniorUid = invite.get("employee").get("uid").asText();
        String junior = signIn(juniorEmail, "Junior-Senha-2026");
        JsonNode me = body(mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + junior)).andReturn());
        assertThat(me.get("permissions").toString()).contains("CRM_USE", "BOARDS_USE").doesNotContain("CLIENTS_WRITE");
        assertThat(me.get("customRoleName").asText()).isEqualTo("Advogado júnior");
        mvc.perform(post("/api/clients").header("Authorization", "Bearer " + junior).contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("type", "PF", "name", "X", "document", "52998224725"))))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/roles/" + roleId).header("Authorization", "Bearer " + owner))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROLE_IN_USE"));

        // 6. Quadro: WIP, automação de conclusão, menções e indicadores
        JsonNode board = body(mvc.perform(post("/api/boards").header("Authorization", "Bearer " + owner).contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of(
                        "name", "Peças da semana", "visibility", "OFFICE", "templateKey", "simples"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.board.columns.length()").value(3))
                .andExpect(jsonPath("$.board.columns[2].done").value(true))
                .andReturn()).get("board");
        String boardId = board.get("id").asText();
        List<Map<String, Object>> columns = new ArrayList<>();
        for (JsonNode c : board.get("columns")) {
            columns.add(new HashMap<>(Map.of("id", c.get("id").asText(), "name", c.get("name").asText(),
                    "wipLimit", c.get("name").asText().equals("Fazendo") ? 1 : 0, "done", c.get("done").asBoolean(),
                    "notifyAssignees", true)));
        }
        mvc.perform(put("/api/boards/" + boardId + "/columns").header("Authorization", "Bearer " + owner).contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("columns", columns))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.board.columns[1].wipLimit").value(1));
        String doing = board.get("columns").get(1).get("id").asText();
        String done = board.get("columns").get(2).get("id").asText();

        String card1 = body(mvc.perform(post("/api/boards/" + boardId + "/cards").header("Authorization", "Bearer " + owner).contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of(
                        "title", "Contestação — Silva", "priority", "ALTA", "assigneeUids", List.of(juniorUid),
                        "checklist", List.of(Map.of("text", "Procuração", "done", false))))))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        String card2 = body(mvc.perform(post("/api/boards/" + boardId + "/cards").header("Authorization", "Bearer " + owner).contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of(
                        "title", "Réplica — Souza", "priority", "MEDIA", "assigneeUids", List.of(juniorUid)))))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        mvc.perform(post("/api/boards/cards/" + card1 + "/move").header("Authorization", "Bearer " + junior).contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("columnId", doing))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/boards/cards/" + card2 + "/move").header("Authorization", "Bearer " + junior).contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("columnId", doing))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WIP_LIMIT"));
        mvc.perform(post("/api/boards/cards/" + card1 + "/move").header("Authorization", "Bearer " + junior).contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("columnId", done))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true));
        mvc.perform(post("/api/boards/cards/" + card2 + "/comments").header("Authorization", "Bearer " + owner).contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of(
                        "text", "@Júlia pode revisar hoje?", "mentionUids", List.of(juniorUid)))))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/boards/cards/" + card2).header("Authorization", "Bearer " + junior))
                .andExpect(jsonPath("$.card.commentCount").value(1))
                .andExpect(jsonPath("$.activity[0].kind").value("COMMENT"));
        mvc.perform(get("/api/boards/my-cards").header("Authorization", "Bearer " + junior))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].card.id").value(card2));
        mvc.perform(get("/api/boards/" + boardId + "/insights").header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$.completed").value(1))
                .andExpect(jsonPath("$.open").value(1))
                .andExpect(jsonPath("$.completed7d").value(1));
        long mentions = firestore.collection("offices").document(officeId).collection("notifications")
                .whereEqualTo("recipientUid", juniorUid).whereEqualTo("type", "BOARD_MENTION").get().get().size();
        assertThat(mentions).isEqualTo(1);

        // 7. CRM: funil, motivo de perda obrigatório, conversão em cliente
        String leadId = body(mvc.perform(post("/api/crm/leads").header("Authorization", "Bearer " + junior).contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of(
                        "name", "Carlos Prospect", "type", "PF", "source", "INDICACAO", "legalArea", "TRABALHISTA",
                        "estimatedValueCents", 500000, "phone", "(11) 98888-7777", "nextAction", "Ligar",
                        "nextActionDate", java.time.LocalDate.now().plusDays(1).toString()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerUid").value(juniorUid))
                .andExpect(jsonPath("$.stage").value("NOVO"))
                .andReturn()).get("id").asText();
        mvc.perform(post("/api/crm/leads/" + leadId + "/stage").header("Authorization", "Bearer " + junior).contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("stage", "PROPOSTA"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.probability").value(60));
        mvc.perform(post("/api/crm/leads/" + leadId + "/stage").header("Authorization", "Bearer " + junior).contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("stage", "PERDIDO"))))
                .andExpect(status().is4xxClientError()).andExpect(jsonPath("$.code").value("LOST_REASON_REQUIRED"));
        mvc.perform(post("/api/crm/leads/" + leadId + "/activities").header("Authorization", "Bearer " + junior).contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of(
                "type", "LIGACAO", "text", "Cliente pediu proposta revisada")))).andExpect(status().isCreated());
        mvc.perform(get("/api/crm/leads").param("search", "8888").header("Authorization", "Bearer " + junior))
                .andExpect(jsonPath("$.length()").value(1));
        Map<String, Object> client = Map.of("client", Map.of("type", "PF", "name", "Carlos Prospect",
                "document", "52998224725", "phone", "(11) 98888-7777"));
        mvc.perform(post("/api/crm/leads/" + leadId + "/convert").header("Authorization", "Bearer " + junior).contentType(MediaType.APPLICATION_JSON).content(toJson(client)))
                .andExpect(status().isForbidden());
        JsonNode converted = body(mvc.perform(post("/api/crm/leads/" + leadId + "/convert").header("Authorization", "Bearer " + owner).contentType(MediaType.APPLICATION_JSON).content(toJson(client)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lead.stage").value("GANHO"))
                .andReturn());
        mvc.perform(get("/api/clients/" + converted.get("clientId").asText()).header("Authorization", "Bearer " + owner)).andExpect(status().isOk());
        mvc.perform(get("/api/crm/metrics").header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$.wonCount").value(1))
                .andExpect(jsonPath("$.conversionRate").value(100.0));

        // 8. Painel master: detalhe, limite de usuários e suspensão
        mvc.perform(get("/api/platform/offices/" + officeId).header("Authorization", "Bearer " + master))
                .andExpect(jsonPath("$.users.length()").value(2))
                .andExpect(jsonPath("$.boards").value(1))
                .andExpect(jsonPath("$.leads").value(1))
                .andExpect(jsonPath("$.clients").value(1));
        mvc.perform(put("/api/platform/offices/" + officeId).header("Authorization", "Bearer " + master).contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of(
                        "name", "Escritório C Advocacia", "plan", "ESSENCIAL", "maxUsers", 2))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.seats").value(2));
        mvc.perform(post("/api/employees").header("Authorization", "Bearer " + owner).contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("email", "extra-" + UUID.randomUUID() + "@c.test",
                        "displayName", "Extra", "role", "SECRETARIA"))))
                .andExpect(status().is4xxClientError()).andExpect(jsonPath("$.code").value("SEAT_LIMIT"));

        mvc.perform(post("/api/platform/offices/" + officeId + "/suspend").header("Authorization", "Bearer " + master).contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("reason", "Inadimplência"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUSPENDED"));
        mvc.perform(get("/api/clients").header("Authorization", "Bearer " + owner))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OFFICE_SUSPENDED"));
        mvc.perform(post("/api/platform/offices/" + officeId + "/reactivate").header("Authorization", "Bearer " + master)).andExpect(status().isOk());
        mvc.perform(get("/api/clients").header("Authorization", "Bearer " + owner)).andExpect(status().isOk());

        // 9. Master redefine a senha do administrador (sessões anteriores encerradas)
        mvc.perform(post("/api/platform/offices/" + officeId + "/users/" + created.get("admin").get("uid").asText()
                + "/password").header("Authorization", "Bearer " + master).contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("password", "Nova-Senha-2027")))).andExpect(status().isNoContent());
        signIn(adminEmail, "Nova-Senha-2027");
    }

    // ------------------------------------------------------------------ helpers

    private static String toJson(Object value) throws Exception {
        return JSON.writeValueAsString(value);
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static String signIn(String email, String password) throws Exception {
        HttpResponse<String> r = authCall("accounts:signInWithPassword", email, password);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body()).get("idToken").asText();
    }

    private static String signUpOrIn(String email, String password) throws Exception {
        HttpResponse<String> r = authCall("accounts:signUp", email, password);
        return r.statusCode() == 200 ? JSON.readTree(r.body()).get("idToken").asText() : signIn(email, password);
    }

    private static HttpResponse<String> authCall(String operation, String email, String password) throws Exception {
        String host = System.getenv("FIREBASE_AUTH_EMULATOR_HOST");
        String payload = JSON.writeValueAsString(Map.of("email", email, "password", password, "returnSecureToken", true));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + "/identitytoolkit.googleapis.com/v1/" + operation + "?key=fake-api-key"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
