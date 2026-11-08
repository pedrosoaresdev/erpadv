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
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.draw.LineSeparator;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document.OutputSettings;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSimpleField;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Documento jurídico em HTML restrito (editor do sistema): saneamento por lista de permissões e exportação
 * para PDF (OpenPDF) e DOCX (Apache POI) com cabeçalho, rodapé e numeração de páginas.
 *
 * <p>Elementos aceitos: p, div, h1–h3, strong/b, em/i, u, s, br, ul/ol/li, blockquote, table/tr/td/th, hr
 * ({@code class="page-break"} = quebra de página). Estilos: somente {@code text-align} e {@code margin-left}.
 */
public final class HtmlDocument {

    /** Tamanho máximo do HTML (o Firestore limita cada documento a ~1 MiB). */
    public static final int MAX_HTML = 400_000;

    private static final Safelist SAFELIST = Safelist.none()
            .addTags("p", "div", "h1", "h2", "h3", "strong", "b", "em", "i", "u", "s", "strike", "br", "ul", "ol", "li",
                    "blockquote", "table", "thead", "tbody", "tr", "td", "th", "hr", "span")
            .addAttributes("p", "style")
            .addAttributes("div", "style")
            .addAttributes("h1", "style")
            .addAttributes("h2", "style")
            .addAttributes("h3", "style")
            .addAttributes("li", "style")
            .addAttributes("td", "style", "colspan")
            .addAttributes("th", "style", "colspan")
            .addAttributes("hr", "class")
            .addAttributes("span", "class", "data-field");

    private static final Pattern ALIGN = Pattern.compile("text-align\\s*:\\s*(left|center|right|justify)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARGIN = Pattern.compile("margin-left\\s*:\\s*(\\d{1,3})px", Pattern.CASE_INSENSITIVE);

    private HtmlDocument() {
    }

    // ------------------------------------------------------------------ saneamento

    /** Remove tudo que não estiver na lista de permissões (scripts, eventos, links, imagens, estilos arbitrários). */
    public static String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        org.jsoup.nodes.Document dirty = Jsoup.parseBodyFragment(html);
        org.jsoup.nodes.Document clean = new org.jsoup.safety.Cleaner(SAFELIST).clean(dirty);
        for (org.jsoup.nodes.Element el : clean.body().select("[style]")) {
            String style = el.attr("style");
            StringBuilder kept = new StringBuilder();
            Matcher a = ALIGN.matcher(style);
            if (a.find()) {
                kept.append("text-align: ").append(a.group(1).toLowerCase(Locale.ROOT)).append(';');
            }
            Matcher m = MARGIN.matcher(style);
            if (m.find() && Integer.parseInt(m.group(1)) <= 400) {
                kept.append(kept.isEmpty() ? "" : " ").append("margin-left: ").append(m.group(1)).append("px;");
            }
            if (kept.isEmpty()) {
                el.removeAttr("style");
            } else {
                el.attr("style", kept.toString());
            }
        }
        for (org.jsoup.nodes.Element el : clean.body().select("hr[class]")) {
            if (!"page-break".equals(el.attr("class"))) {
                el.removeAttr("class");
            }
        }
        for (org.jsoup.nodes.Element el : clean.body().select("span[class]")) {
            if (!"field".equals(el.attr("class"))) {
                el.removeAttr("class");
            }
        }
        clean.outputSettings(new OutputSettings().prettyPrint(false));
        return clean.body().html();
    }

    /** Escapa texto para inserção segura em HTML (valores dos campos automáticos). */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Texto puro (para busca, prévia e contagem de palavras). */
    public static String plainText(String html) {
        return html == null ? "" : Jsoup.parseBodyFragment(html).text();
    }

    // ------------------------------------------------------------------ modelo intermediário

    enum BlockType { PARAGRAPH, H1, H2, H3, LIST_ITEM, QUOTE, PAGE_BREAK, RULE, TABLE }

    record Run(String text, boolean bold, boolean italic, boolean underline, boolean strike, boolean lineBreak) {
    }

    record Block(BlockType type, List<Run> runs, String align, int indent, String bullet, List<List<List<Run>>> table) {
    }

    record Style(boolean bold, boolean italic, boolean underline, boolean strike) {
        Style with(String tag) {
            return switch (tag) {
                case "strong", "b" -> new Style(true, italic, underline, strike);
                case "em", "i" -> new Style(bold, true, underline, strike);
                case "u" -> new Style(bold, italic, true, strike);
                case "s", "strike" -> new Style(bold, italic, underline, true);
                default -> this;
            };
        }
    }

