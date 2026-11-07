package br.com.erpadv.services.generation;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Exporta texto com marcação simples para PDF (OpenPDF) e DOCX (Apache POI):
 * <ul>
 *   <li>{@code # Título} — título centralizado em negrito</li>
 *   <li>{@code ## Cláusula} — subtítulo em negrito</li>
 *   <li>{@code > linha} — linha centralizada (assinaturas)</li>
 *   <li>linhas em branco separam parágrafos (justificados)</li>
 * </ul>
 */
public final class DocumentExporter {

    enum Kind { TITLE, HEADING, CENTER, PARAGRAPH, BLANK }

    record Block(Kind kind, String text) {
    }

    private DocumentExporter() {
    }

    static List<Block> parse(String body) {
        List<Block> blocks = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        for (String raw : (body == null ? "" : body).replace("\r", "").split("\n", -1)) {
            String line = raw.stripTrailing();
            boolean special = line.startsWith("# ") || line.startsWith("## ") || line.startsWith("> ") || line.isBlank();
            if (special && !paragraph.isEmpty()) {
                blocks.add(new Block(Kind.PARAGRAPH, paragraph.toString().trim()));
                paragraph.setLength(0);
            }
            if (line.startsWith("## ")) {
                blocks.add(new Block(Kind.HEADING, line.substring(3).trim()));
            } else if (line.startsWith("# ")) {
                blocks.add(new Block(Kind.TITLE, line.substring(2).trim()));
            } else if (line.startsWith("> ")) {
                blocks.add(new Block(Kind.CENTER, line.substring(2)));
            } else if (line.isBlank()) {
                if (!blocks.isEmpty() && blocks.get(blocks.size() - 1).kind() != Kind.BLANK) {
                    blocks.add(new Block(Kind.BLANK, ""));
                }
            } else {
                paragraph.append(paragraph.isEmpty() ? "" : " ").append(line.trim());
            }
        }
        if (!paragraph.isEmpty()) {
            blocks.add(new Block(Kind.PARAGRAPH, paragraph.toString().trim()));
        }
        return blocks;
    }

    public static byte[] toPdf(String body, String footer) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 72, 72, 64, 64);
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            BaseFont regular = BaseFont.createFont(BaseFont.TIMES_ROMAN, BaseFont.CP1252, false);
            BaseFont bold = BaseFont.createFont(BaseFont.TIMES_BOLD, BaseFont.CP1252, false);
            Font text = new Font(regular, 12);
            Font heading = new Font(bold, 12);
            Font title = new Font(bold, 14);
            Font small = new Font(regular, 8);
            writer.setPageEvent(new PdfPageEventHelper() {
                @Override
                public void onEndPage(PdfWriter w, Document d) {
                    String label = (footer == null ? "" : footer + " — ") + "Página " + w.getPageNumber();
                    ColumnText.showTextAligned(w.getDirectContent(), Element.ALIGN_CENTER, new Phrase(label, small),
                            (d.left() + d.right()) / 2, d.bottom() - 28, 0);
                }
            });
            doc.open();
            for (Block b : parse(body)) {
                Paragraph p;
                switch (b.kind()) {
                    case TITLE -> {
                        p = new Paragraph(b.text(), title);
                        p.setAlignment(Element.ALIGN_CENTER);
                        p.setSpacingAfter(14);
                    }
                    case HEADING -> {
                        p = new Paragraph(b.text(), heading);
                        p.setSpacingBefore(8);
                        p.setSpacingAfter(4);
                    }
                    case CENTER -> {
                        p = new Paragraph(b.text(), text);
                        p.setAlignment(Element.ALIGN_CENTER);
                    }
                    case BLANK -> p = new Paragraph(Chunk.NEWLINE);
                    default -> {
                        p = new Paragraph(b.text(), text);
                        p.setAlignment(Element.ALIGN_JUSTIFIED);
                        p.setLeading(17);
                        p.setSpacingAfter(6);
                    }
                }
                doc.add(p);
            }
            doc.close();
            return out.toByteArray();
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("Falha ao gerar PDF", e);
        }
    }

    public static byte[] toDocx(String body) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (Block b : parse(body)) {
                XWPFParagraph p = doc.createParagraph();
                XWPFRun run = p.createRun();
                run.setFontFamily("Times New Roman");
                run.setFontSize(12);
                switch (b.kind()) {
                    case TITLE -> {
                        p.setAlignment(ParagraphAlignment.CENTER);
                        p.setSpacingAfter(280);
                        run.setBold(true);
                        run.setFontSize(14);
                        run.setText(b.text());
                    }
                    case HEADING -> {
                        p.setSpacingBefore(160);
                        run.setBold(true);
                        run.setText(b.text());
                    }
                    case CENTER -> {
                        p.setAlignment(ParagraphAlignment.CENTER);
                        run.setText(b.text());
                    }
                    case BLANK -> run.setText("");
                    default -> {
                        p.setAlignment(ParagraphAlignment.BOTH);
                        p.setSpacingAfter(120);
                        run.setText(b.text());
                    }
                }
            }
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao gerar DOCX", e);
        }
    }
}
