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
        "app.jobs.enabled=true",
        "app.jobs.deadline-alerts-cron=0 0 0 1 1 *",
        "app.jobs.appointment-reminders-ms=86400000",
        "app.jobs.delinquency-cron=0 0 0 1 1 *",
        "app.security.profile-cache-seconds=1"
})
@AutoConfigureMockMvc
class EmulatorEndToEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String PASSWORD = "Senha-Forte-123";

    @Autowired
    MockMvc mvc;

    @Autowired
    br.com.erpadv.services.ReminderJobs jobs;

    @Autowired
    com.google.cloud.firestore.Firestore firestore;

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

        // 7. Etapa 9 — calendário forense, cálculo, prazos, alertas e agenda
        String officeA = me.get("officeId").asText();
        mvc.perform(post("/api/court-calendar").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2030-03-11\",\"description\":\"Feriado municipal\",\"kind\":\"FERIADO\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/deadlines/calculate").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2030-03-08\",\"days\":1,\"countingMode\":\"UTEIS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueDate").value("2030-03-12"));

        java.time.LocalDate today = br.com.erpadv.services.DeadlineService.today();
        JsonNode deadline = body(mvc.perform(post("/api/deadlines").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "caseId", caseId, "description", "Réplica à contestação", "type", "REPLICA",
                                "startDate", today.toString(), "dueDate", today.plusDays(4).toString(),
                                "countingMode", "MANUAL", "responsibleUid", uidA, "priority", "ALTA"))))
                .andExpect(status().isCreated()).andReturn());
        String deadlineId = deadline.get("id").asText();
        assertThat(deadline.get("clientName").asText()).isEqualTo("João da Conceição");
        assertThat(deadline.get("validated").asBoolean()).isFalse();
        mvc.perform(post("/api/deadlines/" + deadlineId + "/validate").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.validated").value(true));
        mvc.perform(get("/api/deadlines").param("view", "PENDENTES").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(deadlineId));
        mvc.perform(get("/api/deadlines/stats").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.upcoming").value(1));

        assertThat(jobs.sendDeadlineAlerts(today)).isGreaterThanOrEqualTo(1);
        assertThat(jobs.sendDeadlineAlerts(today)).isZero(); // idempotente
        long alerts = firestore.collection("offices").document(officeA).collection("notifications")
                .whereEqualTo("recipientUid", uidA).whereEqualTo("type", "DEADLINE_ALERT").get().get().size();
        assertThat(alerts).isEqualTo(1);

        java.time.Instant hearingStart = java.time.Instant.now().plus(java.time.Duration.ofMinutes(30));
        JsonNode appointment = body(mvc.perform(post("/api/calendar/appointments").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "type", "AUDIENCIA", "title", "Audiência de conciliação",
                                "start", hearingStart.toString(), "end", hearingStart.plusSeconds(3600).toString(),
                                "responsibleUids", java.util.List.of(uidA), "caseId", caseId,
                                "reminders", java.util.List.of(60, 1440)))))
                .andExpect(status().isCreated()).andReturn());
        String appointmentId = appointment.get("id").asText();
        mvc.perform(get("/api/calendar/events")
                        .param("from", java.time.Instant.now().minusSeconds(86400).toString())
                        .param("to", java.time.Instant.now().plus(java.time.Duration.ofDays(15)).toString())
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.kind == 'APPOINTMENT')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.kind == 'DEADLINE')]").isNotEmpty());
        assertThat(jobs.sendAppointmentReminders(java.time.Instant.now())).isEqualTo(1);
        assertThat(jobs.sendAppointmentReminders(java.time.Instant.now())).isZero();

        mvc.perform(get("/api/deadlines/" + deadlineId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/deadlines/" + deadlineId + "/complete").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Protocolada\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONCLUIDO"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/calendar/appointments/" + appointmentId).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        // 8. Etapa 10 — financeiro: parcelamento, inadimplência automática, baixa, comprovante, DRE, CSV, escopo pessoal
        JsonNode installments = body(mvc.perform(post("/api/finance/transactions").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "direction", "RECEITA", "category", "HONORARIOS", "description", "Honorários contratuais",
                                "amountCents", 300001, "dueDate", today.minusDays(5).toString(),
                                "clientId", clientId, "installments", 3))))
                .andExpect(status().isCreated()).andReturn());
        assertThat(installments.size()).isEqualTo(3);
        assertThat(installments.get(0).get("amountCents").asLong()).isEqualTo(100001);
        assertThat(installments.get(1).get("amountCents").asLong()).isEqualTo(100000);
        assertThat(installments.get(0).get("situation").asText()).isEqualTo("ATRASADO");
        String firstInstallment = installments.get(0).get("id").asText();
        mvc.perform(get("/api/clients/" + clientId).header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.defaulting").value(true));
        mvc.perform(get("/api/finance/delinquency").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCents").value(100001))
                .andExpect(jsonPath("$.clients[0].clientId").value(clientId));

        mvc.perform(post("/api/finance/transactions/" + firstInstallment + "/pay").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("paidDate", today.toString(), "paidAmountCents", 100001, "paymentMethod", "PIX"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.situation").value("PAGO"));
        mvc.perform(get("/api/clients/" + clientId).header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.defaulting").value(false));

        JsonNode rent = body(mvc.perform(post("/api/finance/transactions").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "direction", "DESPESA", "category", "ALUGUEL", "description", "Aluguel da sede",
                                "amountCents", 450000, "dueDate", today.toString(), "counterparty", "Imobiliária Centro",
                                "recurrenceMonths", 3))))
                .andExpect(status().isCreated()).andReturn());
        assertThat(rent.size()).isEqualTo(3);
        assertThat(rent.get(2).get("amountCents").asLong()).isEqualTo(450000);
        mvc.perform(post("/api/finance/transactions/" + rent.get(0).get("id").asText() + "/pay").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("paidDate", today.toString(), "paidAmountCents", 450000, "paymentMethod", "BOLETO"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/finance/transactions").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "direction", "DESPESA", "category", "HONORARIOS", "description", "Categoria incompatível",
                                "amountCents", 100, "dueDate", today.toString()))))
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(get("/api/finance/summary").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedCents").value(100001))
                .andExpect(jsonPath("$.paidCents").value(450000))
                .andExpect(jsonPath("$.balanceCents").value(100001 - 450000))
                .andExpect(jsonPath("$.feesPendingCents").value(200000))
                .andExpect(jsonPath("$.dre.netRevenueCents").value(100001))
                .andExpect(jsonPath("$.dre.resultCents").value(100001 - 450000));

        byte[] pdf = "%PDF-1.4\n% comprovante de teste\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/finance/transactions/" + firstInstallment + "/receipt")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "../Comprovante PIX.pdf", "application/pdf", pdf))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasReceipt").value(true))
                .andExpect(jsonPath("$.receiptName").value("Comprovante_PIX.pdf"));
        MvcResult download = mvc.perform(get("/api/finance/transactions/" + firstInstallment + "/receipt")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk()).andReturn();
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(pdf);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/finance/transactions/" + firstInstallment + "/receipt")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "virus.pdf", "application/pdf", new byte[]{'M', 'Z', 1, 2}))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"));

        String csv = mvc.perform(get("/api/finance/transactions/export").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("Honorários contratuais").contains("Aluguel da sede");

        JsonNode personal = body(mvc.perform(post("/api/finance/transactions").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "direction", "DESPESA", "category", "OUTRA_DESPESA", "description", "Despesa pessoal do sócio",
                                "amountCents", 9900, "dueDate", today.toString(), "scope", "PESSOAL"))))
                .andExpect(status().isCreated()).andReturn());
        String personalId = personal.get(0).get("id").asText();
        JsonNode officeList = body(mvc.perform(get("/api/finance/transactions").param("view", "TODOS").param("direction", "DESPESA")
                        .header("Authorization", "Bearer " + tokenA)).andExpect(status().isOk()).andReturn());
        officeList.get("items").forEach(item -> assertThat(item.get("id").asText()).isNotEqualTo(personalId));
        mvc.perform(get("/api/finance/transactions").param("view", "TODOS").param("scope", "PESSOAL")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.items[0].id").value(personalId));
        mvc.perform(get("/api/finance/transactions/" + firstInstallment).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/finance/dashboard").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("ESCRITORIO"));

        // 9. Convite de funcionário (estagiário) e bloqueio
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