    static List<Block> blocks(String html) {
        org.jsoup.nodes.Document doc = Jsoup.parseBodyFragment(sanitize(html));
        List<Block> out = new ArrayList<>();
        walkBlocks(doc.body(), out, 0);
        return out;
    }

    private static void walkBlocks(org.jsoup.nodes.Element parent, List<Block> out, int baseIndent) {
        List<Run> loose = new ArrayList<>();
        for (Node node : parent.childNodes()) {
            if (node instanceof org.jsoup.nodes.Element el && isBlock(el.tagName())) {
                flushLoose(loose, out, baseIndent);
                String tag = el.tagName();
                int indent = baseIndent + indentOf(el);
                switch (tag) {
                    case "h1", "h2", "h3" -> out.add(new Block(BlockType.valueOf(tag.toUpperCase(Locale.ROOT)), runs(el), alignOf(el), indent, null, null));
                    case "ul", "ol" -> {
                        int n = 1;
                        for (org.jsoup.nodes.Element li : el.children()) {
                            if (!"li".equals(li.tagName())) {
                                continue;
                            }
                            String bullet = "ol".equals(tag) ? (n++) + "." : "•";
                            out.add(new Block(BlockType.LIST_ITEM, runs(li), alignOf(li), indent + 18, bullet, null));
                        }
                    }
                    case "blockquote" -> {
                        if (el.children().stream().anyMatch(c -> isBlock(c.tagName()))) {
                            walkBlocks(el, out, indent + 36);
                        } else {
                            out.add(new Block(BlockType.QUOTE, runs(el), alignOf(el), indent + 36, null, null));
                        }
                    }
                    case "hr" -> out.add(new Block("page-break".equals(el.attr("class")) ? BlockType.PAGE_BREAK : BlockType.RULE,
                            List.of(), null, 0, null, null));
                    case "table" -> {
                        List<List<List<Run>>> rows = new ArrayList<>();
                        for (org.jsoup.nodes.Element tr : el.select("tr")) {
                            List<List<Run>> cells = new ArrayList<>();
                            for (org.jsoup.nodes.Element td : tr.children()) {
                                if ("td".equals(td.tagName()) || "th".equals(td.tagName())) {
                                    List<Run> r = runs(td);
                                    if ("th".equals(td.tagName())) {
                                        r = r.stream().map(x -> new Run(x.text(), true, x.italic(), x.underline(), x.strike(), x.lineBreak())).toList();
                                    }
                                    cells.add(r);
                                }
                            }
                            if (!cells.isEmpty()) {
                                rows.add(cells);
                            }
                        }
                        if (!rows.isEmpty()) {
                            out.add(new Block(BlockType.TABLE, List.of(), null, indent, null, rows));
                        }
                    }
                    default -> {
                        // p, div
                        if (el.children().stream().anyMatch(c -> isBlock(c.tagName()))) {
                            walkBlocks(el, out, indent);
                        } else {
                            out.add(new Block(BlockType.PARAGRAPH, runs(el), alignOf(el), indent, null, null));
                        }
                    }
                }
            } else {
                collectRuns(node, new Style(false, false, false, false), loose);
            }
        }
        flushLoose(loose, out, baseIndent);
    }

    private static void flushLoose(List<Run> loose, List<Block> out, int indent) {
        if (loose.stream().anyMatch(r -> r.lineBreak() || !r.text().isBlank())) {
            out.add(new Block(BlockType.PARAGRAPH, new ArrayList<>(loose), null, indent, null, null));
        }
        loose.clear();
    }

    private static boolean isBlock(String tag) {
        return switch (tag) {
            case "p", "div", "h1", "h2", "h3", "ul", "ol", "blockquote", "hr", "table" -> true;
            default -> false;
        };
    }

