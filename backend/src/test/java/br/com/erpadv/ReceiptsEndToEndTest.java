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
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Recibos para clientes: emissão manual, PDF na central documental, emissão a partir de recebimento, cancelamento e permissões. */
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
class ReceiptsEndToEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    MockMvc mvc;

    private String token;

    @Test
    void recibosParaClientes() throws Exception {
        String email = "rec-" + UUID.randomUUID() + "@escritorio-r.test";
        token = authCall("accounts:signUp", email, "Senha-Forte-123");
        mvc.perform(json(post("/api/onboarding/office"), Map.of("name", "Escritório Recibos", "responsibleName", "Dra. Rita Recibo",
                "oabNumber", "PE 1212"))).andExpect(status().isCreated());
        token = authCall("accounts:signInWithPassword", email, "Senha-Forte-123");
        String ownerUid = body(mvc.perform(auth(get("/api/auth/me"))).andReturn()).get("uid").asText();
        LocalDate today = LocalDate.now(ZoneId.of("America/Sao_Paulo"));
        int year = today.getYear();
        String clientId = body(mvc.perform(json(post("/api/clients"), Map.of("type", "PF", "name", "Rui Recebedor", "document", "529.982.247-25")))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        String caseId = body(mvc.perform(json(post("/api/cases"), Map.of("clientId", clientId, "area", "CIVEL", "status", "EM_ANDAMENTO",
                "responsibleUid", ownerUid, "court", "TJPE"))).andExpect(status().isCreated()).andReturn()).get("id").asText();

        // ------------------------------------------------------------------ 1. Recibo de pagamento manual
        Map<String, Object> req = new HashMap<>(Map.of("kind", "PAGAMENTO", "clientId", clientId, "amountCents", 150000,
                "description", "honorários advocatícios iniciais", "paymentMethod", "Pix", "caseId", caseId, "saveToDocuments", true));
        mvc.perform(json(post("/api/receipts"), Map.of("kind", "PAGAMENTO", "clientId", clientId, "amountCents", 0, "description", "x")))
                .andExpect(status().is4xxClientError()).andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
        JsonNode r1 = body(mvc.perform(json(post("/api/receipts"), req)).andExpect(status().isCreated()).andReturn());
        assertThat(r1.get("number").asText()).isEqualTo("REC-" + year + "-0001");
        assertThat(r1.get("payerName").asText()).isEqualTo("Rui Recebedor");
        assertThat(r1.get("documentId").asText()).isNotBlank();
        MvcResult pdf = mvc.perform(auth(get("/api/receipts/" + r1.get("id").asText() + "/pdf"))).andExpect(status().isOk()).andReturn();
        assertThat(new String(pdf.getResponse().getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        JsonNode doc = body(mvc.perform(auth(get("/api/documents/" + r1.get("documentId").asText()))).andExpect(status().isOk()).andReturn());
        assertThat(doc.get("clientId").asText()).isEqualTo(clientId);
        assertThat(doc.get("category").asText()).isEqualTo("COMPROVANTE");
        assertThat(doc.get("title").asText()).contains("REC-" + year + "-0001");

        // ------------------------------------------------------------------ 2. Recibo de entrega de documentos (sem valor)
        JsonNode r2 = body(mvc.perform(json(post("/api/receipts"), Map.of("kind", "ENTREGA_DOCUMENTOS", "clientId", clientId,
                "description", "RG original\nComprovante de residência", "saveToDocuments", false))).andExpect(status().isCreated()).andReturn());
        assertThat(r2.get("number").asText()).isEqualTo("REC-" + year + "-0002");
        assertThat(r2.path("amountCents").isMissingNode() || r2.get("amountCents").isNull()).isTrue();
        assertThat(r2.path("documentId").isMissingNode()).isTrue();

        // ------------------------------------------------------------------ 3. A partir de um recebimento do financeiro
        JsonNode parcels = body(mvc.perform(json(post("/api/finance/transactions"), Map.of("direction", "RECEITA", "category", "HONORARIOS",
                "description", "Honorários de êxito", "amountCents", 80000, "dueDate", today.toString(), "clientId", clientId)))
                .andExpect(status().isCreated()).andReturn());
        String txId = parcels.get(0).get("id").asText();
        assertThat(body(mvc.perform(auth(get("/api/receipts/pending"))).andReturn())).isEmpty();
        mvc.perform(json(post("/api/finance/transactions/" + txId + "/pay"), Map.of("paidDate", today.toString(), "paidAmountCents", 80000,
                "paymentMethod", "TRANSFERENCIA"))).andExpect(status().isOk());
        JsonNode pending = body(mvc.perform(auth(get("/api/receipts/pending"))).andReturn());
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).get("transactionId").asText()).isEqualTo(txId);
        JsonNode r3 = body(mvc.perform(json(post("/api/receipts/from-transaction"), Map.of("transactionId", txId, "saveToDocuments", true)))
                .andExpect(status().isCreated()).andReturn());
        assertThat(r3.get("number").asText()).isEqualTo("REC-" + year + "-0003");
        assertThat(r3.get("amountCents").asLong()).isEqualTo(80000);
        assertThat(r3.get("paymentMethod").asText()).isEqualTo("transferência bancária");
        mvc.perform(auth(get("/api/finance/transactions/" + txId))).andExpect(jsonPath("$.receiptNumber").value("REC-" + year + "-0003"));
        assertThat(body(mvc.perform(auth(get("/api/receipts/pending"))).andReturn())).isEmpty();
        mvc.perform(json(post("/api/receipts/from-transaction"), Map.of("transactionId", txId, "saveToDocuments", false)))
                .andExpect(status().is4xxClientError()).andExpect(jsonPath("$.code").value("RECEIPT_EXISTS"));

        // ------------------------------------------------------------------ 4. Cancelamento
        String r3id = r3.get("id").asText();
        mvc.perform(json(post("/api/receipts/" + r3id + "/cancel"), Map.of("reason", " "))).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        mvc.perform(json(post("/api/receipts/" + r3id + "/cancel"), Map.of("reason", "Valor lançado em duplicidade"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.canceled").value(true)).andExpect(jsonPath("$.canceledByName").value("Dra. Rita Recibo"));
        mvc.perform(json(post("/api/receipts/" + r3id + "/cancel"), Map.of("reason", "de novo"))).andExpect(jsonPath("$.code").value("ALREADY_CANCELED"));
        mvc.perform(auth(get("/api/receipts/" + r3id + "/pdf"))).andExpect(status().isOk());
        assertThat(body(mvc.perform(auth(get("/api/receipts/pending"))).andReturn())).hasSize(1);
        mvc.perform(auth(get("/api/receipts")).param("clientId", clientId)).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].number").value("REC-" + year + "-0003"));
        mvc.perform(auth(get("/api/audit")).param("action", "RECEIPT_ISSUED")).andExpect(jsonPath("$.items.length()").value(3));

        // ------------------------------------------------------------------ 5. Permissões por tipo
        String secEmail = "sec-" + UUID.randomUUID() + "@escritorio-r.test";
        mvc.perform(json(post("/api/employees"), Map.of("email", secEmail, "displayName", "Sônia Secretária", "role", "SECRETARIA",
                "password", "Senha-Membro-123"))).andExpect(status().isCreated());
        String owner = token;
        token = authCall("accounts:signInWithPassword", secEmail, "Senha-Membro-123");
        // vê apenas os recibos de documentos (sem acesso ao financeiro) e não emite (sem permissão de enviar documentos)
        JsonNode visible = body(mvc.perform(auth(get("/api/receipts"))).andExpect(status().isOk()).andReturn());
        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).get("kind").asText()).isEqualTo("ENTREGA_DOCUMENTOS");
        mvc.perform(auth(get("/api/receipts/" + r1.get("id").asText() + "/pdf"))).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/receipts"), req)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/receipts/pending"))).andExpect(status().isForbidden());
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
