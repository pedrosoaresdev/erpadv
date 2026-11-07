package br.com.erpadv.services;

import br.com.erpadv.TestFixtures;
import br.com.erpadv.dto.FinanceDtos.Dre;
import br.com.erpadv.dto.FinanceDtos.DreLine;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.ForbiddenException;
import br.com.erpadv.firebase.FileInspector;
import br.com.erpadv.firebase.FileInspector.FileKind;
import br.com.erpadv.models.FinanceCategory;
import br.com.erpadv.models.FinanceScope;
import br.com.erpadv.security.Role;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinanceUnitTest {

    private static final EnumSet<FileKind> RECEIPTS = EnumSet.of(FileKind.PDF, FileKind.PNG, FileKind.JPEG);

    @Test
    void detectaTipoPelaAssinaturaBinaria() {
        byte[] pdf = "%PDF-1.7\n...".getBytes(StandardCharsets.US_ASCII);
        assertThat(FileInspector.inspect(pdf, "recibo.PDF", 1000, RECEIPTS).kind()).isEqualTo(FileKind.PDF);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0};
        assertThat(FileInspector.inspect(png, "foto.jpg", 1000, RECEIPTS).safeName()).isEqualTo("foto.png");
    }

    @Test
    void recusaArquivoDisfarcado() {
        byte[] exe = {'M', 'Z', (byte) 0x90, 0};
        assertThatThrownBy(() -> FileInspector.inspect(exe, "recibo.pdf", 1000, RECEIPTS))
                .isInstanceOf(BusinessException.class).hasMessageContaining("não permitido");
        byte[] html = "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> FileInspector.inspect(html, "x.png", 1000, RECEIPTS)).isInstanceOf(BusinessException.class);
    }

    @Test
    void recusaArquivoGrandeOuVazio() {
        byte[] pdf = "%PDF-1.7 conteudo".getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> FileInspector.inspect(pdf, "a.pdf", 5, RECEIPTS)).hasMessageContaining("limite");
        assertThatThrownBy(() -> FileInspector.inspect(new byte[0], "a.pdf", 5, RECEIPTS)).hasMessageContaining("vazio");
    }

    @Test
    void nomeSeguroSemCaminhoNemAcentos() {
        assertThat(FileInspector.safeName("../../etc/Comprovante Pix ção.pdf", FileKind.PDF)).isEqualTo("Comprovante_Pix_cao.pdf");
        assertThat(FileInspector.safeName("C:\\temp\\..\\.hidden.exe", FileKind.PDF)).isEqualTo("hidden.pdf");
        assertThat(FileInspector.safeName(null, FileKind.JPEG)).isEqualTo("arquivo.jpg");
    }

    @Test
    void dreGerencialRegimeDeCaixa() {
        Map<FinanceCategory, Long> v = new EnumMap<>(FinanceCategory.class);
        v.put(FinanceCategory.HONORARIOS, 100_000_00L);
        v.put(FinanceCategory.CONSULTORIA, 20_000_00L);
        v.put(FinanceCategory.IMPOSTOS, 12_000_00L);
        v.put(FinanceCategory.ALUGUEL, 8_000_00L);
        v.put(FinanceCategory.PESSOAL, 30_000_00L);
        Dre dre = FinanceReportService.dre(v);
        assertThat(dre.grossRevenueCents()).isEqualTo(120_000_00L);
        assertThat(dre.netRevenueCents()).isEqualTo(108_000_00L);
        assertThat(dre.expensesCents()).isEqualTo(38_000_00L);
        assertThat(dre.resultCents()).isEqualTo(70_000_00L);
        assertThat(dre.marginPercent()).isEqualTo(64.8);
        assertThat(dre.lines()).extracting(DreLine::label).contains("Receita bruta", "Receita líquida", "Resultado do período");
    }

    @Test
    void dreSemReceitaNaoCalculaMargem() {
        assertThat(FinanceReportService.dre(new EnumMap<>(FinanceCategory.class)).marginPercent()).isNull();
    }

    @Test
    void csvNeutralizaFormulasEEscapa() {
        assertThat(FinanceService.csv("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(FinanceService.csv("-10")).isEqualTo("'-10");
        assertThat(FinanceService.csv("A; B")).isEqualTo("\"A; B\"");
        assertThat(FinanceService.money(123456)).isEqualTo("1234,56");
    }

    @Test
    void escopoPessoalSoParaSocios() {
        var socio = TestFixtures.user("s", "o", Role.SOCIO_ADMIN);
        var financeiro = TestFixtures.user("f", "o", Role.FINANCEIRO);
        assertThat(FinanceService.resolveScope(socio, FinanceScope.PESSOAL)).isEqualTo(FinanceScope.PESSOAL);
        assertThat(FinanceService.resolveScope(financeiro, null)).isEqualTo(FinanceScope.ESCRITORIO);
        assertThatThrownBy(() -> FinanceService.resolveScope(financeiro, FinanceScope.PESSOAL))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void categoriasCoerentesComDirecao() {
        for (FinanceCategory c : FinanceCategory.values()) {
            assertThat(c.label()).isNotBlank();
            assertThat(c.group()).isNotBlank();
        }
        assertThat(FinanceCategory.HONORARIOS.isFee()).isTrue();
        assertThat(FinanceCategory.ALUGUEL.isFee()).isFalse();
    }
}
