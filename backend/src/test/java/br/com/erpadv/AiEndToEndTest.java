package br.com.erpadv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 5 — IA Jurídica com o SDK oficial da Anthropic. Um servidor HTTP local responde no formato da API de Mensagens, para
 * conferir o pedido enviado (modelo, regras, mascaramento de CPF, busca restrita a sites oficiais) e o tratamento das respostas
 * (citações, saída estruturada, recusa). Nada aqui é exibido ao usuário como resposta real.
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
        "app.security.rate-limit-per-minute=5000",
        "app.ai.api-key=sk-ant-api03-chave-de-teste"
})
@AutoConfigureMockMvc
class AiEndToEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final List<JsonNode> REQUESTS = new CopyOnWriteArrayList<>();
    private static final List<String> API_KEYS = new CopyOnWriteArrayList<>();
    private static final List<String> BETAS = new CopyOnWriteArrayList<>();
    private static final HttpServer STUB = start();

    @Autowired
    MockMvc mvc;

    private String token;

    @DynamicPropertySource
    static void stub(DynamicPropertyRegistry r) {
        r.add("app.ai.base-url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        STUB.stop(0);
    }

    @Test
    void iaJuridica() throws Exception {
        String email = "ia-" + UUID.randomUUID() + "@escritorio-ia.test";
        token = authCall("accounts:signUp", email, "Senha-Forte-123");
        mvc.perform(json(post("/api/onboarding/office"), Map.of("name", "Escritório IA", "responsibleName", "Dr. Ivo Inteligente",
                "oabNumber", "PE 9999"))).andExpect(status().isCreated());
        token = authCall("accounts:signInWithPassword", email, "Senha-Forte-123");
        String ownerUid = body(mvc.perform(auth(get("/api/auth/me"))).andReturn()).get("uid").asText();

        // ------------------------------------------------------------------ 1. Conector desativado até o escritório decidir
        JsonNode catalog = body(mvc.perform(auth(get("/api/ai/catalog"))).andExpect(status().isOk()).andReturn());
        assertThat(catalog.get("configured").asBoolean()).isTrue();
        assertThat(catalog.get("enabled").asBoolean()).isFalse();
        assertThat(catalog.get("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(catalog.get("agents")).hasSize(6);
        assertThat(catalog.get("officialDomains").toString()).contains("jus.br").contains("gov.br");
        mvc.perform(json(post("/api/ai/ask"), Map.of("agent", "ASSISTENTE_JURIDICO", "task", "CONVERSA", "message", "Olá")))
                .andExpect(status().is4xxClientError()).andExpect(jsonPath("$.code").value("AI_NOT_CONFIGURED"));
        assertThat(REQUESTS).isEmpty();
        mvc.perform(json(put("/api/integrations/ANTHROPIC"), Map.of("enabled", true, "autoSync", false, "config", Map.of(), "secrets", Map.of())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ATIVO"));

        // ------------------------------------------------------------------ 2. Resumo do processo com CPF mascarado
        String clientId = body(mvc.perform(json(post("/api/clients"), Map.of("type", "PF", "name", "Carla Contexto", "document", "529.982.247-25")))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        String caseId = body(mvc.perform(json(post("/api/cases"), Map.of("clientId", clientId, "area", "CIVEL", "status", "EM_ANDAMENTO",
                "responsibleUid", ownerUid, "court", "TJPE", "subject", "Indenização por dano moral",
                "notes", "Cliente CPF 529.982.247-25 relata negativação indevida."))).andExpect(status().isCreated()).andReturn()).get("id").asText();
        mvc.perform(json(post("/api/ai/ask"), Map.of("agent", "ASSISTENTE_JURIDICO", "task", "RESUMO_PROCESSO", "message", "")))
                .andExpect(status().is4xxClientError()).andExpect(jsonPath("$.code").value("CASE_REQUIRED"));
        JsonNode conv = body(mvc.perform(json(post("/api/ai/ask"), Map.of("agent", "ASSISTENTE_JURIDICO", "task", "RESUMO_PROCESSO",
                "message", "Destaque os riscos.", "caseId", caseId))).andExpect(status().isOk()).andReturn());
        String convId = conv.get("id").asText();
        assertThat(conv.get("messages")).hasSize(2);
        assertThat(conv.get("messages").get(1).get("text").asText()).contains("Resumo do processo");
        assertThat(conv.get("inputTokens").asLong()).isEqualTo(120);
        JsonNode sent = REQUESTS.get(REQUESTS.size() - 1);
        assertThat(sent.get("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(sent.get("output_config").get("effort").asText()).isEqualTo("high");
        assertThat(sent.get("system").toString()).contains("Não invente jurisprudência").contains("[NÃO CONFIRMADO]");
        assertThat(sent.toString()).contains("Indenização por dano moral").contains("***.***.***-25").doesNotContain("529.982.247");
        assertThat(sent.has("tools")).isFalse();
        assertThat(API_KEYS.get(API_KEYS.size() - 1)).isEqualTo("Bearer sk-ant-api03-chave-de-teste");
        assertThat(BETAS.get(BETAS.size() - 1)).contains("server-side-fallback-2026-07-01");
        assertThat(sent.get("fallbacks").asText()).isEqualTo("default");

        // continuação: o histórico volta com o contexto relido
        JsonNode follow = body(mvc.perform(json(post("/api/ai/ask"), Map.of("conversationId", convId, "message", "E os prazos?")))
                .andExpect(status().isOk()).andReturn());
        assertThat(follow.get("messages")).hasSize(4);
        JsonNode sent2 = REQUESTS.get(REQUESTS.size() - 1);
        assertThat(sent2.get("messages")).hasSize(3);
        assertThat(sent2.get("messages").get(0).toString()).contains("Indenização por dano moral");
        assertThat(sent2.get("messages").get(1).get("role").asText()).isEqualTo("assistant");

        // a auditoria registra o uso, nunca o conteúdo
        JsonNode logs = body(mvc.perform(auth(get("/api/audit")).param("action", "AI_REQUEST")).andReturn()).get("items");
        assertThat(logs).hasSize(2);
        assertThat(logs.toString()).contains("RESUMO_PROCESSO").doesNotContain("Destaque os riscos").doesNotContain("E os prazos");

        // ------------------------------------------------------------------ 3. Pesquisa com fontes oficiais
        JsonNode research = body(mvc.perform(json(post("/api/ai/ask"), Map.of("agent", "ASSISTENTE_JURIDICO", "task", "PESQUISA",
                "message", "Qual o prazo de prescrição da pretensão de reparação civil?"))).andExpect(status().isOk()).andReturn());
        assertThat(research.get("agent").asText()).isEqualTo("PESQUISADOR");
        JsonNode answer = research.get("messages").get(1);
        assertThat(answer.get("sources")).hasSize(1);
        assertThat(answer.get("sources").get(0).get("url").asText()).startsWith("https://www.planalto.gov.br/");
        JsonNode tool = REQUESTS.get(REQUESTS.size() - 1).get("tools").get(0);
        assertThat(tool.get("type").asText()).isEqualTo("web_search_20260209");
        assertThat(tool.get("allowed_domains").toString()).contains("jus.br").contains("gov.br").contains("leg.br");

        // ------------------------------------------------------------------ 4. Extração estruturada de datas de um documento
        String docId = body(mvc.perform(auth(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "intimacao.txt", "text/plain",
                                ("Intimação: fica a parte intimada para apresentar contestação no prazo de 15 dias. Audiência de conciliação "
                                        + "designada para 20/01/2027 às 14h. Pagamento da primeira parcela em 05/02/2027.").getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("meta", "", "application/json",
                                JSON.writeValueAsBytes(Map.of("title", "Intimação", "category", "DECISAO", "caseId", caseId))))))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        JsonNode ext = body(mvc.perform(json(post("/api/ai/ask"), Map.of("agent", "ANALISADOR_DOCUMENTOS", "task", "EXTRACAO_DATAS",
                "message", "", "documentIds", List.of(docId)))).andExpect(status().isOk()).andReturn());
        JsonNode items = ext.get("messages").get(1).get("extraction");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("date").asText()).isEqualTo("2027-01-20");
        assertThat(items.get(0).get("kind").asText()).isEqualTo("AUDIENCIA");
        JsonNode sentExt = REQUESTS.get(REQUESTS.size() - 1);
        assertThat(sentExt.get("output_config").get("format").get("type").asText()).isEqualTo("json_schema");
        assertThat(sentExt.toString()).contains("Audiência de conciliação");

        // ------------------------------------------------------------------ 5. Recusa do provedor tratada
        JsonNode refused = body(mvc.perform(json(post("/api/ai/ask"), Map.of("agent", "ASSISTENTE_JURIDICO", "task", "CONVERSA",
                "message", "PEDIDO_RECUSADO"))).andExpect(status().isOk()).andReturn());
        assertThat(refused.get("messages").get(1).get("text").asText()).contains("política de segurança");
        assertThat(refused.get("messages").get(1).get("stopReason").asText()).isEqualTo("refusal");

        // ------------------------------------------------------------------ 6. Revisão humana e envio ao Editor Jurídico
        mvc.perform(auth(post("/api/ai/conversations/" + convId + "/messages/0/review"))).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("INVALID_MESSAGE"));
        mvc.perform(auth(post("/api/ai/conversations/" + convId + "/messages/1/review"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[1].reviewed").value(true)).andExpect(jsonPath("$.messages[1].reviewedByName").value("Dr. Ivo Inteligente"));
        JsonNode draft = body(mvc.perform(json(post("/api/ai/conversations/" + convId + "/messages/1/to-editor"),
                Map.of("title", "Resumo para o cliente", "kind", "RELATORIO_PROCESSUAL"))).andExpect(status().isOk()).andReturn());
        assertThat(draft.get("status").asText()).isEqualTo("RASCUNHO");
        assertThat(draft.get("content").asText()).contains("Rascunho gerado com IA").contains("<h2>Resumo do processo</h2>")
                .contains("<strong>riscos</strong>").doesNotContain("<script");
        assertThat(draft.get("caseId").asText()).isEqualTo(caseId);
        mvc.perform(auth(get("/api/ai/conversations"))).andExpect(jsonPath("$.length()").value(4));

        // ------------------------------------------------------------------ 7. Permissões e privacidade
        String internEmail = "estag-" + UUID.randomUUID() + "@escritorio-ia.test";
        mvc.perform(json(post("/api/employees"), Map.of("email", internEmail, "displayName", "Edu Estagiário", "role", "ESTAGIARIO",
                "password", "Senha-Membro-123"))).andExpect(status().isCreated());
        String secEmail = "sec-" + UUID.randomUUID() + "@escritorio-ia.test";
        mvc.perform(json(post("/api/employees"), Map.of("email", secEmail, "displayName", "Sara Secretária", "role", "SECRETARIA",
                "password", "Senha-Membro-123"))).andExpect(status().isCreated());
        String owner = token;
        token = authCall("accounts:signInWithPassword", internEmail, "Senha-Membro-123");
        mvc.perform(json(post("/api/ai/ask"), Map.of("agent", "ASSISTENTE_CRIMINAL", "task", "CONVERSA", "message", "Oi")))
                .andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/ai/conversations/" + convId))).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/ai/conversations"))).andExpect(jsonPath("$.length()").value(0));
        token = authCall("accounts:signInWithPassword", secEmail, "Senha-Membro-123");
        mvc.perform(auth(get("/api/ai/catalog"))).andExpect(status().isForbidden());
        token = owner;
    }

    // ------------------------------------------------------------------ API de Mensagens de teste

    private static HttpServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", AiEndToEndTest::handle);
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void handle(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/v1/messages")) {
            reply(ex, 404, "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\",\"message\":\"not found\"}}");
            return;
        }
        JsonNode req = JSON.readTree(ex.getRequestBody().readAllBytes());
        REQUESTS.add(req);
        // o SDK oficial 3.x envia a chave como Authorization: Bearer
        API_KEYS.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
        BETAS.add(String.valueOf(ex.getRequestHeaders().get("anthropic-beta")));
        String body = req.toString();
        String content;
        String stop = "end_turn";
        String details = "";
        if (req.path("output_config").has("format")) {
            String json = "{\"items\":[{\"date\":\"2027-01-20\",\"time\":\"14:00\",\"kind\":\"AUDIENCIA\",\"description\":\"Audiência de conciliação\","
                    + "\"sourceQuote\":\"Audiência de conciliação designada para 20/01/2027 às 14h\",\"confidence\":\"ALTA\"},"
                    + "{\"date\":\"2027-02-05\",\"time\":\"\",\"kind\":\"VENCIMENTO\",\"description\":\"Primeira parcela\","
                    + "\"sourceQuote\":\"Pagamento da primeira parcela em 05/02/2027\",\"confidence\":\"ALTA\"}],"
                    + "\"observations\":\"O prazo de 15 dias depende da data da intimação.\"}";
            content = "[{\"type\":\"text\",\"text\":" + JSON.writeValueAsString(json) + "}]";
        } else if (req.has("tools")) {
            content = "[{\"type\":\"text\",\"text\":\"O prazo é de três anos \",\"citations\":[{\"type\":\"web_search_result_location\","
                    + "\"url\":\"https://www.planalto.gov.br/ccivil_03/leis/2002/l10406compilada.htm\",\"title\":\"Código Civil\","
                    + "\"cited_text\":\"Art. 206. Prescreve: § 3º Em três anos: V - a pretensão de reparação civil;\",\"encrypted_index\":\"x\"}]},"
                    + "{\"type\":\"text\",\"text\":\"(art. 206, § 3º, V, do Código Civil).\"}]";
        } else if (body.contains("PEDIDO_RECUSADO")) {
            content = "[]";
            stop = "refusal";
            details = ",\"stop_details\":{\"type\":\"refusal\",\"category\":\"cyber\",\"explanation\":\"Pedido recusado.\"}";
        } else {
            content = "[{\"type\":\"text\",\"text\":" + JSON.writeValueAsString("## Resumo do processo\n\nAção de indenização. Principais **riscos**:\n"
                    + "- prova do dano\n- prescrição [NÃO CONFIRMADO]") + "}]";
        }
        reply(ex, 200, "{\"id\":\"msg_" + UUID.randomUUID() + "\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\","
                + "\"content\":" + content + ",\"stop_reason\":\"" + stop + "\",\"stop_sequence\":null" + details
                + ",\"usage\":{\"input_tokens\":120,\"output_tokens\":40}}");
    }

    private static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
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
