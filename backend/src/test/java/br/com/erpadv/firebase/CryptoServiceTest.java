package br.com.erpadv.firebase;

import br.com.erpadv.TestFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoServiceTest {

    private final CryptoService crypto = new CryptoService(TestFixtures.properties(false));

    @Test
    void criptografaEDescriptografa() {
        String enc = crypto.encrypt("52998224725");
        assertThat(enc).startsWith("v1:").doesNotContain("52998224725");
        assertThat(crypto.decrypt(enc)).isEqualTo("52998224725");
    }

    @Test
    void ivAleatorioGeraCifrasDiferentes() {
        assertThat(crypto.encrypt("abc")).isNotEqualTo(crypto.encrypt("abc"));
    }

    @Test
    void detectaAdulteracao() {
        String enc = crypto.encrypt("segredo");
        char last = enc.charAt(enc.length() - 2);
        String tampered = enc.substring(0, enc.length() - 2) + (last == 'A' ? 'B' : 'A') + enc.charAt(enc.length() - 1);
        assertThatThrownBy(() -> crypto.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hmacDeterministico() {
        assertThat(crypto.hmac("52998224725")).isEqualTo(crypto.hmac("52998224725"))
                .isNotEqualTo(crypto.hmac("11222333000181"));
    }

    @Test
    void recusaChavesDeDesenvolvimentoEmProducao() {
        assertThatThrownBy(() -> new CryptoService(TestFixtures.properties(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("produção");
    }
}
