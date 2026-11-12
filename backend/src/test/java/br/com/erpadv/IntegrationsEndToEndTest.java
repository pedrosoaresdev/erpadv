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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 4 — integrações contra o Emulator Suite. Os serviços externos (DataJud, DJEN, WhatsApp Cloud API e ZapSign) são
 * substituídos por um servidor HTTP local de teste que responde no formato documentado de cada API; nenhum dado é
 * apresentado ao usuário como real.
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
        "spring.mail.host=",
        "app.integrations.datajud.api-key=test-key"
})
@AutoConfigureMockMvc
class IntegrationsEndToEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String PASSWORD = "Senha-Forte-123";
    private static final String CNJ = cnj("0016718", "2025", "8", "17", "2480");
    private static final String CNJ_IMPORT = cnj("0001234", "2024", "8", "17", "0001");
    private static final List<String> SEEN = new CopyOnWriteArrayList<>();
    private static final Map<String, String> HEADERS = new ConcurrentHashMap<>();
    private static final HttpServer STUB = start();

    @Autowired
    MockMvc mvc;

    private String token;

    @DynamicPropertySource
    static void stubUrls(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + STUB.getAddress().getPort();
        r.add("app.integrations.datajud.base-url", () -> base);
        r.add("app.integrations.djen.base-url", () -> base);
        r.add("app.integrations.whatsapp.graph-url", () -> base + "/v20");
        r.add("app.integrations.zapsign.url", () -> base);
        r.add("app.integrations.zapsign.sandbox-url", () -> base);
    }

    @AfterAll
    static void stop() {
        STUB.stop(0);
    }

    @Test
    void integracoesCompletas() throws Exception {
        String email = "integra-" + UUID.randomUUID() + "@escritorio-i.test";
        token = authCall("accounts:signUp", email);
        mvc.perform(json(post("/api/onboarding/office"), Map.of("name", "Escritório Integrações", "responsibleName", "Dra. Iara Integra",
                "oabNumber", "PE 5555"))).andExpect(status().isCreated());
        token = authCall("accounts:signInWithPassword", email);
        JsonNode me = body(mvc.perform(auth(get("/api/auth/me"))).andReturn());
        String ownerUid = me.get("uid").asText();
        String officeId = me.get("officeId").asText();
        String clientId = body(mvc.perform(json(post("/api/clients"), Map.of("type", "PF", "name", "Bruno Cliente", "document", "529.982.247-25",
                "whatsapp", "(81) 99999-1234", "email", "bruno@cliente.test"))).andExpect(status().isCreated()).andReturn()).get("id").asText();
        Map<String, Object> caseReq = new HashMap<>(Map.of("clientId", clientId, "cnjNumber", CNJ, "area", "CIVEL", "status", "EM_ANDAMENTO",
                "responsibleUid", ownerUid, "court", "TJPE"));
        String caseId = body(mvc.perform(json(post("/api/cases"), caseReq)).andExpect(status().isCreated()).andReturn()).get("id").asText();

        // ------------------------------------------------------------------ 1. Catálogo (sem segredos na resposta)
        JsonNode catalog = body(mvc.perform(auth(get("/api/integrations"))).andExpect(status().isOk()).andReturn());
        assertThat(catalog.toString()).doesNotContain("test-key");
        assertThat(catalog.findValuesAsText("provider")).contains("DATAJUD", "DJEN", "PJE", "WHATSAPP", "ZAPSIGN", "SMTP", "GOOGLE_CALENDAR");
        Map<String, String> states = new HashMap<>();
        catalog.forEach(c -> states.put(c.get("provider").asText(), c.get("state").asText()));
        // sem configuração salva tudo fica desligado (o escritório trabalha manualmente); tribunais sem API indicados como tais
        assertThat(states).containsEntry("DATAJUD", "DESATIVADO").containsEntry("DJEN", "DESATIVADO").containsEntry("PJE", "SEM_API")
                .containsEntry("SMTP", "NAO_CONFIGURADO").containsEntry("WHATSAPP", "NAO_CONFIGURADO").containsEntry("ANTHROPIC", "NAO_CONFIGURADO");
        mvc.perform(auth(post("/api/integrations/datajud/cases/" + caseId))).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("INTEGRATION_DISABLED"));
        for (String provider : List.of("DATAJUD", "DJEN")) {
            mvc.perform(json(put("/api/integrations/" + provider), Map.of("enabled", true, "autoSync", false, "config", Map.of(), "secrets", Map.of())))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ATIVO"));
        }

        // ------------------------------------------------------------------ 2. DataJud (API pública do CNJ)
        mvc.perform(auth(post("/api/integrations/datajud/cases/" + caseId))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK")).andExpect(jsonPath("$.newMovements").value(2));
        assertThat(HEADERS.get("datajud-auth")).isEqualTo("APIKey test-key");
        assertThat(SEEN).contains("POST /api_publica_tjpe/_search");
        mvc.perform(auth(post("/api/integrations/datajud/cases/" + caseId))).andExpect(status().isOk())
                .andExpect(jsonPath("$.newMovements").value(0));
        JsonNode moves = body(mvc.perform(auth(get("/api/cases/" + caseId + "/movements"))).andExpect(status().isOk()).andReturn()).get("items");
        assertThat(moves.findValuesAsText("source")).containsExactlyInAnyOrder("MANUAL", "DATAJUD", "DATAJUD");
        assertThat(moves.findValuesAsText("title")).contains("Despacho — mero expediente", "Distribuição");
        JsonNode synced = body(mvc.perform(auth(get("/api/cases/" + caseId))).andReturn());
        assertThat(synced.get("subject").asText()).isEqualTo("Indenização por Dano Moral");
        assertThat(synced.get("court").asText()).isEqualTo("TJPE");
        mvc.perform(auth(get("/api/integrations/runs")).param("provider", "DATAJUD")).andExpect(jsonPath("$.length()").value(2));

        // ------------------------------------------------------------------ 3. DJEN (Comunica PJe) → prazo
        LocalDate today = LocalDate.now();
        mvc.perform(auth(post("/api/integrations/djen/sync")).param("from", today.minusDays(40).toString()).param("to", today.toString()))
                .andExpect(status().is4xxClientError()).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        mvc.perform(auth(post("/api/integrations/djen/sync")).param("from", today.minusDays(3).toString()).param("to", today.toString()))
                .andExpect(status().isOk());
        assertThat(SEEN.stream().filter(s -> s.startsWith("GET /api/v1/comunicacao")).findFirst().orElse(""))
                .contains("numeroOab=5555").contains("ufOab=PE");
        JsonNode pubs = body(mvc.perform(auth(get("/api/publications"))).andExpect(status().isOk()).andReturn());
        assertThat(pubs).hasSize(1);
        JsonNode pub = pubs.get(0);
        assertThat(pub.get("caseId").asText()).isEqualTo(caseId);
        assertThat(pub.get("status").asText()).isEqualTo("NOVA");
        assertThat(pub.get("lawyerUid").asText()).isEqualTo(ownerUid);
        mvc.perform(auth(post("/api/integrations/djen/sync")).param("from", today.minusDays(3).toString()).param("to", today.toString()))
                .andExpect(status().isOk());
        mvc.perform(auth(get("/api/publications"))).andExpect(jsonPath("$.length()").value(1));
        String pubId = pub.get("id").asText();
        mvc.perform(json(post("/api/publications/" + pubId + "/status"), Map.of("status", "LIDA"))).andExpect(jsonPath("$.status").value("LIDA"));
        Map<String, Object> dl = new HashMap<>(Map.of("caseId", caseId, "description", "Manifestação — intimação DJEN", "type", "MANIFESTACAO",
                "startDate", today.plusDays(1).toString(), "days", 5, "countingMode", "UTEIS", "court", "TJPE", "responsibleUid", ownerUid,
                "priority", "ALTA"));
        dl.put("origin", "PUBLICACAO_DJE");
        String deadlineId = body(mvc.perform(json(post("/api/deadlines"), dl)).andExpect(status().isCreated()).andReturn()).get("id").asText();
        mvc.perform(json(post("/api/publications/" + pubId + "/deadline"), Map.of("id", deadlineId)))
                .andExpect(jsonPath("$.status").value("PRAZO_CRIADO")).andExpect(jsonPath("$.deadlineId").value(deadlineId));

        // ------------------------------------------------------------------ 4. Importação assistida (planilha)
        MvcResult tpl = mvc.perform(auth(get("/api/integrations/import/cases/template"))).andExpect(status().isOk()).andReturn();
        assertThat(tpl.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("numero_cnj;cliente_cpf_cnpj");
        String csv = "numero_cnj;cliente_cpf_cnpj;cliente_nome;area;tribunal;uf;valor_causa\n"
                + CNJ_IMPORT + ";529.982.247-25;;CIVEL;TJPE;pe;1.500,00\n"
                + CNJ + ";529.982.247-25;;CIVEL;TJPE;PE;\n"
                + "1234567-00.2024.8.17.0001;529.982.247-25;;CIVEL;TJPE;PE;\n"
                + cnj("0009999", "2024", "8", "17", "0001") + ";111.111.111-11;Cliente Inexistente;CIVEL;TJPE;PE;\n";
        JsonNode imported = body(mvc.perform(auth(multipart("/api/integrations/import/cases")
                        .file(new MockMultipartFile("file", "processos.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))))
                .andExpect(status().isOk()).andReturn());
        assertThat(imported.get("total").asInt()).isEqualTo(4);
        assertThat(imported.get("created").asInt()).isEqualTo(1);
        assertThat(imported.get("duplicates").asInt()).isEqualTo(1);
        assertThat(imported.get("errors").asInt()).isEqualTo(2);
        JsonNode importedCase = body(mvc.perform(auth(get("/api/cases/" + imported.get("rows").get(0).get("caseId").asText()))).andReturn());
        assertThat(importedCase.get("clientId").asText()).isEqualTo(clientId);
        assertThat(importedCase.get("state").asText()).isEqualTo("PE");
        assertThat(importedCase.get("claimValueCents").asLong()).isEqualTo(150000);

        // ------------------------------------------------------------------ 5. E-mail sem SMTP configurado
        mvc.perform(auth(get("/api/communications/channels"))).andExpect(jsonPath("$.EMAIL").value(false))
                .andExpect(jsonPath("$.WHATSAPP").value(false));
        mvc.perform(json(post("/api/communications/send"), Map.of("channel", "EMAIL", "clientId", clientId, "subject", "Andamento",
                "body", "Olá"))).andExpect(status().is4xxClientError()).andExpect(jsonPath("$.code").value("EMAIL_NOT_CONFIGURED"));
        mvc.perform(json(post("/api/communications/send"), Map.of("channel", "WHATSAPP", "clientId", clientId, "body", "Olá")))
                .andExpect(status().is4xxClientError()).andExpect(jsonPath("$.code").value("WHATSAPP_NOT_CONFIGURED"));

        // ------------------------------------------------------------------ 6. WhatsApp Business (Cloud API)
        Map<String, Object> wa = new HashMap<>();
        wa.put("enabled", true);
        wa.put("autoSync", false);
        wa.put("config", Map.of("phoneNumberId", "PHONE1", "displayNumber", "+55 81 3000-0000"));
        wa.put("secrets", Map.of("accessToken", "meta-token", "appSecret", "app-secret", "verifyToken", "verifica-123"));
        JsonNode waStatus = body(mvc.perform(json(put("/api/integrations/WHATSAPP"), wa)).andExpect(status().isOk()).andReturn());
        assertThat(waStatus.toString()).doesNotContain("meta-token").doesNotContain("app-secret");
        assertThat(waStatus.get("configuredSecrets").toString()).contains("accessToken");
        mvc.perform(json(post("/api/communications/send"), Map.of("channel", "WHATSAPP", "clientId", clientId, "body", "Bom dia, Bruno!")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ENVIADO"))
                .andExpect(jsonPath("$.externalId").value("wamid.saida.1")).andExpect(jsonPath("$.address").value("5581999991234"));
        assertThat(HEADERS.get("whatsapp-auth")).isEqualTo("Bearer meta-token");
        assertThat(HEADERS.get("whatsapp-body")).contains("\"to\":\"5581999991234\"").contains("Bom dia, Bruno!");

        mvc.perform(get("/api/hooks/whatsapp/" + officeId).param("hub.mode", "subscribe").param("hub.verify_token", "verifica-123")
                .param("hub.challenge", "4242")).andExpect(status().isOk()).andExpect(content().string("4242"));
        mvc.perform(get("/api/hooks/whatsapp/" + officeId).param("hub.mode", "subscribe").param("hub.verify_token", "errado")
                .param("hub.challenge", "4242")).andExpect(status().isForbidden());
        byte[] inbound = ("{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"field\":\"messages\",\"value\":{"
                + "\"contacts\":[{\"wa_id\":\"5581999991234\",\"profile\":{\"name\":\"Bruno\"}}],"
                + "\"messages\":[{\"from\":\"5581999991234\",\"id\":\"wamid.entrada.1\",\"timestamp\":\"1762600000\",\"type\":\"text\","
                + "\"text\":{\"body\":\"Obrigado, doutora\"}}]}}]}]}").getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/api/hooks/whatsapp/" + officeId).contentType(MediaType.APPLICATION_JSON).content(inbound)
                .header("X-Hub-Signature-256", "sha256=" + "0".repeat(64))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/hooks/whatsapp/" + officeId).contentType(MediaType.APPLICATION_JSON).content(inbound)
                .header("X-Hub-Signature-256", sign("app-secret", inbound))).andExpect(status().isOk());
        mvc.perform(post("/api/hooks/whatsapp/" + officeId).contentType(MediaType.APPLICATION_JSON).content(inbound)
                .header("X-Hub-Signature-256", sign("app-secret", inbound))).andExpect(status().isOk());
        JsonNode history = body(mvc.perform(auth(get("/api/communications")).param("clientId", clientId)).andExpect(status().isOk()).andReturn());
        assertThat(history).hasSize(2);
        assertThat(history.findValuesAsText("status")).containsExactlyInAnyOrder("ENVIADO", "RECEBIDO");

        // ------------------------------------------------------------------ 7. Registro de atendimento e lembrete de retorno
        String reg = body(mvc.perform(json(post("/api/communications/register"), Map.of("channel", "TELEFONE", "direction", "ENTRADA",
                "clientId", clientId, "caseId", caseId, "body", "Cliente ligou pedindo atualização", "followUpDate", today.toString())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("REGISTRADO")).andExpect(jsonPath("$.caseNumber").value(CNJ))
                .andReturn()).get("id").asText();
        mvc.perform(auth(get("/api/communications")).param("followUps", "true")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(auth(post("/api/communications/" + reg + "/follow-up-done"))).andExpect(status().isOk());
        mvc.perform(auth(get("/api/communications")).param("followUps", "true")).andExpect(jsonPath("$.length()").value(0));

        // ------------------------------------------------------------------ 8. Modelos de mensagem
        String tplId = body(mvc.perform(json(post("/api/communications/templates"), Map.of("name", "Andamento do processo", "channel", "EMAIL",
                "subject", "Processo {{processo.numero}}", "body", "Olá, {{cliente.nome}}. Novidades no processo {{processo.numero}}. {{campo.inexistente}}")))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        JsonNode rendered = body(mvc.perform(auth(get("/api/communications/templates/" + tplId + "/render")).param("clientId", clientId)
                .param("caseId", caseId)).andExpect(status().isOk()).andReturn());
        assertThat(rendered.get("body").asText()).contains("Olá, Bruno Cliente").contains(CNJ);
        assertThat(rendered.get("missing").toString()).contains("campo.inexistente");
        mvc.perform(auth(delete("/api/communications/templates/" + tplId))).andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/communications/templates"))).andExpect(jsonPath("$.length()").value(0));

        // ------------------------------------------------------------------ 9. Assinatura eletrônica (ZapSign)
        byte[] pdf = "%PDF-1.4\n% contrato de honorarios\n".getBytes(StandardCharsets.US_ASCII);
        String docId = body(mvc.perform(auth(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "contrato.pdf", "application/pdf", pdf))
                        .file(new MockMultipartFile("meta", "", "application/json",
                                JSON.writeValueAsBytes(Map.of("title", "Contrato de honorários", "category", "CONTRATO", "clientId", clientId))))))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        Map<String, Object> signReq = Map.of("documentId", docId, "signers", List.of(Map.of("name", "Bruno Cliente", "email", "bruno@cliente.test")));
        mvc.perform(json(post("/api/integrations/signatures"), signReq)).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("SIGNATURE_NOT_CONFIGURED"));
        Map<String, Object> zs = new HashMap<>();
        zs.put("enabled", true);
        zs.put("autoSync", false);
        zs.put("config", Map.of("sandbox", "true"));
        zs.put("secrets", Map.of("apiToken", "zap-token", "webhookSecret", "segredo-webhook"));
        mvc.perform(json(put("/api/integrations/ZAPSIGN"), zs)).andExpect(status().isOk());
        JsonNode sig = body(mvc.perform(json(post("/api/integrations/signatures"), signReq)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENVIADO")).andExpect(jsonPath("$.sandbox").value(true)).andReturn());
        assertThat(sig.get("signers").get(0).get("signUrl").asText()).isEqualTo("https://sandbox.zapsign.test/sign/s1");
        assertThat(HEADERS.get("zapsign-auth")).isEqualTo("Bearer zap-token");
        assertThat(HEADERS.get("zapsign-body")).contains("base64_pdf").contains("bruno@cliente.test");
        mvc.perform(post("/api/hooks/zapsign/" + officeId).param("secret", "errado").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"doc-tok\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/hooks/zapsign/" + officeId).param("secret", "segredo-webhook").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"doc-tok\",\"event_type\":\"doc_signed\"}")).andExpect(status().isOk());
        JsonNode after = body(mvc.perform(auth(get("/api/integrations/signatures")).param("documentId", docId)).andReturn()).get(0);
        assertThat(after.get("status").asText()).isEqualTo("ASSINADO");
        assertThat(after.get("signedStored").asBoolean()).isTrue();
        assertThat(after.get("signedVersion").asInt()).isEqualTo(2);
        assertThat(after.get("signers").get(0).get("status").asText()).isEqualTo("ASSINOU");
        mvc.perform(auth(get("/api/documents/" + docId))).andExpect(jsonPath("$.currentVersion").value(2));
        MvcResult signedFile = mvc.perform(auth(get("/api/documents/" + docId + "/download"))).andReturn();
        if (signedFile.getResponse().getStatus() == 200) {
            assertThat(signedFile.getResponse().getContentAsString(StandardCharsets.US_ASCII)).contains("via assinada");
        }

        // ------------------------------------------------------------------ 10. Segredos removidos e conector desativado
        Map<String, Object> off = new HashMap<>();
        off.put("enabled", false);
        off.put("autoSync", false);
        off.put("config", Map.of());
        off.put("secrets", Map.of("accessToken", "__remove__"));
        JsonNode offStatus = body(mvc.perform(json(put("/api/integrations/WHATSAPP"), off)).andExpect(status().isOk()).andReturn());
        assertThat(offStatus.get("configuredSecrets").toString()).doesNotContain("accessToken");
        mvc.perform(json(post("/api/communications/send"), Map.of("channel", "WHATSAPP", "clientId", clientId, "body", "Olá")))
                .andExpect(status().is4xxClientError()).andExpect(jsonPath("$.code").value("WHATSAPP_NOT_CONFIGURED"));
    }

    // ------------------------------------------------------------------ servidor de teste das APIs externas

    private static HttpServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", IntegrationsEndToEndTest::handle);
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getRawQuery();
        SEEN.add(ex.getRequestMethod() + " " + path + (query == null ? "" : "?" + query));
        String req = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        int port = STUB == null ? 0 : STUB.getAddress().getPort();
        String digits = CNJ.replaceAll("\\D", "");
        if (path.startsWith("/api_publica_")) {
            HEADERS.put("datajud-auth", String.valueOf(auth));
            reply(ex, 200, "{\"hits\":{\"total\":{\"value\":1},\"hits\":[{\"_index\":\"api_publica_tjpe\",\"_source\":{"
                    + "\"numeroProcesso\":\"" + digits + "\",\"classe\":{\"codigo\":7,\"nome\":\"Procedimento Comum Cível\"},"
                    + "\"sistema\":{\"codigo\":1,\"nome\":\"PJe\"},\"grau\":\"G1\",\"tribunal\":\"TJPE\","
                    + "\"orgaoJulgador\":{\"codigo\":1,\"nome\":\"2ª Vara Cível da Capital\"},"
                    + "\"assuntos\":[{\"codigo\":10433,\"nome\":\"Indenização por Dano Moral\"}],\"dataAjuizamento\":\"20250310000000\","
                    + "\"movimentos\":[{\"codigo\":26,\"nome\":\"Distribuição\",\"dataHora\":\"2025-03-10T09:00:00.000Z\"},"
                    + "{\"codigo\":11010,\"nome\":\"Despacho\",\"dataHora\":\"2025-04-01T10:00:00.000Z\","
                    + "\"complementosTabelados\":[{\"nome\":\"mero expediente\"}]}]}}]}}");
        } else if (path.equals("/api/v1/comunicacao")) {
            boolean first = query != null && query.contains("pagina=1");
            String item = "{\"id\":900001,\"data_disponibilizacao\":\"" + LocalDate.now().minusDays(1) + "\",\"siglaTribunal\":\"TJPE\","
                    + "\"tipoComunicacao\":\"Intimação\",\"nomeOrgao\":\"2ª Vara Cível da Capital\",\"texto\":\"Intime-se a parte autora para manifestação em 5 dias.\","
                    + "\"numero_processo\":\"" + digits + "\",\"numeroprocessocommascara\":\"" + CNJ + "\",\"link\":\"https://comunica.pje.jus.br/consulta\","
                    + "\"tipoDocumento\":\"Despacho\",\"nomeClasse\":\"PROCEDIMENTO COMUM CÍVEL\",\"hash\":\"h900001\",\"ativo\":true,\"data_cancelamento\":null}";
            reply(ex, 200, "{\"status\":\"success\",\"message\":\"ok\",\"count\":" + (first ? 1 : 0) + ",\"items\":[" + (first ? item : "") + "]}");
        } else if (path.startsWith("/v20/") && path.endsWith("/messages")) {
            HEADERS.put("whatsapp-auth", String.valueOf(auth));
            HEADERS.put("whatsapp-body", req);
            reply(ex, 200, "{\"messaging_product\":\"whatsapp\",\"contacts\":[{\"wa_id\":\"5581999991234\"}],\"messages\":[{\"id\":\"wamid.saida.1\"}]}");
        } else if (path.equals("/api/v1/docs/") && ex.getRequestMethod().equals("POST")) {
            HEADERS.put("zapsign-auth", String.valueOf(auth));
            HEADERS.put("zapsign-body", req);
            reply(ex, 200, "{\"token\":\"doc-tok\",\"status\":\"pending\",\"signers\":[{\"token\":\"s1\",\"name\":\"Bruno Cliente\","
                    + "\"email\":\"bruno@cliente.test\",\"sign_url\":\"https://sandbox.zapsign.test/sign/s1\",\"status\":\"new\"}]}");
        } else if (path.equals("/api/v1/docs/doc-tok/")) {
            reply(ex, 200, "{\"token\":\"doc-tok\",\"status\":\"signed\",\"signed_file\":\"http://127.0.0.1:" + port + "/files/signed.pdf\","
                    + "\"signers\":[{\"token\":\"s1\",\"name\":\"Bruno Cliente\",\"email\":\"bruno@cliente.test\",\"status\":\"signed\","
                    + "\"signed_at\":\"2026-11-08T10:00:00Z\"}]}");
        } else if (path.equals("/files/signed.pdf")) {
            byte[] b = "%PDF-1.4\n% via assinada\n".getBytes(StandardCharsets.US_ASCII);
            ex.getResponseHeaders().add("Content-Type", "application/pdf");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        } else {
            reply(ex, 404, "{\"error\":\"not found\"}");
        }
    }

    private static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    /** Número CNJ com dígitos verificadores válidos (módulo 97, Res. CNJ 65/2008). */
    private static String cnj(String seq, String year, String j, String tr, String origin) {
        BigInteger n = new BigInteger(seq + year + j + tr + origin + "00");
        int dv = 98 - n.mod(BigInteger.valueOf(97)).intValue();
        return seq + "-" + String.format("%02d", dv) + "." + year + "." + j + "." + tr + "." + origin;
    }

    private static String sign(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
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