    private static List<Run> runs(org.jsoup.nodes.Element el) {
        List<Run> out = new ArrayList<>();
        Style base = new Style(false, false, false, false);
        for (Node n : el.childNodes()) {
            collectRuns(n, base, out);
        }
        // remove <br> final redundante (o editor costuma deixar um)
        if (!out.isEmpty() && out.get(out.size() - 1).lineBreak()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private static void collectRuns(Node node, Style style, List<Run> out) {
        if (node instanceof TextNode t) {
            String text = t.getWholeText().replace(' ', ' ').replaceAll("\\s+", " ");
            if (!text.isEmpty()) {
                out.add(new Run(text, style.bold(), style.italic(), style.underline(), style.strike(), false));
            }
        } else if (node instanceof org.jsoup.nodes.Element el) {
            if ("br".equals(el.tagName())) {
                out.add(new Run("", false, false, false, false, true));
                return;
            }
            Style next = style.with(el.tagName());
            for (Node c : el.childNodes()) {
                collectRuns(c, next, out);
            }
        }
    }

    private static String alignOf(org.jsoup.nodes.Element el) {
        Matcher m = ALIGN.matcher(el.attr("style"));
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static int indentOf(org.jsoup.nodes.Element el) {
        Matcher m = MARGIN.matcher(el.attr("style"));
        return m.find() ? Math.min(Integer.parseInt(m.group(1)), 400) * 3 / 4 : 0;
    }

    // ------------------------------------------------------------------ PDF

    public record Layout(String header, String footer, boolean pageNumbers) {
    }

    public static byte[] toPdf(String html, Layout layout) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            boolean hasHeader = layout.header() != null && !layout.header().isBlank();
            Document doc = new Document(PageSize.A4, 85, 57, hasHeader ? 90 : 72, 72);
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            BaseFont regular = BaseFont.createFont(BaseFont.TIMES_ROMAN, BaseFont.CP1252, false);
            BaseFont bold = BaseFont.createFont(BaseFont.TIMES_BOLD, BaseFont.CP1252, false);
            BaseFont italic = BaseFont.createFont(BaseFont.TIMES_ITALIC, BaseFont.CP1252, false);
            BaseFont boldItalic = BaseFont.createFont(BaseFont.TIMES_BOLDITALIC, BaseFont.CP1252, false);
            Fonts fonts = new Fonts(regular, bold, italic, boldItalic);
            Font small = new Font(regular, 9);
            writer.setPageEvent(new PdfPageEventHelper() {
                private PdfTemplate total;

                @Override
                public void onOpenDocument(PdfWriter w, Document d) {
                    total = w.getDirectContent().createTemplate(30, 12);
                }

                @Override
                public void onEndPage(PdfWriter w, Document d) {
                    PdfContentByte cb = w.getDirectContent();
                    float center = (d.left() + d.right()) / 2;
                    if (hasHeader) {
                        String[] lines = layout.header().split("\\r?\\n");
                        float y = d.top() + 22 + (lines.length - 1) * 11;
                        for (String line : lines) {
                            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER, new Phrase(line, small), center, y, 0);
                            y -= 11;
                        }
                        cb.setLineWidth(0.4f);
                        cb.moveTo(d.left(), d.top() + 12);
                        cb.lineTo(d.right(), d.top() + 12);
                        cb.stroke();
                    }
                    float y = d.bottom() - 30;
                    if (layout.footer() != null && !layout.footer().isBlank()) {
                        for (String line : layout.footer().split("\\r?\\n")) {
                            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER, new Phrase(line, small), center, y, 0);
                            y -= 11;
                        }
                    }
                    if (layout.pageNumbers()) {
                        String label = "Página " + w.getPageNumber() + " de ";
                        float width = regular.getWidthPoint(label, 9);
                        float x = d.right() - width - 18;
                        ColumnText.showTextAligned(cb, Element.ALIGN_LEFT, new Phrase(label, small), x, d.bottom() - 48, 0);
                        cb.addTemplate(total, x + width, d.bottom() - 48);
                    }
                }

                @Override
                public void onCloseDocument(PdfWriter w, Document d) {
                    total.beginText();
                    total.setFontAndSize(regular, 9);
                    total.setTextMatrix(0, 0);
                    total.showText(String.valueOf(w.getPageNumber() - 1));
                    total.endText();
                }
            });
            doc.open();
            boolean any = false;
            for (Block b : blocks(html)) {
                switch (b.type()) {
                    case PAGE_BREAK -> {
                        if (any) {
                            doc.newPage();
                        }
                    }
                    case RULE -> doc.add(new Chunk(new LineSeparator(0.5f, 100, null, Element.ALIGN_CENTER, -4)));
                    case TABLE -> doc.add(pdfTable(b, fonts));
                    default -> doc.add(pdfParagraph(b, fonts));
                }
                any = true;
            }
            if (!any) {
                doc.add(new Paragraph(" "));
            }
            doc.close();
            return out.toByteArray();
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("Falha ao gerar PDF", e);
        }
    }

    private record Fonts(BaseFont regular, BaseFont bold, BaseFont italic, BaseFont boldItalic) {
        Font of(Run r, float size, boolean forceBold) {
            boolean b = r.bold() || forceBold;
            BaseFont base = b && r.italic() ? boldItalic : b ? bold : r.italic() ? italic : regular;
            int style = (r.underline() ? Font.UNDERLINE : 0) | (r.strike() ? Font.STRIKETHRU : 0);
            return new Font(base, size, style);
        }
    }

    private static Paragraph pdfParagraph(Block b, Fonts fonts) {
        float size = switch (b.type()) {
            case H1 -> 15;
            case H2 -> 13;
            default -> 12;
        };
        boolean heading = b.type() == BlockType.H1 || b.type() == BlockType.H2 || b.type() == BlockType.H3;
        Paragraph p = new Paragraph();
        p.setLeading(size * 1.5f);
        if (b.bullet() != null) {
            p.add(new Chunk(b.bullet() + " ", new Font(fonts.regular(), size)));
        }
        for (Run r : b.runs()) {
            p.add(r.lineBreak() ? Chunk.NEWLINE : new Chunk(r.text(), fonts.of(r, size, heading)));
        }
        if (b.runs().isEmpty()) {
            p.add(new Chunk(" ", new Font(fonts.regular(), size)));
        }
        p.setAlignment(switch (b.align() == null ? (b.type() == BlockType.H1 ? "center" : heading || b.bullet() != null ? "left" : "justify") : b.align()) {
            case "center" -> Element.ALIGN_CENTER;
            case "right" -> Element.ALIGN_RIGHT;
            case "left" -> Element.ALIGN_LEFT;
            default -> Element.ALIGN_JUSTIFIED;
        });
        p.setIndentationLeft(b.indent());
        if (b.type() == BlockType.PARAGRAPH && b.align() == null) {
            p.setFirstLineIndent(0);
        }
        if (b.type() == BlockType.QUOTE) {
            p.setIndentationRight(18);
        }
        p.setSpacingBefore(heading ? 10 : 0);
        p.setSpacingAfter(heading ? 6 : b.bullet() != null ? 2 : 8);
        return p;
    }

    private static PdfPTable pdfTable(Block b, Fonts fonts) {
        int cols = b.table().stream().mapToInt(List::size).max().orElse(1);
        PdfPTable t = new PdfPTable(cols);
        t.setWidthPercentage(100);
        t.setSpacingBefore(4);
        t.setSpacingAfter(10);
        for (List<List<Run>> row : b.table()) {
            for (int i = 0; i < cols; i++) {
                Phrase ph = new Phrase();
                if (i < row.size()) {
                    for (Run r : row.get(i)) {
                        ph.add(r.lineBreak() ? Chunk.NEWLINE : new Chunk(r.text(), fonts.of(r, 11, false)));
                    }
                }
                PdfPCell cell = new PdfPCell(ph);
                cell.setPadding(5);
                cell.setBorderWidth(0.5f);
                t.addCell(cell);
            }
        }
        return t;
    }

    // ------------------------------------------------------------------ DOCX

    public static byte[] toDocx(String html, Layout layout) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CTSectPr sect = doc.getDocument().getBody().isSetSectPr() ? doc.getDocument().getBody().getSectPr()
                    : doc.getDocument().getBody().addNewSectPr();
            CTPageSz size = sect.isSetPgSz() ? sect.getPgSz() : sect.addNewPgSz();
            size.setW(BigInteger.valueOf(11906));
            size.setH(BigInteger.valueOf(16838));
            CTPageMar mar = sect.isSetPgMar() ? sect.getPgMar() : sect.addNewPgMar();
            mar.setLeft(BigInteger.valueOf(1701));
            mar.setRight(BigInteger.valueOf(1134));
            mar.setTop(BigInteger.valueOf(1417));
            mar.setBottom(BigInteger.valueOf(1417));
            if (layout.header() != null && !layout.header().isBlank()) {
                XWPFHeader header = doc.createHeader(HeaderFooterType.DEFAULT);
                boolean first = true;
                for (String line : layout.header().split("\\r?\\n")) {
                    XWPFParagraph p = first ? header.createParagraph() : header.createParagraph();
                    first = false;
                    p.setAlignment(ParagraphAlignment.CENTER);
                    XWPFRun r = p.createRun();
                    r.setFontFamily("Times New Roman");
                    r.setFontSize(9);
                    r.setText(line);
                }
            }
            boolean footerText = layout.footer() != null && !layout.footer().isBlank();
            if (footerText || layout.pageNumbers()) {
                XWPFFooter footer = doc.createFooter(HeaderFooterType.DEFAULT);
                if (footerText) {
                    for (String line : layout.footer().split("\\r?\\n")) {
                        XWPFParagraph p = footer.createParagraph();
                        p.setAlignment(ParagraphAlignment.CENTER);
                        XWPFRun r = p.createRun();
                        r.setFontFamily("Times New Roman");
                        r.setFontSize(9);
                        r.setText(line);
                    }
                }
                if (layout.pageNumbers()) {
                    XWPFParagraph p = footer.createParagraph();
                    p.setAlignment(ParagraphAlignment.RIGHT);
                    XWPFRun r = p.createRun();
                    r.setFontFamily("Times New Roman");
                    r.setFontSize(9);
                    r.setText("Página ");
                    field(p, "PAGE");
                    XWPFRun of = p.createRun();
                    of.setFontFamily("Times New Roman");
                    of.setFontSize(9);
                    of.setText(" de ");
                    field(p, "NUMPAGES");
                }
            }
            boolean any = false;
            for (Block b : blocks(html)) {
                switch (b.type()) {
                    case PAGE_BREAK -> {
                        if (any) {
                            doc.createParagraph().createRun().addBreak(BreakType.PAGE);
                        }
                    }
                    case RULE -> {
                        XWPFParagraph p = doc.createParagraph();
                        p.setBorderBottom(org.apache.poi.xwpf.usermodel.Borders.SINGLE);
                    }
                    case TABLE -> docxTable(doc, b);
                    default -> docxParagraph(doc.createParagraph(), b);
                }
                any = true;
            }
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao gerar DOCX", e);
        }
    }

    private static void field(XWPFParagraph p, String instr) {
        CTSimpleField f = p.getCTP().addNewFldSimple();
        f.setInstr(instr + " \\* MERGEFORMAT");
        var r = f.addNewR();
        var rpr = r.addNewRPr();
        rpr.addNewSz().setVal(BigInteger.valueOf(18));
        r.addNewT().setStringValue("1");
    }

    private static void docxParagraph(XWPFParagraph p, Block b) {
        int size = switch (b.type()) {
            case H1 -> 15;
            case H2 -> 13;
            default -> 12;
        };
        boolean heading = b.type() == BlockType.H1 || b.type() == BlockType.H2 || b.type() == BlockType.H3;
        String align = b.align() == null ? (b.type() == BlockType.H1 ? "center" : heading || b.bullet() != null ? "left" : "justify") : b.align();
        p.setAlignment(switch (align) {
            case "center" -> ParagraphAlignment.CENTER;
            case "right" -> ParagraphAlignment.RIGHT;
            case "left" -> ParagraphAlignment.LEFT;
            default -> ParagraphAlignment.BOTH;
        });
        if (b.indent() > 0) {
            p.setIndentationLeft(b.indent() * 20);
        }
        if (b.type() == BlockType.QUOTE) {
            p.setIndentationRight(360);
        }
        p.setSpacingBefore(heading ? 200 : 0);
        p.setSpacingAfter(heading ? 120 : b.bullet() != null ? 40 : 160);
        p.setSpacingBetween(1.5);
        if (b.bullet() != null) {
            XWPFRun r = p.createRun();
            r.setFontFamily("Times New Roman");
            r.setFontSize(size);
            r.setText(b.bullet() + " ");
        }
        for (Run run : b.runs()) {
            XWPFRun r = p.createRun();
            r.setFontFamily("Times New Roman");
            r.setFontSize(size);
            if (run.lineBreak()) {
                r.addBreak();
                continue;
            }
            r.setBold(run.bold() || heading);
            r.setItalic(run.italic());
            if (run.underline()) {
                r.setUnderline(UnderlinePatterns.SINGLE);
            }
            r.setStrikeThrough(run.strike());
            r.setText(run.text());
        }
    }

    private static void docxTable(XWPFDocument doc, Block b) {
        int cols = b.table().stream().mapToInt(List::size).max().orElse(1);
        XWPFTable table = doc.createTable(b.table().size(), cols);
        table.setWidth("100%");
        for (int i = 0; i < b.table().size(); i++) {
            XWPFTableRow row = table.getRow(i);
            List<List<Run>> cells = b.table().get(i);
            for (int j = 0; j < cols; j++) {
                XWPFTableCell cell = row.getCell(j);
                XWPFParagraph p = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
                if (j < cells.size()) {
                    for (Run run : cells.get(j)) {
                        XWPFRun r = p.createRun();
                        r.setFontFamily("Times New Roman");
                        r.setFontSize(11);
                        if (run.lineBreak()) {
                            r.addBreak();
                        } else {
                            r.setBold(run.bold());
                            r.setItalic(run.italic());
                            r.setText(run.text());
                        }
                    }
                }
            }
        }
        doc.createParagraph();
    }
}
