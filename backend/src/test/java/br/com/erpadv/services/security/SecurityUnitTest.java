package br.com.erpadv.services.security;

import br.com.erpadv.audit.AuditChange;
import br.com.erpadv.audit.AuditDiff;
import br.com.erpadv.audit.AuditHasher;
import br.com.erpadv.audit.AuditLog;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Fase 5 — TOTP, compatibilidade da cadeia de auditoria, comparação antes/depois e utilitários de sessão e backup. */
class SecurityUnitTest {

    @Test
    void totpConfereComVetorDaRfc6238() {
        byte[] key = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        // RFC 6238, apêndice B (SHA-1): T=59 → 94287082; T=1111111109 → 07081804 (6 dígitos finais)
        assertThat(Totp.code(key, 59 / 30)).isEqualTo("287082");
        assertThat(Totp.code(key, 1111111109L / 30)).isEqualTo("081804");
        String b32 = Totp.base32(key);
        assertThat(Totp.decode(b32)).isEqualTo(key);
        assertThat(Totp.verify(b32, "287082", 0, 59)).isEqualTo(1);
        // o mesmo passo não vale duas vezes; tolerância de um passo para cada lado
        assertThat(Totp.verify(b32, "287082", 1, 59)).isEqualTo(-1);
        assertThat(Totp.verify(b32, Totp.code(key, 3), 0, 59)).isEqualTo(-1);
        assertThat(Totp.verify(b32, Totp.code(key, 2), 0, 59)).isEqualTo(2);
        assertThat(Totp.verify(b32, "12345", 0, 59)).isEqualTo(-1);
        assertThat(Totp.newSecret()).hasSize(32).matches("[A-Z2-7]+");
        assertThat(Totp.uri("ERPAdv", "dra@escritorio.test", "ABC")).startsWith("otpauth://totp/ERPAdv:dra%40escritorio.test?secret=ABC");
        assertThat(Totp.qrSvg("otpauth://totp/x")).startsWith("<svg").contains("<path");
    }

    @Test
    void registrosAntigosDaAuditoriaMantemOMesmoHash() {
        AuditLog old = new AuditLog();
        old.setSeq(7);
        old.setAt(Instant.ofEpochMilli(1_700_000_000_000L));
        old.setOfficeId("o1");
        old.setActorUid("u1");
        old.setAction("CLIENT_UPDATED");
        old.setEntityType("client");
        old.setEntityId("c1");
        old.setSummary("Cliente atualizado");
        old.setDetails(new java.util.TreeMap<>(Map.of("fields", "email")));
        // texto canônico da versão anterior (sem os campos da Fase 5)
        String expected = "7|1700000000000|o1|u1||CLIENT_UPDATED|client|c1|Cliente atualizado||fields=email|";
        assertThat(AuditHasher.canonical(old)).isEqualTo(expected);

        AuditLog v2 = new AuditLog();
        v2.setSeq(7);
        v2.setAt(old.getAt());
        v2.setOfficeId("o1");
        v2.setActorUid("u1");
        v2.setAction("CLIENT_UPDATED");
        v2.setEntityType("client");
        v2.setEntityId("c1");
        v2.setSummary("Cliente atualizado");
        v2.setDetails(old.getDetails());
        v2.setModule("Clientes");
        v2.setJustification("Pedido do cliente");
        v2.setChanges(List.of(new AuditChange("email", "a@x.test", "b@x.test")));
        assertThat(AuditHasher.canonical(v2)).startsWith(expected).contains("v2|").contains("Pedido do cliente").contains("b@x.test");
        String h = AuditHasher.hash(AuditHasher.GENESIS, v2);
        v2.getChanges().get(0).setAfter("c@x.test");
        assertThat(AuditHasher.hash(AuditHasher.GENESIS, v2)).isNotEqualTo(h);
    }

    @Test
    void comparacaoAntesDepoisIgnoraTecnicosEProtegeCifrados() {
        Map<String, Object> before = new HashMap<>();
        before.put("name", "Bruno");
        before.put("email", "bruno@x.test");
        before.put("documentEnc", "enc:v1:aaa");
        before.put("updatedAt", 1L);
        before.put("tags", List.of());
        before.put("amountCents", 1000);
        Map<String, Object> after = new HashMap<>(before);
        after.put("email", "bruno@y.test");
        after.put("documentEnc", "enc:v1:bbb");
        after.put("updatedAt", 2L);
        after.put("tags", null);
        after.put("amountCents", 1000L);
        after.put("notes", "");
        List<AuditChange> changes = AuditDiff.diff(before, after);
        assertThat(changes).extracting(AuditChange::getField).containsExactly("documentEnc", "email");
        assertThat(changes.get(0).getBefore()).isEqualTo("(protegido)").doesNotContain("aaa");
        assertThat(changes.get(0).getAfter()).doesNotContain("bbb");
        assertThat(changes.get(1).getBefore()).isEqualTo("bruno@x.test");
        assertThat(changes.get(1).getAfter()).isEqualTo("bruno@y.test");
        assertThat(AuditDiff.module("finance")).isEqualTo("Financeiro");
        assertThat(AuditDiff.module("desconhecido")).isEqualTo("Sistema");
    }

    @Test
    void dispositivoSessaoEBackup() {
        assertThat(SessionService.device("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"))
                .isEqualTo("Chrome · Windows");
        assertThat(SessionService.device("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1"))
                .isEqualTo("Safari · iPhone");
        assertThat(SessionService.device("Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/130.0 Safari/537.36 Edg/130.0")).isEqualTo("Edge · Windows");
        assertThat(SessionService.device(null)).isEqualTo("Dispositivo desconhecido");
        assertThat(SessionService.sessionId("u1", 100)).hasSize(32).isEqualTo(SessionService.sessionId("u1", 100))
                .isNotEqualTo(SessionService.sessionId("u1", 101));
        assertThat(BackupService.collectionOf("clients/abc")).isEqualTo("clients");
        assertThat(BackupService.collectionOf("clients/abc/interactions/x")).isEqualTo("clients/interactions");
        assertThat(BackupService.collectionOf("@users/u1")).isEqualTo("@users");
        Object enc = BackupService.encode(Map.of("at", com.google.cloud.Timestamp.ofTimeSecondsAndNanos(10, 0), "n", List.of(1, "x")));
        assertThat(enc.toString()).contains("$ts").contains("1970-01-01T00:00:10Z");
    }
}
