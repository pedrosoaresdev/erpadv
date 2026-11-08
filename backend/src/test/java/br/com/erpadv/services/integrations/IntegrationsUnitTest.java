package br.com.erpadv.services.integrations;

import br.com.erpadv.TestFixtures;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.firebase.CryptoService;
import br.com.erpadv.models.Appointment;
import br.com.erpadv.models.AppointmentType;
import br.com.erpadv.models.MovementType;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fase 4 — regras locais dos conectores (sem rede). */
class IntegrationsUnitTest {

    @Test
    void indiceDoDataJudPeloNumeroCnj() {
        assertThat(DataJudService.alias("0016718-82.2025.8.17.2480")).isEqualTo("tjpe");
        assertThat(DataJudService.alias("0000001-02.2024.8.26.0100")).isEqualTo("tjsp");
        assertThat(DataJudService.alias("0000001-02.2024.8.07.0001")).isEqualTo("tjdft");
        assertThat(DataJudService.alias("0000001-02.2024.4.05.8300")).isEqualTo("trf5");
        assertThat(DataJudService.alias("0000001-02.2024.5.06.0001")).isEqualTo("trt6");
        assertThat(DataJudService.alias("0000001-02.2024.5.00.0000")).isEqualTo("tst");
        assertThat(DataJudService.alias("0000001-02.2024.6.17.0001")).isEqualTo("tre-pe");
        assertThat(DataJudService.alias("0000001-02.2024.3.00.0000")).isEqualTo("stj");
        assertThat(DataJudService.alias("0000001-02.2024.9.13.0001")).isEqualTo("tjmmg");
        assertThat(DataJudService.alias("123")).isNull();
        assertThat(DataJudService.alias(null)).isNull();
    }

    @Test
    void tipoDaMovimentacaoPelaTabelaUnificadaSemAlterarSituacao() {
        assertThat(DataJudService.type("Despacho")).isEqualTo(MovementType.DESPACHO);
        assertThat(DataJudService.type("Decisão")).isEqualTo(MovementType.DECISAO);
        assertThat(DataJudService.type("Concessão de tutela de urgência")).isEqualTo(MovementType.DECISAO);
        assertThat(DataJudService.type("Procedência")).isEqualTo(MovementType.SENTENCA);
        assertThat(DataJudService.type("Trânsito em julgado")).isEqualTo(MovementType.TRANSITO_JULGADO);
        assertThat(DataJudService.type("Audiência de conciliação")).isEqualTo(MovementType.AUDIENCIA);
        assertThat(DataJudService.type("Expedição de documento")).isEqualTo(MovementType.MOVIMENTACAO);
    }

    @Test
    void oabEmFormatosDiversos() {
        assertThat(PublicationService.parseOab("PE 12345")).isEqualTo(new PublicationService.Oab("12345", "PE"));
        assertThat(PublicationService.parseOab("12.345/SP")).isEqualTo(new PublicationService.Oab("12345", "SP"));
        assertThat(PublicationService.parseOab("OAB/RJ 098765")).isEqualTo(new PublicationService.Oab("98765", "RJ"));
        assertThat(PublicationService.parseOab("pe12345")).isEqualTo(new PublicationService.Oab("12345", "PE"));
        assertThat(PublicationService.parseOab("12345")).isNull();
        assertThat(PublicationService.parseOab("XX 123")).isNull();
    }

    @Test
    void whatsappNumeroAssinaturaEMensagensRecebidas() throws Exception {
        assertThat(WhatsAppService.normalize("(81) 99999-1234")).isEqualTo("5581999991234");
        assertThat(WhatsAppService.normalize("+55 81 99999-1234")).isEqualTo("5581999991234");
        assertThatThrownBy(() -> WhatsAppService.normalize("1234")).isInstanceOf(BusinessException.class);
        JsonNode payload = HttpJson.JSON.readTree("""
                {"object":"whatsapp_business_account","entry":[{"id":"1","changes":[{"field":"messages","value":{
                 "contacts":[{"wa_id":"5581999991234","profile":{"name":"Bruno"}}],
                 "messages":[{"from":"5581999991234","id":"wamid.1","timestamp":"1762600000","type":"text","text":{"body":"Bom dia, doutor"}},
                             {"from":"5581999991234","id":"wamid.2","timestamp":"1762600001","type":"image","image":{"id":"x"}}]}}]}]}
                """);
        List<WhatsAppService.Incoming> in = WhatsAppService.parse(payload);
        assertThat(in).hasSize(2);
        assertThat(in.get(0).text()).isEqualTo("Bom dia, doutor");
        assertThat(in.get(0).name()).isEqualTo("Bruno");
        assertThat(in.get(1).text()).contains("image");
    }

    @Test
    void eventoDoGoogleComSalaDoMeetEDiaInteiro() {
        Appointment a = new Appointment();
        a.setId("ap1");
        a.setType(AppointmentType.REUNIAO);
        a.setTitle("Reunião com cliente");
        a.setStart(Instant.parse("2026-11-10T13:00:00Z"));
        a.setEnd(Instant.parse("2026-11-10T14:00:00Z"));
        a.setClientName("Bruno");
        a.setDescription("Pauta sigilosa");
        a.setRestricted(true);
        Map<String, Object> body = GoogleCalendarService.event(a, true);
        assertThat(body.get("summary")).isEqualTo("Reunião: Reunião com cliente");
        assertThat(String.valueOf(body.get("description"))).contains("Bruno").doesNotContain("Pauta sigilosa");
        assertThat(body).containsKey("conferenceData");
        assertThat(((Map<?, ?>) body.get("start")).get("dateTime")).isEqualTo("2026-11-10T13:00:00Z");
        a.setAllDay(true);
        assertThat(((Map<?, ?>) GoogleCalendarService.event(a, false).get("start")).get("date")).isEqualTo("2026-11-10");
    }

    @Test
    void estadoDoOauthAssinadoEComValidade() {
        CryptoService crypto = new CryptoService(TestFixtures.properties(false));
        GoogleCalendarService g = new GoogleCalendarService(null, null, crypto, null, null, "id", "secret", "https://x/cb",
                "https://auth", "https://token", "https://api", "https://app");
        String payload = "office1|uid1|" + (Instant.now().getEpochSecond() + 600) + "|nonce";
        String state = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes()) + "." + crypto.hmac("google-oauth|" + payload);
        assertThat(g.verifyState(state)).containsExactly("office1", "uid1", payload.split("\\|")[2], "nonce");
        assertThat(g.verifyState(state.substring(0, state.length() - 2) + "xx")).isNull();
        String expired = "office1|uid1|" + (Instant.now().getEpochSecond() - 5) + "|n";
        assertThat(g.verifyState(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(expired.getBytes()) + "."
                + crypto.hmac("google-oauth|" + expired))).isNull();
        assertThat(g.verifyState("lixo")).isNull();
    }

    @Test
    void csvComAspasESeparador() {
        assertThat(CaseImportService.split("a;\"b;c\";\"d \"\"e\"\"\";", ';')).containsExactly("a", "b;c", "d \"e\"", "");
    }
}
