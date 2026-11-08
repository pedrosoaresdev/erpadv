package br.com.erpadv.services;

import br.com.erpadv.models.LegalDocKind;
import br.com.erpadv.services.generation.HtmlDocument;
import br.com.erpadv.services.generation.LegalTemplates;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Fase 2 — editor jurídico (saneamento, campos, exportação) e recorrência da agenda. */
class OperationsUnitTest {

    @Test
    void saneamentoRemoveScriptsEventosLinksEEstilosArbitrarios() {
        String dirty = "<p style=\"text-align: center; color: red; background: url(javascript:alert(1))\" onclick=\"x()\">Olá "
                + "<a href=\"javascript:alert(1)\">clique</a><img src=x onerror=alert(1)><script>alert(1)</script>"
                + "<strong>forte</strong></p><hr class=\"page-break\"><hr class=\"outra\"><span class=\"field\">[cliente.nome]</span>"
                + "<span class=\"evil\" style=\"position:fixed\">s</span><iframe src=\"https://x\"></iframe>";
        String clean = HtmlDocument.sanitize(dirty);
        assertThat(clean).contains("text-align: center;").doesNotContain("color").doesNotContain("javascript")
                .doesNotContain("onclick").doesNotContain("<img").doesNotContain("<script").doesNotContain("<a ")
                .doesNotContain("iframe").doesNotContain("position").doesNotContain("evil")
                .contains("<strong>forte</strong>").contains("clique")
                .contains("<hr class=\"page-break\">").contains("<span class=\"field\">[cliente.nome]</span>");
        assertThat(clean).contains("<hr>");
    }

    @Test
    void camposSaoEscapadosEPendentesFicamDestacados() {
        String html = "<p>{{cliente.nome}}, {{cliente.documento}} — {{ processo.numero }}</p>";
        String out = LegalEditorService.render(html, Map.of("cliente.nome", "Ana <b>&</b> Filhos", "processo.numero", "0001234-56.2024.8.17.0001"));
        assertThat(out).contains("Ana &lt;b&gt;&amp;&lt;/b&gt; Filhos")
                .contains("<span class=\"field\">[cliente.documento]</span>")
                .contains("0001234-56.2024.8.17.0001");
        assertThat(LegalEditorService.missing(HtmlDocument.sanitize(out))).containsExactly("cliente.documento");
    }

    @Test
    void modelosDoSistemaCobremTodosOsTiposDaEspecificacao() {
        Set<LegalDocKind> covered = LegalTemplates.all().stream().map(LegalTemplates.SystemTemplate::kind).collect(Collectors.toSet());
        assertThat(covered).containsAll(EnumSet.allOf(LegalDocKind.class));
        for (LegalTemplates.SystemTemplate t : LegalTemplates.all()) {
            // o modelo já está no subconjunto aceito pelo saneador (nada é perdido ao gerar o documento)
            String rendered = LegalEditorService.render(t.content(), Map.of());
            assertThat(HtmlDocument.plainText(HtmlDocument.sanitize(rendered))).isEqualTo(HtmlDocument.plainText(rendered));
            assertThat(LegalTemplates.find(t.id())).isPresent();
        }
        assertThat(LegalTemplates.all().stream().map(LegalTemplates.SystemTemplate::id).distinct().count())
                .isEqualTo(LegalTemplates.all().size());
    }

    @Test
    void exportaPdfComNumeracaoEDocxComCabecalhoRodapeETabelas() throws Exception {
        String html = "<h1>PROCURAÇÃO</h1><p style=\"text-align: right;\">Recife, 8 de novembro de 2026.</p>"
                + "<p><strong>Negrito</strong> <em>itálico</em> <u>sublinhado</u> <s>tachado</s><br>linha 2</p>"
                + "<ol><li>primeiro</li><li>segundo</li></ol><ul><li>item</li></ul><blockquote>citação recuada</blockquote>"
                + "<table><tbody><tr><th>Cliente</th><td>Ana</td></tr></tbody></table><hr class=\"page-break\"><p>Segunda página</p>";
        HtmlDocument.Layout layout = new HtmlDocument.Layout("ESCRITÓRIO MODELO\nCNPJ 00.000.000/0001-00", "contato@escritorio.test", true);

        byte[] pdf = HtmlDocument.toPdf(html, layout);
        String head = new String(pdf, 0, 5, StandardCharsets.US_ASCII);
        assertThat(head).isEqualTo("%PDF-");
        try (var reader = new com.lowagie.text.pdf.PdfReader(pdf)) {
            assertThat(reader.getNumberOfPages()).isEqualTo(2);
            String page1 = new com.lowagie.text.pdf.parser.PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(page1).contains("PROCURAÇÃO").contains("Página 1 de").contains("ESCRITÓRIO MODELO").contains("contato@escritorio.test");
        }

        byte[] docx = HtmlDocument.toDocx(html, layout);
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            String text = doc.getParagraphs().stream().map(XWPFParagraph::getText).collect(Collectors.joining("\n"));
            assertThat(text).contains("PROCURAÇÃO").contains("1. primeiro").contains("2. segundo").contains("• item").contains("Segunda página");
            assertThat(doc.getTables()).hasSize(1);
            assertThat(doc.getTables().get(0).getText()).contains("Cliente").contains("Ana");
            assertThat(doc.getHeaderList().get(0).getText()).contains("ESCRITÓRIO MODELO");
            assertThat(doc.getFooterList().get(0).getText()).contains("contato@escritorio.test").contains("Página");
            assertThat(Arrays.toString(docx)).isNotEmpty();
        }
    }

    @Test
    void htmlVazioAindaGeraArquivosValidos() {
        assertThat(new String(HtmlDocument.toPdf("", new HtmlDocument.Layout(null, null, false)), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(HtmlDocument.toDocx("<script>x</script>", new HtmlDocument.Layout(null, null, false))[0]).isEqualTo((byte) 'P');
    }

    @Test
    void recorrenciaDaAgenda() {
        Instant base = Instant.parse("2026-01-31T13:00:00Z");
        assertThat(CalendarService.shift(base, "DIARIA", 2)).isEqualTo(Instant.parse("2026-02-02T13:00:00Z"));
        assertThat(CalendarService.shift(base, "SEMANAL", 1)).isEqualTo(Instant.parse("2026-02-07T13:00:00Z"));
        assertThat(CalendarService.shift(base, "QUINZENAL", 1)).isEqualTo(Instant.parse("2026-02-14T13:00:00Z"));
        // mensal ajusta para o último dia de meses mais curtos
        assertThat(CalendarService.shift(base, "MENSAL", 1).toString()).startsWith("2026-02-28");
    }

    @Test
    void nomeDeArquivoSeguro() {
        assertThat(LegalEditorService.fileBase("Petição Inicial — João / Banco: \"teste\"")).isEqualTo("peticao-inicial-joao-banco-teste");
        assertThat(LegalEditorService.fileBase("***")).isEqualTo("documento");
    }
}
