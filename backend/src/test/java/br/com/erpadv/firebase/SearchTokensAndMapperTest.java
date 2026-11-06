package br.com.erpadv.firebase;

import br.com.erpadv.models.CaseStatus;
import br.com.erpadv.models.LegalCase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SearchTokensAndMapperTest {

    @Test
    void normalizaAcentosEGeraPrefixos() {
        assertThat(SearchTokens.normalize("  João  da Conceição ")).isEqualTo("joao da conceicao");
        assertThat(SearchTokens.forText("João Silva")).contains("jo", "joa", "joao", "si", "silva")
                .doesNotContain("j");
    }

    @Test
    void telefoneGeraSufixos() {
        assertThat(SearchTokens.forPhone("(11) 99999-1234"))
                .contains("p:99991234", "p:999991234", "p:11999991234");
    }

    @Test
    void tokenDeConsultaUsaPalavraMaisLonga() {
        assertThat(SearchTokens.queryToken("ana carolina")).isEqualTo("carolina");
        assertThat(SearchTokens.queryToken("a")).isNull();
    }

    @Test
    void mapperGravaDatasOrdenaveis() {
        FirestoreMapper mapper = new FirestoreMapper();
        LegalCase c = new LegalCase();
        c.setId("x");
        c.setStatus(CaseStatus.EM_ANDAMENTO);
        c.setCreatedAt(Instant.ofEpochMilli(1_700_000_000_123L));
        c.setDistributionDate(LocalDate.of(2024, 3, 5));
        c.setClaimValueCents(1_500_000L);

        Map<String, Object> map = mapper.toMap(c);
        assertThat(map.get("createdAt")).isEqualTo(1_700_000_000_123L);
        assertThat(map.get("distributionDate")).isEqualTo("2024-03-05");
        assertThat(map.get("status")).isEqualTo("EM_ANDAMENTO");
        assertThat(map).doesNotContainKey("notes");

        LegalCase back = mapper.fromMap(map, LegalCase.class);
        assertThat(back.getCreatedAt()).isEqualTo(c.getCreatedAt());
        assertThat(back.getDistributionDate()).isEqualTo(c.getDistributionDate());
        assertThat(back.getClaimValueCents()).isEqualTo(1_500_000L);
    }
}
