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
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 2 — operação jurídica: prazos (origem, publicação, comprovante, histórico, relatório), agenda (recorrência,
 * convidados, confirmação, status, ICS, audiências, relatório), tarefas, central documental (pastas, etiquetas,
 * favoritos, duplicidade, mover/copiar, lixeira e eliminação) e Editor Jurídico — contra o Firebase Emulator Suite.
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
class OperationsEndToEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String PASSWORD = "Senha-Forte-123";

    @Autowired
    MockMvc mvc;

    private String token;

    @Test
    void operacaoJuridicaCompleta() throws Exception {
        String email = "operacao-" + UUID.randomUUID() + "@escritorio-o.test";
        token = authCall("accounts:signUp", email);
        mvc.perform(json(post("/api/onboarding/office"), Map.of("name", "Escritório Operação", "responsibleName", "Dr. Otávio Operação",
                "oabNumber", "PE 7777"))).andExpect(status().isCreated());
        token = authCall("accounts:signInWithPassword", email);
        String ownerUid = body(mvc.perform(auth(get("/api/auth/me"))).andReturn()).get("uid").asText();

        Map<String, Object> clientReq = new HashMap<>(Map.of("type", "PF", "name", "Maria Operação", "document", "529.982.247-25",
                "email", "maria@cliente.test", "profession", "Professora", "maritalStatus", "Casada", "nationality", "Brasileira"));
        String clientId = body(mvc.perform(json(post("/api/clients"), clientReq)).andExpect(status().isCreated()).andReturn()).get("id").asText();
        Map<String, Object> caseReq = new HashMap<>(Map.of("clientId", clientId, "area", "CIVEL", "status", "EM_ANDAMENTO",
                "responsibleUid", ownerUid, "court", "TJPE", "courtDivision", "2ª Vara Cível", "district", "Recife",
                "opposingParty", "Banco Exemplo S.A."));
        caseReq.put("confidential", false);
        String caseId = body(mvc.perform(json(post("/api/cases"), caseReq)).andExpect(status().isCreated()).andReturn()).get("id").asText();

        // ------------------------------------------------------------------ 1. Prazos
        LocalDate availability = LocalDate.now().plusDays(3);
        JsonNode pub = body(mvc.perform(auth(get("/api/deadlines/publication-date")).param("availability", availability.toString())
                .param("court", "TJPE")).andExpect(status().isOk()).andReturn());
        LocalDate publication = LocalDate.parse(pub.get("publicationDate").asText());
        assertThat(publication).isAfter(availability);
        Map<String, Object> dl = new HashMap<>(Map.of("caseId", caseId, "description", "Contestação — prazo da citação", "type", "CONTESTACAO",
                "startDate", pub.get("suggestedStartDate").asText(), "days", 15, "countingMode", "UTEIS", "court", "TJPE",
                "responsibleUid", ownerUid, "priority", "ALTA"));
        dl.put("origin", "PUBLICACAO_DJE");
        dl.put("availabilityDate", availability.toString());
        dl.put("publicationDate", publication.toString());
        JsonNode deadline = body(mvc.perform(json(post("/api/deadlines"), dl)).andExpect(status().isCreated()).andReturn());
        String deadlineId = deadline.get("id").asText();
        assertThat(deadline.get("originLabel").asText()).isNotBlank();
        assertThat(deadline.get("publicationDate").asText()).isEqualTo(publication.toString());
        mvc.perform(auth(get("/api/deadlines")).param("clientId", clientId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(deadlineId));

        byte[] receipt = "%PDF-1.4\n% protocolo da contestação\n".getBytes(StandardCharsets.US_ASCII);
        mvc.perform(upload(multipart("/api/deadlines/" + deadlineId + "/receipt"), new MockMultipartFile("file", "protocolo.pdf", "application/pdf", receipt)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.receiptName").value("protocolo.pdf"));
        MvcResult receiptDownload = mvc.perform(auth(get("/api/deadlines/" + deadlineId + "/receipt"))).andExpect(status().isOk()).andReturn();
        assertThat(receiptDownload.getResponse().getContentAsByteArray()).isEqualTo(receipt);
        mvc.perform(upload(multipart("/api/deadlines/" + deadlineId + "/receipt"), new MockMultipartFile("file", "x.pdf", "application/pdf",
                new byte[]{'M', 'Z', 0, 1}))).andExpect(status().is4xxClientError());
        JsonNode history = body(mvc.perform(auth(get("/api/deadlines/" + deadlineId + "/history"))).andExpect(status().isOk()).andReturn());
        assertThat(history.get("items").size()).isGreaterThanOrEqualTo(2);
        MvcResult dlReport = mvc.perform(auth(get("/api/deadlines/report")).param("from", LocalDate.now().toString())
                .param("to", LocalDate.now().plusDays(90).toString()).param("format", "pdf")).andExpect(status().isOk()).andReturn();
        assertThat(new String(dlReport.getResponse().getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        // ------------------------------------------------------------------ 2. Agenda e audiências
        Instant start = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        Map<String, Object> appt = new HashMap<>(Map.of("type", "AUDIENCIA", "title", "Audiência de conciliação", "start", start.toString(),
                "end", start.plus(1, ChronoUnit.HOURS).toString(), "responsibleUids", List.of(ownerUid), "caseId", caseId,
                "location", "Fórum do Recife — sala 3", "meetingUrl", "https://meet.example.test/sala-3"));
        appt.put("guests", List.of(Map.of("name", "Maria Operação", "email", "maria@cliente.test")));
        appt.put("recurrence", "SEMANAL");
        appt.put("occurrences", 3);
        JsonNode created = body(mvc.perform(json(post("/api/calendar/appointments"), appt)).andExpect(status().isCreated()).andReturn());
        String apptId = created.get("id").asText();
        assertThat(created.get("seriesId").asText()).isNotBlank();
        assertThat(created.get("guests").size()).isEqualTo(1);
        assertThat(created.get("status").asText()).isEqualTo("AGENDADO");
        String from = start.minus(1, ChronoUnit.DAYS).toString();
        String to = start.plus(30, ChronoUnit.DAYS).toString();
        JsonNode events = body(mvc.perform(auth(get("/api/calendar/events")).param("from", from).param("to", to).param("deadlines", "false"))
                .andExpect(status().isOk()).andReturn());
        assertThat(events.findValues("title").stream().filter(t -> t.asText().contains("Audiência de conciliação")).count()).isEqualTo(3);

        appt.put("title", "Audiência de conciliação (remarcada sala)");
        mvc.perform(json(put("/api/calendar/appointments/" + apptId).param("series", "true"), appt)).andExpect(status().isOk());
        JsonNode updated = body(mvc.perform(auth(get("/api/calendar/events")).param("from", from).param("to", to).param("deadlines", "false")).andReturn());
        assertThat(updated.findValues("title").stream().filter(t -> t.asText().contains("remarcada sala")).count()).isEqualTo(3);

        mvc.perform(json(post("/api/calendar/appointments/" + apptId + "/rsvp"), Map.of("status", "TALVEZ"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.attendance." + ownerUid).value("TALVEZ"));
        mvc.perform(json(post("/api/calendar/appointments/" + apptId + "/status"), Map.of("status", "REALIZADO", "outcome", "Acordo homologado")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("Acordo homologado"));
        MvcResult ics = mvc.perform(auth(get("/api/calendar/appointments/" + apptId + "/ics"))).andExpect(status().isOk()).andReturn();
        String icsText = ics.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(icsText).startsWith("BEGIN:VCALENDAR").contains("SUMMARY:").contains("LOCATION:").contains("END:VCALENDAR");
        JsonNode hearings = body(mvc.perform(auth(get("/api/calendar/hearings")).param("from", from).param("to", to)).andExpect(status().isOk()).andReturn());
        assertThat(hearings.size()).isEqualTo(3);
        MvcResult agReport = mvc.perform(auth(get("/api/calendar/report")).param("from", from).param("to", to).param("hearings", "true")
                .param("format", "xlsx")).andExpect(status().isOk()).andReturn();
        assertThat(agReport.getResponse().getContentAsByteArray()[0]).isEqualTo((byte) 'P');
        mvc.perform(auth(get("/api/calendar/appointments/" + apptId + "/history"))).andExpect(status().isOk());
        mvc.perform(auth(delete("/api/calendar/appointments/" + apptId).param("series", "true"))).andExpect(status().isNoContent());
        JsonNode afterDelete = body(mvc.perform(auth(get("/api/calendar/events")).param("from", from).param("to", to).param("deadlines", "false")).andReturn());
        assertThat(afterDelete.size()).isZero();

        // ------------------------------------------------------------------ 3. Tarefas
        Map<String, Object> task = new HashMap<>(Map.of("title", "Separar documentos da cliente", "priority", "ALTA",
                "assigneeUids", List.of(ownerUid), "labelIds", List.of(), "checklist", List.of(), "clientId", clientId));
        JsonNode card = body(mvc.perform(json(post("/api/tasks"), task)).andExpect(status().isCreated()).andReturn());
        String cardId = card.get("id").asText();
        mvc.perform(auth(get("/api/tasks")).param("scope", "mine").param("state", "open")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].card.id").value(cardId)).andExpect(jsonPath("$[0].boardName").value("Tarefas do escritório"));
        mvc.perform(auth(post("/api/tasks/" + cardId + "/complete"))).andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true));
        mvc.perform(auth(get("/api/tasks")).param("scope", "all").param("state", "done").param("clientId", clientId))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(auth(post("/api/tasks/" + cardId + "/reopen"))).andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(false));

        // ------------------------------------------------------------------ 4. Central documental
        mvc.perform(auth(get("/api/documents/settings"))).andExpect(status().isOk()).andExpect(jsonPath("$.maxBytes").value(25 * 1024 * 1024));
        String rootFolder = body(mvc.perform(json(post("/api/documents/folders"), Map.of("name", "Processos cíveis", "color", "#c9a227")))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        String subFolder = body(mvc.perform(json(post("/api/documents/folders"), Map.of("name", "Banco Exemplo", "parentId", rootFolder)))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        mvc.perform(json(put("/api/documents/folders/" + rootFolder), Map.of("name", "Processos cíveis", "parentId", subFolder)))
                .andExpect(status().is4xxClientError()).andExpect(jsonPath("$.code").value("FOLDER_CYCLE"));

        byte[] sheet = officeZip("xl/workbook.xml");
        Map<String, Object> meta = new HashMap<>(Map.of("title", "Planilha de cálculo", "category", "COMPROVANTE", "caseId", caseId,
                "folderId", subFolder, "tags", List.of("Cálculo", "urgente", "urgente")));
        meta.put("confidential", false);
        meta.put("sharedWithClient", false);
        JsonNode doc = body(mvc.perform(upload(multipart("/api/documents"), new MockMultipartFile("file", "calculo.xlsx",
                        "application/octet-stream", sheet), new MockMultipartFile("meta", "", "application/json", JSON.writeValueAsBytes(meta))))
                .andExpect(status().isCreated()).andReturn());
        String docId = doc.get("id").asText();
        assertThat(doc.get("contentType").asText()).contains("spreadsheetml");
        assertThat(doc.get("folderId").asText()).isEqualTo(subFolder);
        assertThat(doc.get("tags").toString()).isEqualTo("[\"cálculo\",\"urgente\"]");

        mvc.perform(auth(get("/api/documents")).param("folderId", subFolder)).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(auth(get("/api/documents")).param("tag", "urgente")).andExpect(jsonPath("$.items[0].id").value(docId));
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sheet));
        mvc.perform(auth(get("/api/documents/duplicates")).param("sha256", sha)).andExpect(jsonPath("$[0].id").value(docId));

        mvc.perform(auth(post("/api/documents/" + docId + "/favorite"))).andExpect(jsonPath("$.favorite").value(true));
        mvc.perform(auth(get("/api/documents")).param("favorites", "true")).andExpect(jsonPath("$.items[0].id").value(docId));
        mvc.perform(json(post("/api/documents/" + docId + "/move"), Map.of("folderId", rootFolder))).andExpect(jsonPath("$.folderId").value(rootFolder));
        JsonNode copy = body(mvc.perform(json(post("/api/documents/" + docId + "/copy"), Map.of("folderId", subFolder)))
                .andExpect(status().isCreated()).andReturn());
        assertThat(copy.get("title").asText()).isEqualTo("Cópia de Planilha de cálculo");
        assertThat(copy.get("favorite").asBoolean()).isFalse();
        mvc.perform(json(post("/api/documents/" + docId + "/share"), Map.of("uids", List.of(ownerUid), "message", "veja")))
                .andExpect(status().isNoContent());

        mvc.perform(auth(delete("/api/documents/folders/" + rootFolder))).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("FOLDER_NOT_EMPTY"));
        mvc.perform(auth(delete("/api/documents/" + docId)).param("reason", "teste")).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("NOT_IN_TRASH"));
        mvc.perform(auth(post("/api/documents/" + docId + "/trash"))).andExpect(jsonPath("$.deleted").value(true));
        JsonNode listing = body(mvc.perform(auth(get("/api/documents")).param("includeArchived", "true")).andReturn());
        assertThat(listing.get("items").findValues("id").stream().map(JsonNode::asText).toList()).doesNotContain(docId);
        mvc.perform(auth(get("/api/documents")).param("trash", "true")).andExpect(jsonPath("$.items[0].id").value(docId));
        mvc.perform(auth(post("/api/documents/" + docId + "/restore"))).andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.archived").value(false));
        mvc.perform(auth(post("/api/documents/" + docId + "/trash"))).andExpect(status().isOk());
        mvc.perform(auth(delete("/api/documents/" + docId)).param("reason", "cópia de teste")).andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/documents/" + docId))).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/audit")).param("entityType", "document").param("entityId", docId))
                .andExpect(jsonPath("$.items[0].action").value("DOCUMENT_PURGED"));
        // a pasta-mãe ainda tem a subpasta (com a cópia): só sai depois de esvaziada
        mvc.perform(auth(delete("/api/documents/folders/" + rootFolder))).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("FOLDER_NOT_EMPTY"));
        mvc.perform(auth(post("/api/documents/" + copy.get("id").asText() + "/trash"))).andExpect(status().isOk());
        mvc.perform(auth(delete("/api/documents/folders/" + subFolder))).andExpect(status().isNoContent());
        mvc.perform(auth(delete("/api/documents/folders/" + rootFolder))).andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/documents/folders"))).andExpect(jsonPath("$.length()").value(0));

        // ------------------------------------------------------------------ 5. Editor Jurídico
        JsonNode templates = body(mvc.perform(auth(get("/api/legal-editor/templates"))).andExpect(status().isOk()).andReturn());
        assertThat(templates.size()).isGreaterThanOrEqualTo(14);
        JsonNode fields = body(mvc.perform(auth(get("/api/legal-editor/fields")).param("caseId", caseId)).andExpect(status().isOk()).andReturn());
        assertThat(fields.get("values").get("cliente.nome").asText()).isEqualTo("Maria Operação");
        assertThat(fields.get("values").get("cliente.documento").asText()).isEqualTo("529.982.247-25");
        assertThat(fields.get("values").get("processo.vara").asText()).isEqualTo("2ª Vara Cível");
        assertThat(fields.get("values").get("advogado.oab").asText()).isEqualTo("PE 7777");

        JsonNode draft = body(mvc.perform(json(post("/api/legal-editor/documents"), Map.of("templateId", "sys-procuracao", "caseId", caseId)))
                .andExpect(status().isCreated()).andReturn());
        String draftId = draft.get("id").asText();
        assertThat(draft.get("content").asText()).contains("Maria Operação").contains("529.982.247-25").doesNotContain("{{");
        assertThat(draft.get("clientName").asText()).isEqualTo("Maria Operação");
        assertThat(draft.get("version").asInt()).isEqualTo(1);
        assertThat(draft.get("header").asText()).contains("ESCRITÓRIO OPERAÇÃO");

        Map<String, Object> upd = new HashMap<>(Map.of("title", "Procuração — Maria", "kind", "PROCURACAO",
                "content", "<h1>PROCURAÇÃO</h1><p onclick=\"x()\">Texto revisado <script>alert(1)</script><span class=\"field\">[processo.numero]</span></p>",
                "pageNumbers", true, "caseId", caseId, "clientId", clientId));
        upd.put("header", "ESCRITÓRIO OPERAÇÃO");
        JsonNode saved = body(mvc.perform(json(put("/api/legal-editor/documents/" + draftId), upd)).andExpect(status().isOk()).andReturn());
        assertThat(saved.get("content").asText()).doesNotContain("script").doesNotContain("onclick");
        assertThat(saved.get("missingFields").toString()).contains("processo.numero");
        mvc.perform(json(post("/api/legal-editor/documents/" + draftId + "/versions"), Map.of("note", "Revisão 1")))
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(auth(get("/api/legal-editor/documents/" + draftId + "/versions"))).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].note").value("Revisão 1"));

        MvcResult pdf = mvc.perform(auth(get("/api/legal-editor/documents/" + draftId + "/export")).param("format", "pdf")).andExpect(status().isOk()).andReturn();
        assertThat(new String(pdf.getResponse().getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        MvcResult docx = mvc.perform(auth(get("/api/legal-editor/documents/" + draftId + "/export")).param("format", "docx")).andExpect(status().isOk()).andReturn();
        assertThat(docx.getResponse().getContentAsByteArray()[0]).isEqualTo((byte) 'P');

        mvc.perform(auth(post("/api/legal-editor/documents/" + draftId + "/finalize"))).andExpect(jsonPath("$.status").value("FINAL"))
                .andExpect(jsonPath("$.version").value(3));
        mvc.perform(json(put("/api/legal-editor/documents/" + draftId), upd)).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("DOCUMENT_FINAL"));
        mvc.perform(auth(delete("/api/legal-editor/documents/" + draftId))).andExpect(status().is4xxClientError());

        JsonNode stored = body(mvc.perform(json(post("/api/legal-editor/documents/" + draftId + "/save-to-central"), Map.of("format", "pdf")))
                .andExpect(status().isOk()).andReturn());
        assertThat(stored.get("category").asText()).isEqualTo("PROCURACAO");
        assertThat(stored.get("caseId").asText()).isEqualTo(caseId);
        mvc.perform(auth(get("/api/legal-editor/documents/" + draftId))).andExpect(jsonPath("$.storedDocumentId").value(stored.get("id").asText()));
        mvc.perform(json(post("/api/legal-editor/documents/" + draftId + "/save-to-central"), Map.of("format", "docx")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(stored.get("id").asText()))
                .andExpect(jsonPath("$.currentVersion").value(2));

        mvc.perform(auth(post("/api/legal-editor/documents/" + draftId + "/reopen"))).andExpect(jsonPath("$.status").value("RASCUNHO"));
        mvc.perform(auth(post("/api/legal-editor/documents/" + draftId + "/versions/1/restore"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));
        mvc.perform(auth(get("/api/legal-editor/documents")).param("status", "RASCUNHO").param("search", "procuracao"))
                .andExpect(jsonPath("$[0].id").value(draftId));
        mvc.perform(auth(get("/api/legal-editor/documents")).param("caseId", caseId)).andExpect(jsonPath("$.length()").value(1));

        String copyId = body(mvc.perform(auth(post("/api/legal-editor/documents/" + draftId + "/duplicate"))).andExpect(status().isCreated()).andReturn())
                .get("id").asText();
        mvc.perform(auth(delete("/api/legal-editor/documents/" + copyId))).andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/legal-editor/documents/" + copyId))).andExpect(status().isNotFound());

        JsonNode custom = body(mvc.perform(json(post("/api/legal-editor/templates"), Map.of("name", "Procuração previdenciária", "kind", "PROCURACAO",
                "content", "<p>Outorgante: {{cliente.nome}} <img src=x onerror=alert(1)></p>"))).andExpect(status().isCreated()).andReturn());
        assertThat(custom.get("content").asText()).doesNotContain("img").contains("{{cliente.nome}}");
        JsonNode fromCustom = body(mvc.perform(json(post("/api/legal-editor/documents"), Map.of("templateId", custom.get("id").asText(),
                "clientId", clientId))).andExpect(status().isCreated()).andReturn());
        assertThat(fromCustom.get("content").asText()).contains("Outorgante: Maria Operação");
        mvc.perform(auth(delete("/api/legal-editor/templates/" + custom.get("id").asText()))).andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] officeZip(String entry) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("[Content_Types].xml"));
            z.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry(entry));
            z.write("<workbook/>".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return out.toByteArray();
    }

    private MockHttpServletRequestBuilder upload(MockMultipartHttpServletRequestBuilder builder, MockMultipartFile... files) {
        for (MockMultipartFile f : files) {
            builder.file(f);
        }
        return auth(builder);
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
