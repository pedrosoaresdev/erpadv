package br.com.erpadv.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditHasherTest {

    private AuditLog entry(long seq, String summary) {
        AuditLog log = new AuditLog();
        log.setSeq(seq);
        log.setAt(Instant.ofEpochMilli(1_700_000_000_000L + seq));
        log.setOfficeId("office1");
        log.setActorUid("uid1");
        log.setAction("CLIENT_UPDATED");
        log.setEntityType("client");
        log.setEntityId("c1");
        log.setSummary(summary);
        log.setDetails(Map.of("fields", "name"));
        return log;
    }

    @Test
    void cadeiaDetectaAlteracao() {
        AuditLog first = entry(1, "a");
        String h1 = AuditHasher.hash(AuditHasher.GENESIS, first);
        AuditLog second = entry(2, "b");
        String h2 = AuditHasher.hash(h1, second);

        assertThat(AuditHasher.hash(AuditHasher.GENESIS, entry(1, "a"))).isEqualTo(h1);
        assertThat(AuditHasher.hash(AuditHasher.GENESIS, entry(1, "A"))).isNotEqualTo(h1);
        // O hash do segundo depende do primeiro.
        assertThat(AuditHasher.hash("f".repeat(64), second)).isNotEqualTo(h2);
    }

    @Test
    void separadorEscapadoEvitaColisao() {
        AuditLog a = entry(1, "x|y");
        AuditLog b = entry(1, "x");
        b.setIp("y");
        assertThat(AuditHasher.canonical(a)).isNotEqualTo(AuditHasher.canonical(b));
    }
}
