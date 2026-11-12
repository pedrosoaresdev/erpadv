package br.com.erpadv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
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

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Conteúdo dos documentos: texto nativo pesquisável e OCR de PDF digitalizado (quando o Tesseract existe no ambiente). */
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
class DocumentTextEndToEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    MockMvc mvc;

    private String token;

    @Test
    void textoNativoEOcr() throws Exception {
        String email = "ocr-" + UUID.randomUUID() + "@escritorio-o.test";
        token = authCall("accounts:signUp", email);
        mvc.perform(json(post("/api/onboarding/office"), Map.of("name", "Escritório OCR", "responsibleName", "Dr. Otto Ocr", "oabNumber", "PE 3434")))
                .andExpect(status().isCreated());
        token = authCall("accounts:signInWithPassword", email);

        // texto nativo: disponível na hora e encontrado pela busca pelo conteúdo
        String txt = upload("peticao.txt", "text/plain",
                "Excelentíssimo juízo. Requer a reintegração de posse do imóvel situado na Rua das Palmeiras.".getBytes(StandardCharsets.UTF_8));
        JsonNode t = body(mvc.perform(auth(get("/api/documents/" + txt + "/text"))).andExpect(status().isOk()).andReturn());
        assertThat(t.get("status").asText()).isEqualTo("TEXTO_NATIVO");
        assertThat(t.get("text").asText()).contains("reintegração de posse");
        JsonNode found = body(mvc.perform(auth(get("/api/documents")).param("search", "reintegracao")).andExpect(status().isOk()).andReturn());
        assertThat(found.get("items").findValuesAsText("id")).contains(txt);
        assertThat(body(mvc.perform(auth(get("/api/documents/" + txt))).andReturn()).get("ocrStatus").asText()).isEqualTo("TEXTO_NATIVO");

        // PDF digitalizado (só imagem): OCR em segundo plano no servidor
        String scan = upload("digitalizado.pdf", "application/pdf", scannedPdf("CONTRATO DE LOCACAO RESIDENCIAL"));
        JsonNode s = null;
        for (int i = 0; i < 90; i++) {
            s = body(mvc.perform(auth(get("/api/documents/" + scan + "/text"))).andExpect(status().isOk()).andReturn());
            if (List.of("CONCLUIDO", "SEM_TEXTO", "INDISPONIVEL", "FALHOU").contains(s.get("status").asText())) {
                break;
            }
            Thread.sleep(1000);
        }
        if (s.get("ocrAvailable").asBoolean()) {
            assertThat(s.get("status").asText()).isEqualTo("CONCLUIDO");
            assertThat(s.get("text").asText().toUpperCase()).contains("LOCACAO");
            JsonNode byOcr = body(mvc.perform(auth(get("/api/documents")).param("search", "locacao")).andReturn());
            assertThat(byOcr.get("items").findValuesAsText("id")).contains(scan);
        } else {
            // sem Tesseract no ambiente o sistema informa, não simula
            assertThat(s.get("status").asText()).isEqualTo("INDISPONIVEL");
            assertThat(s.get("text").asText()).isEmpty();
        }
        mvc.perform(auth(post("/api/documents/" + scan + "/ocr"))).andExpect(status().isOk());
    }

    private static byte[] scannedPdf(String text) throws Exception {
        BufferedImage img = new BufferedImage(1700, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 64));
        g.drawString(text, 60, 300);
        g.dispose();
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDImageXObject x = LosslessFactory.createFromImage(doc, img);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(x, 20, 500, 555, 196);
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private String upload(String name, String type, byte[] bytes) throws Exception {
        return body(mvc.perform(auth(multipart("/api/documents")
                        .file(new MockMultipartFile("file", name, type, bytes))
                        .file(new MockMultipartFile("meta", "", "application/json",
                                JSON.writeValueAsBytes(Map.of("title", name, "category", "OUTRO"))))))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
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
        String payload = JSON.writeValueAsString(Map.of("email", email, "password", "Senha-Forte-123", "returnSecureToken", true));
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
