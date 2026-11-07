package br.com.erpadv.services;

import br.com.erpadv.models.Contract;
import br.com.erpadv.models.ContractEnums.FeeModel;
import br.com.erpadv.services.generation.DocumentExporter;
import br.com.erpadv.services.generation.SystemTemplates;
import br.com.erpadv.services.generation.TemplateRenderer;
import br.com.erpadv.services.generation.ValorExtenso;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContractGenerationTest {

    @Test
    void valorPorExtenso() {
        assertThat(ValorExtenso.reais(450000)).isEqualTo("quatro mil e quinhentos reais");
        assertThat(ValorExtenso.reais(1234567)).isEqualTo("doze mil trezentos e quarenta e cinco reais e sessenta e sete centavos");
        assertThat(ValorExtenso.reais(100)).isEqualTo("um real");
        assertThat(ValorExtenso.reais(50)).isEqualTo("cinquenta centavos");
        assertThat(ValorExtenso.reais(100_000_000)).isEqualTo("um milhão de reais");
        assertThat(ValorExtenso.reais(250_000_000)).isEqualTo("dois milhões e quinhentos mil reais");
        assertThat(ValorExtenso.reais(10_000)).isEqualTo("cem reais");
        assertThat(ValorExtenso.reais(10_100)).isEqualTo("cento e um reais");
        assertThat(ValorExtenso.reais(100_000)).isEqualTo("mil reais");
        assertThat(ValorExtenso.inteiro(1_001_500)).isEqualTo("um milhão, mil e quinhentos");
    }

    @Test
    void renderizaCamposEApontaPendencias() {
        var r = TemplateRenderer.render("Contratante: {{cliente.nome}}, CPF {{ cliente.documento }} — {{x.y}}",
                Map.of("cliente.nome", "Maria", "cliente.documento", "529.982.247-25"));
        assertThat(r.text()).isEqualTo("Contratante: Maria, CPF 529.982.247-25 — [x.y]");
        assertThat(r.missing()).containsExactly("x.y");
        assertThat(TemplateRenderer.fields(SystemTemplates.HONORARIOS)).contains("cliente.nome", "contrato.valorExtenso", "advogado.oab");
    }

    @Test
    void valorComCifraoNaoQuebraSubstituicao() {
        var r = TemplateRenderer.render("Valor: {{v}}", Map.of("v", "R$ 1.500,00 \\ $1"));
        assertThat(r.text()).isEqualTo("Valor: R$ 1.500,00 \\ $1");
    }

    @Test
    void exportaPdfEDocx() {
        String body = "# CONTRATO\n\n## CLÁUSULA 1ª\nTexto com acentuação — “aspas” e §.\ncontinua o parágrafo.\n\n> ______\n> Assinatura";
        byte[] pdf = DocumentExporter.toPdf(body, "CT-2026-0001");
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        byte[] docx = DocumentExporter.toDocx(body);
        assertThat(docx[0]).isEqualTo((byte) 'P');
        assertThat(docx[1]).isEqualTo((byte) 'K');
        assertThat(new String(docx, StandardCharsets.ISO_8859_1)).contains("word/document.xml");
    }

    @Test
    void exitoEPercentual() {
        assertThat(ContractService.successAmount(10_000_000, 2000)).isEqualTo(2_000_000);
        assertThat(ContractService.successAmount(333, 1500)).isEqualTo(50);
        assertThat(ContractService.percent(2000)).isEqualTo("20%");
        assertThat(ContractService.percent(1250)).isEqualTo("12,50%");
    }

    @Test
    void assinaturaDosHonorariosDetectaAlteracao() {
        Contract a = new Contract();
        a.setFeeModel(FeeModel.PARCELADO);
        a.setAmountCents(300000L);
        a.setInstallments(3);
        Contract b = new Contract();
        b.setFeeModel(FeeModel.PARCELADO);
        b.setAmountCents(300000L);
        b.setInstallments(3);
        assertThat(ContractService.feeSignature(a)).isEqualTo(ContractService.feeSignature(b));
        b.setAmountCents(310000L);
        assertThat(ContractService.feeSignature(a)).isNotEqualTo(ContractService.feeSignature(b));
    }

    @Test
    void modelosDoSistemaSaoSomenteLeituraEIdentificados() {
        assertThat(SystemTemplates.all()).allMatch(t -> t.isSystem() && t.getId().startsWith("system:"));
    }
}
