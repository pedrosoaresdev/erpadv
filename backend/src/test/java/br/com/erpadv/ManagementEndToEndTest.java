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
 * Fase 3 — gestão empresarial: centros de custo, contas bancárias (saldo e extrato), recibo, cobranças, honorários,
 * comissões, renovação de contratos, etapas do CRM com propostas e central de relatórios — contra o Emulator Suite.
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
class ManagementEndToEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String PASSWORD = "Senha-Forte-123";

    @Autowired
    MockMvc mvc;

    private String token;

    @Test
    void gestaoEmpresarialCompleta() throws Exception {
        String email = "gestao-" + UUID.randomUUID() + "@escritorio-g.test";
        token = authCall("accounts:signUp", email);
        mvc.perform(json(post("/api/onboarding/office"), Map.of("name", "Escritório Gestão", "responsibleName", "Dra. Gisele Gestão",
                "oabNumber", "PE 5555"))).andExpect(status().isCreated());
        token = authCall("accounts:signInWithPassword", email);
        String ownerUid = body(mvc.perform(auth(get("/api/auth/me"))).andReturn()).get("uid").asText();
        LocalDate today = LocalDate.now();
        String clientId = body(mvc.perform(json(post("/api/clients"), Map.of("type", "PF", "name", "Bruno Cliente", "document", "529.982.247-25",
                "whatsapp", "(81) 99999-1234", "email", "bruno@cliente.test"))).andExpect(status().isCreated()).andReturn()).get("id").asText();

        // ------------------------------------------------------------------ 1. Centros de custo e contas
        String center = body(mvc.perform(json(post("/api/finance/cost-centers"), Map.of("name", "Contencioso cível", "code", "CIV", "active", true)))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        mvc.perform(json(post("/api/finance/cost-centers"), Map.of("name", "contencioso CÍVEL", "active", true)))
                .andExpect(status().is4xxClientError()).andExpect(jsonPath("$.code").value("DUPLICATE_NAME"));
        Map<String, Object> acc = new HashMap<>(Map.of("name", "Conta principal", "bankName", "Banco Exemplo", "accountNumber", "123456-7",
                "kind", "CORRENTE", "initialBalanceCents", 100000, "initialBalanceDate", today.minusDays(30).toString(), "active", true));
        String account = body(mvc.perform(json(post("/api/finance/bank-accounts"), acc)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountMasked").value("•••56-7")).andReturn()).get("id").asText();

        Map<String, Object> fee = new HashMap<>(Map.of("direction", "RECEITA", "category", "HONORARIOS", "description", "Honorários iniciais",
                "amountCents", 300000, "dueDate", today.minusDays(2).toString(), "clientId", clientId, "installments", 3, "costCenterId", center));
        JsonNode parcels = body(mvc.perform(json(post("/api/finance/transactions"), fee)).andExpect(status().isCreated()).andReturn());
        assertThat(parcels.get(0).get("costCenterName").asText()).isEqualTo("Contencioso cível");
        String firstId = parcels.get(0).get("id").asText();
        mvc.perform(json(post("/api/finance/transactions/" + firstId + "/pay"), Map.of("paidDate", today.toString(), "paidAmountCents", 100000,
                "paymentMethod", "PIX", "bankAccountId", account))).andExpect(status().isOk()).andExpect(jsonPath("$.bankAccountName").value("Conta principal"));
        MvcResult recibo = mvc.perform(auth(get("/api/finance/transactions/" + firstId + "/recibo"))).andExpect(status().isOk()).andReturn();
        assertThat(new String(recibo.getResponse().getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        mvc.perform(auth(get("/api/finance/transactions/" + firstId))).andExpect(jsonPath("$.receiptNumber").exists());
        mvc.perform(auth(get("/api/finance/transactions/" + parcels.get(1).get("id").asText() + "/recibo"))).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("RECEIPT_UNAVAILABLE"));

        String cost = body(mvc.perform(json(post("/api/finance/transactions"), Map.of("direction", "DESPESA", "category", "DESPESAS_PROCESSUAIS",
                "description", "Cópias e diligência", "amountCents", 15000, "dueDate", today.toString(), "costCenterId", center)))
                .andExpect(status().isCreated()).andReturn()).get(0).get("id").asText();
        mvc.perform(json(post("/api/finance/transactions/" + cost + "/pay"), Map.of("paidDate", today.toString(), "paidAmountCents", 15000,
                "paymentMethod", "PIX", "bankAccountId", account))).andExpect(status().isOk());
        mvc.perform(auth(get("/api/finance/bank-accounts"))).andExpect(jsonPath("$[0].balanceCents").value(100000 + 100000 - 15000))
                .andExpect(jsonPath("$[0].movements").value(2));
        mvc.perform(auth(get("/api/finance/bank-accounts/" + account + "/statement")).param("from", today.minusDays(1).toString())
                        .param("to", today.toString()))
                .andExpect(jsonPath("$.openingCents").value(100000)).andExpect(jsonPath("$.closingCents").value(185000))
                .andExpect(jsonPath("$.lines.length()").value(2));
        mvc.perform(auth(get("/api/finance/transactions")).param("view", "TODOS").param("costCenterId", center))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(4));
        mvc.perform(auth(delete("/api/finance/cost-centers/" + center))).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("COST_CENTER_IN_USE"));

        // ------------------------------------------------------------------ 2. Cobranças
        String overdue = body(mvc.perform(json(post("/api/finance/transactions"), Map.of("direction", "RECEITA", "category", "HONORARIOS_SUCUMBENCIA",
                "description", "Sucumbência — acordo", "amountCents", 50000, "dueDate", today.minusDays(10).toString(), "clientId", clientId)))
                .andExpect(status().isCreated()).andReturn()).get(0).get("id").asText();
        JsonNode draft = body(mvc.perform(auth(get("/api/finance/collections/draft")).param("clientId", clientId)).andExpect(status().isOk()).andReturn());
        assertThat(draft.get("titles").asInt()).isEqualTo(1);
        assertThat(draft.get("message").asText()).contains("Bruno").contains("Sucumbência — acordo");
        assertThat(draft.get("whatsappUrl").asText()).startsWith("https://wa.me/5581999991234?text=");
        mvc.perform(json(post("/api/finance/collections"), Map.of("transactionIds", List.of(overdue), "channel", "WHATSAPP", "outcome", "PROMESSA_PAGAMENTO")))
                .andExpect(status().is4xxClientError()).andExpect(jsonPath("$.code").value("PROMISE_DATE"));
        mvc.perform(json(post("/api/finance/collections"), Map.of("transactionIds", List.of(overdue), "channel", "WHATSAPP", "outcome", "PROMESSA_PAGAMENTO",
                "promisedDate", today.plusDays(5).toString()))).andExpect(status().isCreated()).andExpect(jsonPath("$.amountCents").value(50000));
        mvc.perform(auth(get("/api/finance/collections")).param("clientId", clientId)).andExpect(jsonPath("$.length()").value(1));

        // ------------------------------------------------------------------ 3. Honorários e comissões
        JsonNode summary = body(mvc.perform(auth(get("/api/fees/summary")).param("from", today.minusMonths(1).toString())
                .param("to", today.plusMonths(3).toString()).param("groupBy", "CLIENT")).andExpect(status().isOk()).andReturn());
        assertThat(summary.get("totals").get("contractedCents").asLong()).isEqualTo(350000);
        assertThat(summary.get("totals").get("receivedCents").asLong()).isEqualTo(100000);
        assertThat(summary.get("groups").get(0).get("label").asText()).isEqualTo("Bruno Cliente");
        mvc.perform(auth(get("/api/fees/items")).param("clientId", clientId)).andExpect(jsonPath("$.length()").value(4));

        mvc.perform(json(post("/api/fees/commission-rules"), Map.of("beneficiaryUid", ownerUid, "percentBp", 1000, "active", true)))
                .andExpect(status().isCreated());
        JsonNode statements = body(mvc.perform(auth(get("/api/fees/commissions")).param("from", today.minusDays(1).toString())
                .param("to", today.toString())).andExpect(status().isOk()).andReturn());
        assertThat(statements.get(0).get("commissionCents").asLong()).isEqualTo(10000);
        mvc.perform(json(post("/api/fees/commissions/payouts"), Map.of("beneficiaryUid", ownerUid, "from", today.minusDays(1).toString(),
                "to", today.toString(), "dueDate", today.toString()))).andExpect(status().isCreated()).andExpect(jsonPath("$.amountCents").value(10000));
        mvc.perform(json(post("/api/fees/commissions/payouts"), Map.of("beneficiaryUid", ownerUid, "from", today.minusDays(1).toString(),
                "to", today.toString(), "dueDate", today.toString()))).andExpect(status().is4xxClientError()).andExpect(jsonPath("$.code").value("NOTHING_TO_PAY"));
        mvc.perform(auth(get("/api/fees/commissions/mine")).param("from", today.minusDays(1).toString()).param("to", today.toString()))
                .andExpect(jsonPath("$[0].pendingCents").value(0)).andExpect(jsonPath("$[0].paidOutCents").value(10000));
        mvc.perform(auth(get("/api/finance/transactions")).param("direction", "DESPESA").param("category", "COMISSOES"))
                .andExpect(jsonPath("$.items.length()").value(1));

        // ------------------------------------------------------------------ 4. Renovação de contrato
        Map<String, Object> contract = new HashMap<>(Map.of("title", "Assessoria mensal", "type", "PRESTACAO_SERVICOS", "clientId", clientId,
                "responsibleUid", ownerUid, "object", "Consultoria jurídica preventiva", "startDate", today.toString(), "feeModel", "MENSAL",
                "monthlyCents", 200000, "months", 3, "firstDueDate", today.plusDays(5).toString()));
        contract.put("endDate", today.plusMonths(3).toString());
        String contractId = body(mvc.perform(json(post("/api/contracts"), contract)).andExpect(status().isCreated()).andReturn()).get("id").asText();
        mvc.perform(auth(post("/api/contracts/" + contractId + "/activate"))).andExpect(status().isOk());
        mvc.perform(json(post("/api/contracts/" + contractId + "/renew"), Map.of("newEndDate", today.plusMonths(1).toString())))
                .andExpect(status().is4xxClientError()).andExpect(jsonPath("$.code").value("INVALID_RENEWAL"));
        mvc.perform(json(post("/api/contracts/" + contractId + "/renew"), Map.of("newEndDate", today.plusMonths(5).toString(), "monthsAdded", 2,
                        "monthlyCents", 210000, "note", "Reajuste anual")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.months").value(5)).andExpect(jsonPath("$.monthlyCents").value(210000))
                .andExpect(jsonPath("$.renewals.length()").value(1));
        mvc.perform(auth(get("/api/finance/transactions")).param("contractId", contractId)).andExpect(jsonPath("$.items.length()").value(5));

        // ------------------------------------------------------------------ 5. CRM: etapas da especificação e propostas
        String leadId = body(mvc.perform(json(post("/api/crm/leads"), Map.of("name", "Carla Prospect", "type", "PF", "source", "INSTAGRAM",
                "estimatedValueCents", 800000))).andExpect(status().isCreated()).andReturn()).get("id").asText();
        mvc.perform(json(post("/api/crm/leads/" + leadId + "/stage"), Map.of("stage", "REUNIAO_AGENDADA")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stageLabel").value("Reunião agendada"));
        String proposal = body(mvc.perform(json(post("/api/crm/leads/" + leadId + "/proposals"), Map.of("title", "Ação trabalhista",
                "scope", "Ajuizamento e acompanhamento\nAudiências", "feeTerms", "30% de êxito", "valueCents", 500000,
                "validUntil", today.plusDays(15).toString()))).andExpect(status().isCreated()).andReturn()).get("id").asText();
        MvcResult pdf = mvc.perform(auth(get("/api/crm/proposals/" + proposal + "/pdf"))).andExpect(status().isOk()).andReturn();
        assertThat(new String(pdf.getResponse().getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        mvc.perform(auth(post("/api/crm/proposals/" + proposal + "/send"))).andExpect(jsonPath("$.status").value("ENVIADA"));
        mvc.perform(auth(get("/api/crm/leads/" + leadId))).andExpect(jsonPath("$.lead.stage").value("PROPOSTA"));
        mvc.perform(json(post("/api/crm/proposals/" + proposal + "/decision"), Map.of("status", "ACEITA", "note", "Assinará amanhã")))
                .andExpect(jsonPath("$.status").value("ACEITA"));
        mvc.perform(auth(get("/api/crm/leads/" + leadId))).andExpect(jsonPath("$.lead.stage").value("CONTRATO_ENVIADO"));

        // ------------------------------------------------------------------ 6. Central de relatórios (todos geram)
        JsonNode catalog = body(mvc.perform(auth(get("/api/reports"))).andExpect(status().isOk()).andReturn());
        assertThat(catalog.size()).isEqualTo(19);
        for (JsonNode r : catalog) {
            MvcResult file = mvc.perform(auth(get("/api/reports/" + r.get("key").asText())).param("from", today.minusMonths(2).toString())
                    .param("to", today.toString())).andExpect(status().isOk()).andReturn();
            assertThat(new String(file.getResponse().getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).as(r.get("key").asText()).isEqualTo("%PDF-");
        }
        MvcResult xlsx = mvc.perform(auth(get("/api/reports/fluxo-caixa")).param("format", "xlsx")).andExpect(status().isOk()).andReturn();
        assertThat(xlsx.getResponse().getContentAsByteArray()[0]).isEqualTo((byte) 'P');
        mvc.perform(auth(get("/api/reports/nao-existe"))).andExpect(status().isNotFound());
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
