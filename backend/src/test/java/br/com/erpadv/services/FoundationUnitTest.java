package br.com.erpadv.services;

import br.com.erpadv.dto.DashboardDtos;
import br.com.erpadv.dto.DashboardDtos.NamedCount;
import br.com.erpadv.services.generation.ReportExporter;
import br.com.erpadv.services.generation.ReportExporter.Report;
import br.com.erpadv.services.generation.ReportExporter.Section;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Fase 1 — fundação: relatórios PDF/Excel, agrupamentos do dashboard e pesquisa global. */
class FoundationUnitTest {

    private static Report sample() {
        return new Report("Clientes cadastrados", "Escritório Teste", "2 cliente(s)", List.of(
                Section.fields("Dados", List.of(List.of("Nome", "Maria — Ação"), List.of("Situação", "Ativo"))),
                Section.table("Clientes", List.of("Nome", "Processos", "Observação"),
                        List.of(List.of("Ana", "3", "=HYPERLINK(\"http://x\")"), List.of("Bruno", "12", "+55 11")))), true);
    }

    @Test
    void geraPdfComCabecalhoEPaginas() {
        byte[] pdf = ReportExporter.pdf(sample());
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(pdf.length).isGreaterThan(1000);
    }

    @Test
    void geraExcelComNumerosSomaveisESemFormulasInjetadas() throws Exception {
        byte[] xlsx = ReportExporter.xlsx(sample());
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(2);
            Sheet clients = wb.getSheet("Clientes");
            // Linha 0 título, 1 escritório, 2 subtítulo, 3 vazia, 4 cabeçalho, 5+ dados.
            assertThat(clients.getRow(4).getCell(0).getStringCellValue()).isEqualTo("Nome");
            assertThat(clients.getRow(5).getCell(1).getNumericCellValue()).isEqualTo(3.0);
            assertThat(clients.getRow(5).getCell(2).getStringCellValue()).startsWith("'=");
            assertThat(clients.getRow(6).getCell(2).getStringCellValue()).startsWith("'+");
        }
    }

    @Test
    void exportaComNomeETipoCorretos() {
        ReportExporter.Exported x = ReportExporter.export(sample(), "xlsx", "clientes ação");
        assertThat(x.fileName()).isEqualTo("clientes-a-o.xlsx");
        assertThat(x.contentType()).isEqualTo(ReportExporter.XLSX);
        assertThat(ReportExporter.export(sample(), null, "r").contentType()).isEqualTo(ReportExporter.PDF);
    }

    @Test
    void agrupaTribunaisEOrigensMantendoOsMaioresESomandoOutros() {
        List<NamedCount> top = DashboardService.groupTop(List.of("TJSP", "TJSP", "TRT2", "TJRJ", "STJ", "TJSP"), 2);
        assertThat(top).extracting(NamedCount::label).containsExactly("TJSP", "STJ", "Outros");
        assertThat(top.get(0).value()).isEqualTo(3);
        assertThat(top.get(2).value()).isEqualTo(2);
        assertThat(DashboardService.normalizeCourt("  tjsp   1ª vara ")).isEqualTo("TJSP 1ª VARA");
        assertThat(DashboardService.normalizeCourt(null)).isEqualTo("Não informado");
        assertThat(DashboardService.sourceLabel("INDICACAO")).isEqualTo("Indicação");
        assertThat(DashboardService.sourceLabel(null)).isEqualTo("Não informada");
        assertThat(DashboardService.isActiveStatus("SUSPENSO")).isTrue();
        assertThat(DashboardService.isActiveStatus("ENCERRADO")).isFalse();
    }

    @Test
    void relatorioDoDashboardIncluiIndicadoresEComparacao() {
        var d = new DashboardDtos.DashboardResponse(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30),
                new DashboardDtos.ClientStats(10, 8, 2, 1, 4), new DashboardDtos.CaseStats(20, 15, 3, 2, 5),
                List.of(new DashboardDtos.MonthPoint("2026-06", 4, 10L)), List.of(), List.of(), List.of(), List.of(),
                new DashboardDtos.DeadlineStats(1, 2, 3, 4, 5, 0), new DashboardDtos.HearingStats(2, List.of()),
                new DashboardDtos.ContractStats(3, 1, 0), Map.of(), null,
                new DashboardDtos.DeadlineWindows(1, 3, 6), new DashboardDtos.CaseExtra(2, 4, 60), 7,
                List.of(new DashboardDtos.DeadlineMonth("2026-06", 5, 1)), List.of(new NamedCount("TJSP", "TJSP", 9)),
                List.of(new NamedCount("Indicação", "Indicação", 3)),
                List.of(new DashboardDtos.TeamMember("u1", "Dra. Ana", 9, 5, 1)),
                new DashboardDtos.Comparison(LocalDate.of(2025, 7, 1), LocalDate.of(2025, 12, 31), 2, 6, 4));
        Report report = DashboardService.buildReport(d, null, "Escritório X", "Dra. Ana");
        assertThat(report.subtitle()).contains("01/01/2026 a 30/06/2026").contains("Dra. Ana");
        assertThat(report.sections()).extracting(Section::title)
                .contains("Indicadores", "Processos por tribunal", "Origem dos novos clientes", "Desempenho da equipe")
                .doesNotContain("Financeiro (escritório)");
        assertThat(report.sections().get(0).rows()).anyMatch(r -> r.get(0).startsWith("Novos clientes")
                && r.get(1).contains("período anterior: 2"));
        assertThat(ReportExporter.pdf(report)).isNotEmpty();
    }

    @Test
    void pesquisaIgnoraAcentosEMaiusculas() {
        assertThat(SearchService.contains("Contestação — Silva", "contestacao")).isTrue();
        assertThat(SearchService.contains("Audiência de Instrução", "instrucao")).isTrue();
        assertThat(SearchService.contains(null, "x")).isFalse();
    }
}
