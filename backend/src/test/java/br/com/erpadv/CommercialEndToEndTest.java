package br.com.erpadv;

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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 6 (comercialização): avaliação gratuita, configurações e logo, módulos por plano e por escritório, unidades com filtro
 * no dashboard, sistemas dos tribunais, planos e assinatura pelo painel master, cobrança manual (emissão, atraso, suspensão,
 * pagamento e reativação), suporte, monitoramento e permissões. Contra o Firebase Emulator Suite.
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
        "app.platform.master-emails=master-com@plataforma.test"
})
@AutoConfigureMockMvc
class CommercialEndToEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    MockMvc mvc;

    private String token;

    @Test
    void comercializacao() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("America/Sao_Paulo"));
        String email = "com-" + UUID.randomUUID() + "@escritorio-c.test";
        token = authCall("accounts:signUp", email, "Senha-Forte-123");
        mvc.perform(json(post("/api/onboarding/office"), Map.of("name", "Escritório Comercial", "responsibleName", "Dr. Caio Comercial",
                "oabNumber", "PE 3434"))).andExpect(status().isCreated());
        token = authCall("accounts:signInWithPassword", email, "Senha-Forte-123");
        String owner = token;
        JsonNode me = body(mvc.perform(auth(get("/api/auth/me"))).andReturn());
        String ownerUid = me.get("uid").asText();
        String officeId = me.get("officeId").asText();

        // ------------------------------------------------------------------ 1. Avaliação gratuita com todos os módulos
        JsonNode settings = body(mvc.perform(auth(get("/api/office"))).andExpect(status().isOk()).andReturn());
        assertThat(settings.get("subscriptionStatus").asText()).isEqualTo("TRIAL");
        assertThat(settings.get("planCode").asText()).isEqualTo("ENTERPRISE");
        assertThat(settings.get("trialEndsAt").asText()).isEqualTo(today.plusDays(14).toString());
        settings.get("modules").forEach(m -> assertThat(m.get("enabled").asBoolean()).as(m.toString()).isTrue());
        assertThat(me.get("permissions").toString()).contains("CRM_USE", "FINANCE_READ");

        // ------------------------------------------------------------------ 2. Dados, logo, uso e primeiros passos
        Map<String, Object> data = new HashMap<>();
        data.put("name", "Comercial Advogados Associados");
        data.put("cnpj", "11.222.333/0001-81");
        data.put("email", "contato@comercial.test");
        data.put("phone", "(81) 3333-4444");
        data.put("oabRegistration", "PE 999");
        data.put("address", Map.of("zipCode", "50000-000", "street", "Av. Boa Viagem", "number", "100", "city", "Recife", "state", "PE"));
        mvc.perform(json(put("/api/office"), data)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Comercial Advogados Associados"))
                .andExpect(jsonPath("$.address.city").value("Recife"));
        mvc.perform(auth(multipart("/api/office/logo").file(new MockMultipartFile("file", "logo.txt", "text/plain", "não é imagem".getBytes()))))
                .andExpect(status().is4xxClientError());
        mvc.perform(auth(multipart("/api/office/logo").file(new MockMultipartFile("file", "logo.png", "image/png", png()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasLogo").value(true));
        MvcResult logo = mvc.perform(auth(get("/api/office/logo"))).andExpect(status().isOk()).andReturn();
        assertThat(logo.getResponse().getContentType()).isEqualTo("image/png");
        JsonNode onboarding = body(mvc.perform(auth(get("/api/office/onboarding"))).andExpect(status().isOk()).andReturn());
        assertThat(step(onboarding, "DADOS")).isTrue();
        assertThat(step(onboarding, "LOGO")).isTrue();
        assertThat(step(onboarding, "PROCESSO")).isFalse();
        mvc.perform(auth(post("/api/office/onboarding/dismiss"))).andExpect(jsonPath("$.dismissed").value(true));
        mvc.perform(auth(get("/api/office/usage"))).andExpect(status().isOk()).andExpect(jsonPath("$.users").value(1))
                .andExpect(jsonPath("$.recentActivity").isArray());

        // ------------------------------------------------------------------ 3. Módulos desligados pelo escritório
        mvc.perform(json(put("/api/office/modules"), Map.of("enabled", List.of("FINANCEIRO", "CONTRATOS", "QUADROS", "RELATORIOS", "LGPD"))))
                .andExpect(status().isOk());
        assertThat(body(mvc.perform(auth(get("/api/auth/me"))).andReturn()).get("permissions").toString()).doesNotContain("CRM_USE");
        mvc.perform(auth(get("/api/crm/leads"))).andExpect(status().isForbidden());
        mvc.perform(json(put("/api/office/modules"), Map.of("enabled", List.of("FINANCEIRO", "CONTRATOS", "CRM", "QUADROS", "RELATORIOS", "LGPD"))))
                .andExpect(status().isOk());
        mvc.perform(auth(get("/api/crm/leads"))).andExpect(status().isOk());

        // ------------------------------------------------------------------ 4. Unidades, processos e filtro do dashboard
        String unitId = body(mvc.perform(json(post("/api/units"), Map.of("name", "Filial Caruaru", "active", true,
                "address", Map.of("city", "Caruaru", "state", "PE")))).andExpect(status().isCreated()).andReturn()).get("id").asText();
        mvc.perform(json(put("/api/units/" + unitId + "/members"), Map.of("uids", List.of(ownerUid)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.members").value(1)).andExpect(jsonPath("$.memberUids[0]").value(ownerUid));
        String clientId = body(mvc.perform(json(post("/api/clients"), Map.of("type", "PF", "name", "Clara Cliente", "document", "529.982.247-25")))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        mvc.perform(json(post("/api/cases"), Map.of("clientId", clientId, "area", "CIVEL", "status", "EM_ANDAMENTO",
                "responsibleUid", ownerUid, "unitId", "nao-existe"))).andExpect(status().is4xxClientError());
        JsonNode legalCase = body(mvc.perform(json(post("/api/cases"), Map.of("clientId", clientId, "area", "CIVEL", "status", "EM_ANDAMENTO",
                "responsibleUid", ownerUid, "unitId", unitId))).andExpect(status().isCreated()).andReturn());
        assertThat(legalCase.get("unitName").asText()).isEqualTo("Filial Caruaru");
        mvc.perform(json(post("/api/cases"), Map.of("clientId", clientId, "area", "TRABALHISTA", "status", "EM_ANDAMENTO",
                "responsibleUid", ownerUid))).andExpect(status().isCreated());
        mvc.perform(auth(get("/api/cases")).param("unitId", unitId)).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(auth(get("/api/cases"))).andExpect(jsonPath("$.items.length()").value(2));
        String from = today.withDayOfMonth(1).toString();
        String to = today.toString();
        mvc.perform(auth(get("/api/dashboard")).param("from", from).param("to", to).param("unitId", unitId)).andExpect(status().isOk());
        mvc.perform(auth(get("/api/units"))).andExpect(jsonPath("$[0].cases").value(1));
        mvc.perform(auth(delete("/api/units/" + unitId))).andExpect(status().is4xxClientError()).andExpect(jsonPath("$.code").value("UNIT_IN_USE"));

        // ------------------------------------------------------------------ 5. Sistemas dos tribunais (somente endereços https)
        mvc.perform(json(put("/api/office/systems"), List.of(Map.of("name", "PJe falso", "url", "http://pje.exemplo.test/login"))))
                .andExpect(status().is4xxClientError());
        mvc.perform(json(put("/api/office/systems"), List.of(Map.of("name", "PJe TJPE 1º grau", "url", "https://pje.cloud.tjpe.jus.br/1g/login.seam",
                "court", "tjpe")))).andExpect(status().isOk()).andExpect(jsonPath("$[0].court").value("TJPE"));

        // ------------------------------------------------------------------ 6. Equipe sem administração
        String secEmail = "sec-" + UUID.randomUUID() + "@escritorio-c.test";
        mvc.perform(json(post("/api/employees"), Map.of("email", secEmail, "displayName", "Sara Secretária", "role", "SECRETARIA",
                "password", "Senha-Membro-123"))).andExpect(status().isCreated());
        token = authCall("accounts:signInWithPassword", secEmail, "Senha-Membro-123");
        String secretary = token;
        mvc.perform(auth(get("/api/office"))).andExpect(status().isOk());
        mvc.perform(json(put("/api/office"), data)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/subscription"))).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/units"), Map.of("name", "Outra", "active", true))).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/platform/monitoring"))).andExpect(status().isForbidden());
        token = owner;
        mvc.perform(auth(get("/api/platform/plans"))).andExpect(status().isForbidden());

        // ------------------------------------------------------------------ 7. Painel master: plano com preço e assinatura
        String master = signUpOrIn("master-com@plataforma.test", "Master-Senha-2026");
        token = master;
        JsonNode plans = body(mvc.perform(auth(get("/api/platform/plans"))).andExpect(status().isOk()).andReturn());
        JsonNode pro = null;
        for (JsonNode p : plans) {
            if ("PROFISSIONAL".equals(p.get("code").asText())) {
                pro = p;
            }
        }
        assertThat(pro).isNotNull();
        List<String> proModules = new ArrayList<>();
        pro.get("modules").forEach(m -> proModules.add(m.asText()));
        assertThat(proModules).doesNotContain("CRM");
        Map<String, Object> plan = new HashMap<>();
        plan.put("code", "PROFISSIONAL");
        plan.put("name", "Profissional");
        plan.put("monthlyPriceCents", 19900);
        plan.put("yearlyPriceCents", 199000);
        plan.put("maxUsers", 10);
        plan.put("maxStorageMb", 10240);
        plan.put("modules", proModules);
        plan.put("trialDays", 14);
        plan.put("active", true);
        plan.put("highlight", true);
        plan.put("sortOrder", 2);
        mvc.perform(json(put("/api/platform/plans"), plan)).andExpect(status().isOk()).andExpect(jsonPath("$.monthlyPriceCents").value(19900));
        mvc.perform(json(put("/api/platform/plans"), Map.of("code", "PROFISSIONAL", "name", "x", "modules", List.of("NAO_EXISTE"))))
                .andExpect(jsonPath("$.code").value("INVALID_MODULE"));
        // avaliação vencida ontem e cobrança atrasada há 30 dias: a rotina deve ativar, faturar, marcar atraso e suspender
        Map<String, Object> sub = new HashMap<>();
        sub.put("planCode", "PROFISSIONAL");
        sub.put("billingCycle", "MENSAL");
        sub.put("subscriptionStatus", "TRIAL");
        sub.put("trialEndsAt", today.minusDays(1).toString());
        sub.put("nextInvoiceDate", today.minusDays(30).toString());
        sub.put("graceDays", 10);
        sub.put("billingEmail", "financeiro@comercial.test");
        mvc.perform(json(put("/api/platform/offices/" + officeId + "/subscription"), sub)).andExpect(status().isOk())
                .andExpect(jsonPath("$.planCode").value("PROFISSIONAL")).andExpect(jsonPath("$.priceCents").value(19900))
                .andExpect(jsonPath("$.maxUsers").value(10));
        token = owner;
        // o CRM não faz parte do plano Profissional
        assertThat(body(mvc.perform(auth(get("/api/auth/me"))).andReturn()).get("permissions").toString()).doesNotContain("CRM_USE");
        mvc.perform(auth(get("/api/crm/leads"))).andExpect(status().isForbidden());
        JsonNode crm = null;
        for (JsonNode m : body(mvc.perform(auth(get("/api/office"))).andReturn()).get("modules")) {
            if ("CRM".equals(m.get("code").asText())) {
                crm = m;
            }
        }
        assertThat(crm.get("inPlan").asBoolean()).isFalse();

        // ------------------------------------------------------------------ 8. Rotina de cobrança: atraso e suspensão automática
        token = master;
        JsonNode run = body(mvc.perform(auth(post("/api/platform/billing/run"))).andExpect(status().isOk()).andReturn());
        assertThat(run.get("actions").asInt()).isGreaterThanOrEqualTo(4);
        JsonNode invoices = body(mvc.perform(auth(get("/api/platform/invoices")).param("officeId", officeId)).andReturn());
        assertThat(invoices).hasSize(1);
        JsonNode invoice = invoices.get(0);
        assertThat(invoice.get("status").asText()).isEqualTo("VENCIDA");
        assertThat(invoice.get("amountCents").asLong()).isEqualTo(19900);
        assertThat(invoice.get("periodStart").asText()).isEqualTo(today.minusDays(30).toString());
        assertThat(invoice.get("number").asText()).startsWith("FAT-");
        String invoiceId = invoice.get("id").asText();
        mvc.perform(auth(get("/api/platform/offices/" + officeId + "/subscription")))
                .andExpect(jsonPath("$.status").value("SUSPENSA")).andExpect(jsonPath("$.suspended").value(true));

        token = owner;
        mvc.perform(auth(get("/api/clients"))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OFFICE_SUSPENDED_BILLING"));
        mvc.perform(auth(get("/api/auth/me"))).andExpect(status().isOk());
        JsonNode overview = body(mvc.perform(auth(get("/api/subscription"))).andExpect(status().isOk()).andReturn());
        assertThat(overview.get("subscription").get("status").asText()).isEqualTo("SUSPENSA");
        assertThat(overview.get("invoices")).hasSize(1);
        MvcResult pdf = mvc.perform(auth(get("/api/subscription/invoices/" + invoiceId + "/pdf"))).andExpect(status().isOk()).andReturn();
        assertThat(new String(pdf.getResponse().getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        token = secretary;
        mvc.perform(auth(get("/api/subscription"))).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/cases"))).andExpect(jsonPath("$.code").value("OFFICE_SUSPENDED_BILLING"));

        // ------------------------------------------------------------------ 9. Suporte durante a suspensão
        token = owner;
        JsonNode ticket = body(mvc.perform(json(post("/api/support/tickets"), Map.of("subject", "Pagamento da fatura", "category", "FINANCEIRO",
                "priority", "ALTA", "message", "Pagamos via Pix hoje, segue a confirmação."))).andExpect(status().isCreated()).andReturn());
        assertThat(ticket.get("number").asText()).startsWith("SUP-");
        String ticketId = ticket.get("id").asText();
        mvc.perform(json(post("/api/support/tickets"), Map.of("subject", " ", "message", "x"))).andExpect(jsonPath("$.code").value("INVALID_TICKET"));
        token = master;
        JsonNode all = body(mvc.perform(auth(get("/api/platform/support/tickets"))).andExpect(status().isOk()).andReturn());
        assertThat(all.toString()).contains(ticketId);
        mvc.perform(json(post("/api/platform/support/tickets/" + ticketId + "/reply"), Map.of("message", "Pagamento conferido, obrigado.")))
                .andExpect(jsonPath("$.status").value("AGUARDANDO_ESCRITORIO")).andExpect(jsonPath("$.awaitingPlatform").value(false));

        // ------------------------------------------------------------------ 10. Pagamento manual e reativação
        mvc.perform(json(post("/api/platform/invoices/" + invoiceId + "/pay"), Map.of("paidDate", today.plusDays(1).toString())))
                .andExpect(jsonPath("$.code").value("INVALID_DATE"));
        mvc.perform(json(post("/api/platform/invoices/" + invoiceId + "/pay"), Map.of("paidDate", today.toString(), "method", "Pix")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAGA")).andExpect(jsonPath("$.paidAmountCents").value(19900));
        mvc.perform(json(post("/api/platform/invoices/" + invoiceId + "/cancel"), Map.of("reason", "x")))
                .andExpect(jsonPath("$.code").value("INVOICE_CLOSED"));
        mvc.perform(auth(get("/api/platform/offices/" + officeId + "/subscription")))
                .andExpect(jsonPath("$.status").value("ATIVA")).andExpect(jsonPath("$.suspended").value(false));
        token = owner;
        mvc.perform(auth(get("/api/clients"))).andExpect(status().isOk());
        JsonNode thread = body(mvc.perform(auth(get("/api/support/tickets/" + ticketId))).andExpect(status().isOk()).andReturn());
        assertThat(thread.get("messages")).hasSize(2);
        assertThat(thread.get("messages").get(1).get("fromPlatform").asBoolean()).isTrue();
        mvc.perform(json(post("/api/support/tickets/" + ticketId + "/reply"), Map.of("status", "RESOLVIDO")))
                .andExpect(jsonPath("$.status").value("RESOLVIDO"));
        JsonNode change = body(mvc.perform(json(post("/api/subscription/change-request"), Map.of("planCode", "ENTERPRISE", "billingCycle", "ANUAL")))
                .andExpect(status().isCreated()).andReturn());
        assertThat(change.get("category").asText()).isEqualTo("PLANO");
        assertThat(body(mvc.perform(auth(get("/api/support/tickets"))).andReturn())).hasSize(2);

        // ------------------------------------------------------------------ 11. Monitoramento e faturamento sob demanda
        token = master;
        JsonNode metrics = body(mvc.perform(auth(get("/api/platform/monitoring"))).andExpect(status().isOk()).andReturn());
        assertThat(metrics.get("mrrCents").asLong()).isGreaterThanOrEqualTo(19900);
        assertThat(metrics.get("offices").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(metrics.get("health").asText()).isEqualTo("UP");
        assertThat(metrics.get("ticketsAwaiting").asLong()).isGreaterThanOrEqualTo(1);
        JsonNode issued = body(mvc.perform(auth(post("/api/platform/offices/" + officeId + "/invoices"))).andExpect(status().isCreated()).andReturn());
        assertThat(issued.get("status").asText()).isEqualTo("ABERTA");
        mvc.perform(json(post("/api/platform/invoices/" + issued.get("id").asText() + "/cancel"), Map.of("reason", "Emitida para teste")))
                .andExpect(jsonPath("$.status").value("CANCELADA"));
        mvc.perform(json(put("/api/platform/billing/settings"), Map.of("issuerName", "ERPAdv Tecnologia", "pixKey", "financeiro@erpadv.test")))
                .andExpect(status().isOk());
        token = owner;
        mvc.perform(auth(get("/api/subscription"))).andExpect(jsonPath("$.payment.pixKey").value("financeiro@erpadv.test"));
        mvc.perform(auth(get("/api/audit")).param("action", "SUBSCRIPTION_UPDATED")).andExpect(jsonPath("$.items.length()").value(1));
    }

    private static boolean step(JsonNode onboarding, String key) {
        for (JsonNode s : onboarding.get("steps")) {
            if (key.equals(s.get("key").asText())) {
                return s.get("done").asBoolean();
            }
        }
        throw new AssertionError("Etapa ausente: " + key);
    }

    private static byte[] png() throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        img.setRGB(1, 1, 0xC5A46D);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
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

    private static String signUpOrIn(String email, String password) throws Exception {
        try {
            return authCall("accounts:signUp", email, password);
        } catch (AssertionError e) {
            return authCall("accounts:signInWithPassword", email, password);
        }
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
