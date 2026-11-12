package br.com.erpadv.services;

import br.com.erpadv.firebase.FirestoreMapper;
import br.com.erpadv.firebase.SearchTokens;
import br.com.erpadv.models.BaseEntity;
import br.com.erpadv.models.StoredDocument;
import br.com.erpadv.repositories.DocumentRepositories;
import br.com.erpadv.repositories.FirestoreRepository;
import br.com.erpadv.services.ai.DocumentText;
import com.google.cloud.firestore.Firestore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Texto dos documentos da central: texto nativo de PDF/DOCX/TXT e OCR (Tesseract, executado no próprio servidor — nenhum
 * arquivo sai do escritório) para PDFs digitalizados e fotos. O texto alimenta a busca da central e a IA Jurídica.
 */
@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);
    private static final int MAX_PAGES = 60;
    private static final int MAX_CHARS = 500_000;
    private static final int MAX_CONTENT_TOKENS = 400;

    public static final String NATIVE = "TEXTO_NATIVO";
    public static final String PENDING = "PENDENTE";
    public static final String RUNNING = "PROCESSANDO";
    public static final String DONE = "CONCLUIDO";
    public static final String EMPTY = "SEM_TEXTO";
    public static final String UNAVAILABLE = "INDISPONIVEL";
    public static final String FAILED = "FALHOU";

    /** Texto extraído ({@code offices/{officeId}/document_texts/{documentId}}), separado do documento por tamanho e sigilo. */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class DocumentTextRecord extends BaseEntity {
        private int version;
        private String engine;
        private String status;
        private int pages;
        private String text;
        private boolean restricted;
        private String error;
    }

    public record TextView(String status, String engine, int pages, int version, Instant updatedAt, String text, boolean ocrAvailable) {
    }

    @Repository
    public static class Texts extends FirestoreRepository<DocumentTextRecord> {
        public Texts(Firestore db, FirestoreMapper mapper) {
            super(db, mapper, DocumentTextRecord.class, "document_texts");
        }
    }

    private final Texts texts;
    private final DocumentRepositories.Documents documents;
    private final String tesseract;
    private final String languages;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(200),
            r -> {
                Thread t = new Thread(r, "ocr-worker");
                t.setDaemon(true);
                return t;
            });
    private volatile Boolean available;

    public OcrService(Texts texts, DocumentRepositories.Documents documents,
                      @Value("${app.ocr.tesseract-path:tesseract}") String tesseract,
                      @Value("${app.ocr.languages:por}") String languages) {
        this.texts = texts;
        this.documents = documents;
        this.tesseract = tesseract;
        this.languages = languages;
    }

    /** Tesseract instalado e com o idioma configurado (verificado uma vez). */
    public boolean available() {
        if (available == null) {
            try {
                Process p = new ProcessBuilder(tesseract, "--list-langs").redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                boolean ok = p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0
                        && List.of(languages.split("\\+")).stream().allMatch(l -> out.lines().anyMatch(x -> x.trim().equals(l)));
                available = ok;
                if (!ok) {
                    log.warn("Tesseract encontrado, mas sem o idioma {}: OCR desativado", languages);
                }
            } catch (IOException e) {
                available = false;
                log.info("Tesseract não instalado neste ambiente: OCR desativado ({})", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                available = false;
            }
        }
        return available;
    }

    /**
     * Chamado após o envio de um arquivo ou de uma nova versão. Texto nativo é gravado na hora; o OCR entra na fila (um por
     * vez, para não disputar a CPU com o sistema).
     */
    public void process(String officeId, StoredDocument d, byte[] bytes) {
        String ct = d.getContentType() == null ? "" : d.getContentType().toLowerCase(Locale.ROOT);
        boolean image = ct.startsWith("image/jpeg") || ct.startsWith("image/png");
        boolean pdf = DocumentText.isPdf(ct, d.getFileName());
        String nativeText = image ? "" : DocumentText.extract(bytes, ct, d.getFileName());
        int pages = pdf ? pageCount(bytes) : 1;
        boolean scanned = image || (pdf && nativeText.replaceAll("\\s", "").length() < 80L * Math.max(1, pages) / 2);
        if (!scanned) {
            if (!nativeText.isBlank()) {
                save(officeId, d, NATIVE, "Texto do arquivo", pages, nativeText, null);
            }
            return;
        }
        if (!available()) {
            mark(officeId, d.getId(), UNAVAILABLE);
            save(officeId, d, UNAVAILABLE, null, pages, "", "OCR indisponível neste servidor (Tesseract não instalado).");
            return;
        }
        mark(officeId, d.getId(), PENDING);
        String id = d.getId();
        int version = d.getCurrentVersion();
        try {
            executor.execute(() -> run(officeId, id, version, bytes, image));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            mark(officeId, id, FAILED);
            log.warn("Fila de OCR cheia; documento {} não processado", id);
        }
    }

    private void run(String officeId, String id, int version, byte[] bytes, boolean image) {
        StoredDocument d = documents.findById(officeId, id).orElse(null);
        if (d == null || d.getCurrentVersion() != version) {
            return;
        }
        mark(officeId, id, RUNNING);
        try {
            StringBuilder out = new StringBuilder();
            int pages;
            if (image) {
                pages = 1;
                out.append(ocrImage(ImageIO.read(new java.io.ByteArrayInputStream(bytes))));
            } else {
                try (PDDocument pdf = Loader.loadPDF(bytes)) {
                    pages = Math.min(pdf.getNumberOfPages(), MAX_PAGES);
                    PDFRenderer renderer = new PDFRenderer(pdf);
                    for (int i = 0; i < pages; i++) {
                        BufferedImage img = renderer.renderImageWithDPI(i, 300, ImageType.GRAY);
                        out.append("--- página ").append(i + 1).append(" ---\n").append(ocrImage(img)).append('\n');
                        if (out.length() > MAX_CHARS) {
                            break;
                        }
                    }
                }
            }
            String text = out.toString().trim();
            boolean hasText = text.replaceAll("--- página \\d+ ---", "").replaceAll("\\s", "").length() >= 20;
            StoredDocument fresh = documents.findById(officeId, id).orElse(null);
            if (fresh == null || fresh.getCurrentVersion() != version) {
                return;
            }
            save(officeId, fresh, hasText ? DONE : EMPTY, "OCR (Tesseract, " + languages + ")", pages, hasText ? text : "", null);
        } catch (IOException | RuntimeException e) {
            log.warn("OCR falhou para o documento {}", id, e);
            documents.findById(officeId, id).ifPresent(x -> save(officeId, x, FAILED, null, 0, "", e.getMessage()));
        }
    }

    private String ocrImage(BufferedImage img) throws IOException {
        if (img == null) {
            throw new IOException("Imagem ilegível");
        }
        Path file = Files.createTempFile("ocr-", ".png");
        try {
            ImageIO.write(img, "png", file.toFile());
            Process p = new ProcessBuilder(tesseract, file.toString(), "stdout", "-l", languages, "--psm", "3")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            byte[] stdout = p.getInputStream().readAllBytes();
            if (!p.waitFor(180, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("Tempo esgotado no OCR");
            }
            if (p.exitValue() != 0) {
                throw new IOException("Tesseract terminou com erro " + p.exitValue());
            }
            return new String(stdout, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("OCR interrompido", e);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private void mark(String officeId, String id, String status) {
        documents.findById(officeId, id).ifPresent(d -> {
            d.setOcrStatus(status);
            documents.save(officeId, d);
        });
    }

    private void save(String officeId, StoredDocument d, String status, String engine, int pages, String text, String error) {
        DocumentTextRecord r = texts.findById(officeId, d.getId()).orElseGet(DocumentTextRecord::new);
        r.setId(d.getId());
        r.setVersion(d.getCurrentVersion());
        r.setStatus(status);
        r.setEngine(engine);
        r.setPages(pages);
        r.setText(text == null ? "" : text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text);
        r.setRestricted(d.isRestricted() || d.isConfidential());
        r.setError(error);
        r.setUpdatedAt(Instant.now());
        if (r.getCreatedAt() == null) {
            r.setCreatedAt(r.getUpdatedAt());
        }
        texts.save(officeId, r);
        d.setOcrStatus(status);
        d.setContentTokens(tokens(r.getText()));
        DocumentService.rebuildTokens(d);
        documents.save(officeId, d);
    }

    /** Palavras mais frequentes do conteúdo (inteiras, como a busca as procura), sem números longos (CPF, contas…). */
    static List<String> tokens(String text) {
        Map<String, Integer> freq = new HashMap<>();
        for (String w : SearchTokens.normalize(text).split(" ")) {
            if (w.length() < 4 || w.matches(".*\\d.*")) {
                continue;
            }
            String t = w.length() > SearchTokens.MAX_PREFIX ? w.substring(0, SearchTokens.MAX_PREFIX) : w;
            freq.merge(t, 1, Integer::sum);
        }
        return freq.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_CONTENT_TOKENS).map(Map.Entry::getKey).toList();
    }

    private static int pageCount(byte[] bytes) {
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            return pdf.getNumberOfPages();
        } catch (IOException | RuntimeException e) {
            return 1;
        }
    }

    // ------------------------------------------------------------------ leitura

    public Optional<DocumentTextRecord> record(String officeId, String documentId) {
        return texts.findById(officeId, documentId);
    }

    /** Texto vigente (somente se corresponder à versão atual do documento). */
    public Optional<String> currentText(String officeId, StoredDocument d) {
        return texts.findById(officeId, d.getId())
                .filter(r -> r.getVersion() == d.getCurrentVersion() && (DONE.equals(r.getStatus()) || NATIVE.equals(r.getStatus())))
                .map(DocumentTextRecord::getText).filter(t -> !t.isBlank());
    }

    public TextView view(String officeId, StoredDocument d) {
        DocumentTextRecord r = texts.findById(officeId, d.getId()).orElse(null);
        if (r == null) {
            return new TextView(d.getOcrStatus(), null, 0, d.getCurrentVersion(), null, "", available());
        }
        return new TextView(r.getVersion() == d.getCurrentVersion() ? r.getStatus() : PENDING, r.getEngine(), r.getPages(), r.getVersion(),
                r.getUpdatedAt(), r.getText(), available());
    }
}
