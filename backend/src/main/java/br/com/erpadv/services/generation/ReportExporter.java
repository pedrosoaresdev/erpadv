package br.com.erpadv.services.generation;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Relatórios tabulares e fichas em PDF (OpenPDF) e Excel (Apache POI), com cabeçalho padrão:
 * escritório, título, período/filtros e data de geração; rodapé com numeração de páginas.
 */
public final class ReportExporter {

    public static final String PDF = "application/pdf";
    public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.of("pt", "BR"));
    private static final Color NAVY = new Color(11, 31, 58);
    private static final Color GOLD = new Color(197, 164, 109);
    private static final Color ZEBRA = new Color(245, 247, 250);

    /** Seção: tabela (cabeçalhos + linhas) ou lista de pares rótulo/valor quando {@code keyValue}. */
    public record Section(String title, List<String> headers, List<List<String>> rows, boolean keyValue) {
        public static Section table(String title, List<String> headers, List<List<String>> rows) {
            return new Section(title, headers, rows, false);
        }

        public static Section fields(String title, List<List<String>> pairs) {
            return new Section(title, List.of("Campo", "Valor"), pairs, true);
        }
    }

    public record Report(String title, String officeName, String subtitle, List<Section> sections, boolean landscape) {
    }

    public record Exported(byte[] bytes, String fileName, String contentType) {
    }

    private ReportExporter() {
    }

    public static Exported export(Report report, String format, String baseName) {
        String safe = baseName.replaceAll("[^A-Za-z0-9._-]+", "-");
        return switch (format == null ? "pdf" : format.toLowerCase(Locale.ROOT)) {
            case "xlsx", "excel" -> new Exported(xlsx(report), safe + ".xlsx", XLSX);
            default -> new Exported(pdf(report), safe + ".pdf", PDF);
        };
    }

    static String generatedAt() {
        return ZonedDateTime.now(ZONE).format(STAMP);
    }

    // ------------------------------------------------------------------ PDF

    public static byte[] pdf(Report report) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(report.landscape() ? PageSize.A4.rotate() : PageSize.A4, 36, 36, 40, 44);
        try {
            BaseFont regular = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, false);
            BaseFont bold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, false);
            Font office = new Font(bold, 9, Font.NORMAL, GOLD);
            Font title = new Font(bold, 16, Font.NORMAL, NAVY);
            Font sub = new Font(regular, 9, Font.NORMAL, Color.DARK_GRAY);
            Font section = new Font(bold, 11, Font.NORMAL, NAVY);
            Font head = new Font(bold, 8.5f, Font.NORMAL, Color.WHITE);
            Font cell = new Font(regular, 8.5f);
            Font label = new Font(bold, 9);
            Font footer = new Font(regular, 8, Font.NORMAL, Color.GRAY);

            PdfWriter writer = PdfWriter.getInstance(doc, out);
            String stamp = generatedAt();
            writer.setPageEvent(new PdfPageEventHelper() {
                @Override
                public void onEndPage(PdfWriter w, Document d) {
                    ColumnText.showTextAligned(w.getDirectContent(), Element.ALIGN_LEFT,
                            new Phrase("ERPAdv · " + safe(report.officeName()) + " · gerado em " + stamp, footer),
                            d.left(), d.bottom() - 18, 0);
                    ColumnText.showTextAligned(w.getDirectContent(), Element.ALIGN_RIGHT,
                            new Phrase("Página " + w.getPageNumber(), footer), d.right(), d.bottom() - 18, 0);
                }
            });
            doc.open();
            doc.add(new Paragraph(safe(report.officeName()).toUpperCase(Locale.ROOT), office));
            Paragraph t = new Paragraph(safe(report.title()), title);
            t.setSpacingBefore(2);
            doc.add(t);
            Paragraph s = new Paragraph((report.subtitle() == null || report.subtitle().isBlank() ? "" : report.subtitle() + " · ")
                    + "Gerado em " + stamp, sub);
            s.setSpacingAfter(10);
            doc.add(s);

            for (Section sec : report.sections()) {
                Paragraph h = new Paragraph(safe(sec.title()), section);
                h.setSpacingBefore(8);
                h.setSpacingAfter(5);
                doc.add(h);
                if (sec.rows().isEmpty()) {
                    doc.add(new Paragraph("Nenhum registro.", sub));
                    continue;
                }
                PdfPTable table = new PdfPTable(sec.keyValue() ? 2 : sec.headers().size());
                table.setWidthPercentage(100);
                if (sec.keyValue()) {
                    table.setWidths(new float[]{1.1f, 2.9f});
                    for (List<String> pair : sec.rows()) {
                        table.addCell(cell(safe(pair.get(0)), label, ZEBRA));
                        table.addCell(cell(pair.size() > 1 ? safe(pair.get(1)) : "", cell, null));
                    }
                } else {
                    table.setHeaderRows(1);
                    for (String hd : sec.headers()) {
                        table.addCell(cell(hd, head, NAVY));
                    }
                    int i = 0;
                    for (List<String> row : sec.rows()) {
                        Color bg = i++ % 2 == 1 ? ZEBRA : null;
                        for (int c = 0; c < sec.headers().size(); c++) {
                            table.addCell(cell(c < row.size() ? safe(row.get(c)) : "", cell, bg));
                        }
                    }
                }
                doc.add(table);
            }
            doc.close();
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("Falha ao gerar PDF", e);
        }
        return out.toByteArray();
    }

    private static PdfPCell cell(String text, Font font, Color background) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setPadding(4);
        c.setBorderColor(new Color(225, 229, 236));
        c.setBorder(Rectangle.BOX);
        if (background != null) {
            c.setBackgroundColor(background);
        }
        return c;
    }

    // ------------------------------------------------------------------ Excel

    public static byte[] xlsx(Report report) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle titleStyle = wb.createCellStyle();
            org.apache.poi.ss.usermodel.Font tf = wb.createFont();
            tf.setBold(true);
            tf.setFontHeightInPoints((short) 14);
            titleStyle.setFont(tf);
            CellStyle headStyle = wb.createCellStyle();
            org.apache.poi.ss.usermodel.Font hf = wb.createFont();
            hf.setBold(true);
            hf.setColor(IndexedColors.WHITE.getIndex());
            headStyle.setFont(hf);
            headStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headStyle.setBorderBottom(BorderStyle.THIN);

            Set<String> used = new HashSet<>();
            for (Section sec : report.sections()) {
                Sheet sheet = wb.createSheet(sheetName(sec.title(), used));
                int r = 0;
                Row t = sheet.createRow(r++);
                text(t.createCell(0), safe(report.title()) + " — " + safe(sec.title())).setCellStyle(titleStyle);
                text(sheet.createRow(r++).createCell(0), safe(report.officeName()));
                text(sheet.createRow(r++).createCell(0), (report.subtitle() == null ? "" : report.subtitle() + " · ")
                        + "Gerado em " + generatedAt());
                r++;
                Row header = sheet.createRow(r++);
                for (int c = 0; c < sec.headers().size(); c++) {
                    text(header.createCell(c), sec.headers().get(c)).setCellStyle(headStyle);
                }
                for (List<String> row : sec.rows()) {
                    Row x = sheet.createRow(r++);
                    for (int c = 0; c < row.size(); c++) {
                        value(x.createCell(c), row.get(c));
                    }
                }
                for (int c = 0; c < sec.headers().size(); c++) {
                    sheet.autoSizeColumn(c);
                    sheet.setColumnWidth(c, Math.min(sheet.getColumnWidth(c) + 512, 60 * 256));
                }
            }
            if (report.sections().isEmpty()) {
                wb.createSheet("Relatório");
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao gerar planilha", e);
        }
    }

    /** Neutraliza injeção de fórmulas e grava números como números (somáveis no Excel). */
    private static void value(Cell cell, String value) {
        if (value != null && value.matches("-?\\d{1,15}")) {
            cell.setCellValue(Long.parseLong(value));
            return;
        }
        text(cell, value);
    }

    private static Cell text(Cell cell, String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        cell.setCellValue(v);
        return cell;
    }

    private static String sheetName(String title, Set<String> used) {
        String base = (title == null || title.isBlank() ? "Dados" : title).replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
        base = base.length() > 28 ? base.substring(0, 28) : base;
        String name = base;
        int n = 2;
        while (!used.add(name.toLowerCase(Locale.ROOT))) {
            name = (base.length() > 25 ? base.substring(0, 25) : base) + " " + n++;
        }
        return name;
    }

    /** Remove caracteres fora do CP1252 (as fontes base do PDF não os desenham). */
    static String safe(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (char ch : text.toCharArray()) {
            if (ch == '—' || ch == '–' || ch == '•' || ch == '…' || ch == '“' || ch == '”' || ch == '‘' || ch == '’' || ch < 0x100) {
                sb.append(ch);
            } else if (ch == '→') {
                sb.append("->");
            } else {
                sb.append('?');
            }
        }
        return sb.toString();
    }
}
