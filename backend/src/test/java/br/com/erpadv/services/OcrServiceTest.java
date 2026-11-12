package br.com.erpadv.services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OcrServiceTest {

    @Test
    void palavrasDoConteudoParaBuscaSemNumeros() {
        List<String> t = OcrService.tokens("Contestação contestação CONTESTAÇÃO da ré. CPF 529.982.247-25, conta 12345-6; "
                + "indenização por danos morais; inconstitucionalidade");
        assertThat(t.get(0)).isEqualTo("contestacao");
        assertThat(t).contains("indenizacao", "danos", "morais").doesNotContain("da", "por", "529", "12345");
        // palavras longas ficam com 15 caracteres, como a busca as procura
        assertThat(t).contains("inconstituciona");
    }
}
