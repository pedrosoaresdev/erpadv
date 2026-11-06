package br.com.erpadv.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BrazilianDocumentsTest {

    @Test
    void validaCpf() {
        assertThat(BrazilianDocuments.isValidCpf("529.982.247-25")).isTrue();
        assertThat(BrazilianDocuments.isValidCpf("52998224725")).isTrue();
        assertThat(BrazilianDocuments.isValidCpf("529.982.247-24")).isFalse();
        assertThat(BrazilianDocuments.isValidCpf("111.111.111-11")).isFalse();
        assertThat(BrazilianDocuments.isValidCpf("123")).isFalse();
    }

    @Test
    void validaCnpj() {
        assertThat(BrazilianDocuments.isValidCnpj("11.222.333/0001-81")).isTrue();
        assertThat(BrazilianDocuments.isValidCnpj("11.222.333/0001-80")).isFalse();
        assertThat(BrazilianDocuments.isValidCnpj("00000000000000")).isFalse();
    }

    @Test
    void mascaraPreservaApenasFinal() {
        assertThat(BrazilianDocuments.mask("52998224725")).isEqualTo("***.***.247-25");
        assertThat(BrazilianDocuments.mask("11222333000181")).isEqualTo("**.***.***/0001-81");
    }

    @Test
    void validaNumeroCnj() {
        assertThat(BrazilianDocuments.isValidCnj("1234567-13.2024.8.26.0100")).isTrue();
        assertThat(BrazilianDocuments.isValidCnj("0001234-71.2024.8.26.0100")).isTrue();
        assertThat(BrazilianDocuments.isValidCnj("1234567-14.2024.8.26.0100")).isFalse();
        assertThat(BrazilianDocuments.isValidCnj("123")).isFalse();
        assertThat(BrazilianDocuments.formatCnj("12345671320248260100")).isEqualTo("1234567-13.2024.8.26.0100");
    }

    @Test
    void anotacaoAceitaVazioERecusaInvalido() {
        CpfCnpj.Validator validator = new CpfCnpj.Validator();
        assertThat(validator.isValid(null, null)).isTrue();
        assertThat(validator.isValid("", null)).isTrue();
        assertThat(validator.isValid("529.982.247-25", null)).isTrue();
        assertThat(validator.isValid("529.982.247-00", null)).isFalse();
    }
}
