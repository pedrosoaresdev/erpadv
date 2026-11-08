package br.com.erpadv.security;

import br.com.erpadv.dto.CursorPage;
import br.com.erpadv.models.UserProfile;
import br.com.erpadv.models.UserStatus;
import br.com.erpadv.services.AuthService;
import br.com.erpadv.services.ClientService;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.messaging.FirebaseMessaging;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testes de autenticação e autorização ponta a ponta na camada HTTP (filtro de token, RBAC,
 * bloqueio, revogação de sessão, isolamento de escritório). O Firebase é simulado com Mockito.
 */
@SpringBootTest(properties = {
        "app.security.data-encryption-key=ZGV2LW9ubHkta2V5LWNoYW5nZS1tZS0zMi1ieXRlcyE=",
        "app.security.search-hmac-key=ZGV2LW9ubHktaG1hYy1rZXktY2hhbmdlLW1lLTMyYnl0ZXM=",
        "app.jobs.enabled=false",
        "app.security.rate-limit-per-minute=1000",
        "app.platform.master-emails=master@teste.com"
})
@AutoConfigureMockMvc
class SecurityIntegrationTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    FirebaseApp firebaseApp;
    @MockitoBean
    FirebaseAuth firebaseAuth;
    @MockitoBean
    Firestore firestore;
    @MockitoBean
    FirebaseMessaging firebaseMessaging;
    @MockitoBean
    UserProfileCache profileCache;
    @MockitoBean
    OfficeStatusCache officeStatusCache;
    @MockitoBean
    br.com.erpadv.services.PlatformService platformService;
    @MockitoBean
    br.com.erpadv.services.BoardService boardService;
    @MockitoBean
    br.com.erpadv.services.CrmService crmService;
    @MockitoBean
    br.com.erpadv.services.RoleService roleService;
    @MockitoBean
    br.com.erpadv.services.SearchService searchService;
    @MockitoBean
    br.com.erpadv.services.CaseService caseService;
    @MockitoBean
    ClientService clientService;
    @MockitoBean
    AuthService authService;
    @MockitoBean
    br.com.erpadv.services.DeadlineService deadlineService;
    @MockitoBean
    br.com.erpadv.services.CalendarService calendarService;
    @MockitoBean
    br.com.erpadv.services.FinanceService financeService;
    @MockitoBean
    br.com.erpadv.services.FinanceReportService financeReportService;
    @MockitoBean
    br.com.erpadv.services.DocumentService documentService;
    @MockitoBean
    br.com.erpadv.services.ContractService contractService;
    @MockitoBean
    br.com.erpadv.services.LegalEditorService legalEditorService;
    @MockitoBean
    br.com.erpadv.services.FeeService feeService;
    @MockitoBean
    br.com.erpadv.services.ReportCenterService reportCenterService;
    @MockitoBean
    br.com.erpadv.services.FinanceSetupService financeSetupService;

    private void token(String token, String uid, String officeId, Role role, long iatSeconds) throws Exception {
        FirebaseToken decoded = mock(FirebaseToken.class);
        Map<String, Object> claims = new HashMap<>();
        if (officeId != null) {
            claims.put("officeId", officeId);
            claims.put("role", role.name());
        }
        claims.put("iat", iatSeconds);
        when(decoded.getUid()).thenReturn(uid);
        when(decoded.getEmail()).thenReturn(uid + "@teste.com");
        when(decoded.isEmailVerified()).thenReturn(true);
        when(decoded.getClaims()).thenReturn(claims);
        when(firebaseAuth.verifyIdToken(token)).thenReturn(decoded);
    }

    private void profile(String uid, String officeId, Role role, UserStatus status, Instant revokedAt) {
        UserProfile p = new UserProfile();
        p.setId(uid);
        p.setOfficeId(officeId);
        p.setRole(role);
        p.setStatus(status);
        p.setTokensRevokedAt(revokedAt);
        when(profileCache.get(uid)).thenReturn(Optional.of(p));
    }

    private long now() {
        return Instant.now().getEpochSecond();
    }

    @Test
    void semTokenRetorna401() throws Exception {
        mvc.perform(get("/api/clients"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void tokenInvalidoRetorna401() throws Exception {
        when(firebaseAuth.verifyIdToken("ruim")).thenThrow(mock(FirebaseAuthException.class));
        mvc.perform(get("/api/clients").header("Authorization", "Bearer ruim"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void advogadoListaClientes() throws Exception {
        token("t-adv", "adv", "office1", Role.ADVOGADO, now());
        profile("adv", "office1", Role.ADVOGADO, UserStatus.ACTIVE, null);
        when(clientService.list(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(new CursorPage<>(List.of(), null));
        mvc.perform(get("/api/clients").header("Authorization", "Bearer t-adv"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void estagiarioNaoCadastraCliente() throws Exception {
        token("t-est", "est", "office1", Role.ESTAGIARIO, now());
        profile("est", "office1", Role.ESTAGIARIO, UserStatus.ACTIVE, null);
        mvc.perform(post("/api/clients").header("Authorization", "Bearer t-est")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"PF\",\"name\":\"X\",\"document\":\"52998224725\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void financeiroNaoAcessaProcessos() throws Exception {
        token("t-fin", "fin", "office1", Role.FINANCEIRO, now());
        profile("fin", "office1", Role.FINANCEIRO, UserStatus.ACTIVE, null);
        mvc.perform(get("/api/cases").header("Authorization", "Bearer t-fin"))
                .andExpect(status().isForbidden());
    }

    @Test
    void contaBloqueadaRetorna403() throws Exception {
        token("t-blk", "blk", "office1", Role.ADVOGADO, now());
        profile("blk", "office1", Role.ADVOGADO, UserStatus.BLOCKED, null);
        mvc.perform(get("/api/clients").header("Authorization", "Bearer t-blk"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"));
    }

    @Test
    void sessaoRevogadaRetorna401() throws Exception {
        token("t-rev", "rev", "office1", Role.ADVOGADO, now() - 3600);
        profile("rev", "office1", Role.ADVOGADO, UserStatus.ACTIVE, Instant.now().minusSeconds(60));
        mvc.perform(get("/api/clients").header("Authorization", "Bearer t-rev"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
    }

    @Test
    void claimDeEscritorioDivergenteDoPerfilERecusado() throws Exception {
        token("t-x", "x", "officeA", Role.SOCIO_ADMIN, now());
        profile("x", "officeB", Role.SOCIO_ADMIN, UserStatus.ACTIVE, null);
        mvc.perform(get("/api/clients").header("Authorization", "Bearer t-x"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PROFILE_MISMATCH"));
    }

    @Test
    void usuarioSemEscritorioSoAcessaOnboarding() throws Exception {
        token("t-new", "novo", null, null, now());
        mvc.perform(get("/api/clients").header("Authorization", "Bearer t-new"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer t-new"))
                .andExpect(status().isOk());
    }

    @Test
    void clienteDoPortalNaoAcessaApiInterna() throws Exception {
        token("t-cli", "cli", "office1", Role.CLIENTE, now());
        profile("cli", "office1", Role.CLIENTE, UserStatus.ACTIVE, null);
        mvc.perform(get("/api/clients").header("Authorization", "Bearer t-cli"))
                .andExpect(status().isForbidden());
    }

    @Test
    void validacaoRetornaCamposInvalidos() throws Exception {
        token("t-adv2", "adv2", "office1", Role.ADVOGADO, now());
        profile("adv2", "office1", Role.ADVOGADO, UserStatus.ACTIVE, null);
        mvc.perform(post("/api/clients").header("Authorization", "Bearer t-adv2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"PF\",\"name\":\"\",\"document\":\"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.name").exists())
                .andExpect(jsonPath("$.fields.document").exists());
    }

    @Test
    void healthEhPublico() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }

    @Test
    void rotasForaDaApiSaoNegadas() throws Exception {
        token("t-adv3", "adv3", "office1", Role.ADVOGADO, now());
        profile("adv3", "office1", Role.ADVOGADO, UserStatus.ACTIVE, null);
        mvc.perform(get("/qualquer").header("Authorization", "Bearer t-adv3"))
                .andExpect(status().isForbidden());
    }

    @Test
    void onboardingExigeAutenticacao() throws Exception {
        mvc.perform(post("/api/onboarding/office").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        token("t-on", "on", null, null, now());
        mvc.perform(post("/api/onboarding/office").header("Authorization", "Bearer t-on")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest());
        verify(authService, never()).createOffice(any(), any());
    }

    // ------------------------------------------------------------------ Etapa 9: prazos e agenda

    @Test
    void estagiarioConsultaMasNaoCadastraPrazo() throws Exception {
        token("t-est9", "est9", "office1", Role.ESTAGIARIO, now());
        profile("est9", "office1", Role.ESTAGIARIO, UserStatus.ACTIVE, null);
        when(deadlineService.list(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(new CursorPage<>(List.of(), null));
        mvc.perform(get("/api/deadlines").header("Authorization", "Bearer t-est9"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/deadlines").header("Authorization", "Bearer t-est9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Contestação\",\"type\":\"CONTESTACAO\",\"startDate\":\"2026-11-02\","
                                + "\"days\":15,\"countingMode\":\"UTEIS\",\"responsibleUid\":\"est9\",\"priority\":\"ALTA\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/calendar/appointments").header("Authorization", "Bearer t-est9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"REUNIAO\",\"title\":\"Reunião\",\"start\":\"2026-11-10T13:00:00Z\","
                                + "\"end\":\"2026-11-10T14:00:00Z\",\"responsibleUids\":[\"est9\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void financeiroNaoAcessaPrazos() throws Exception {
        token("t-fin9", "fin9", "office1", Role.FINANCEIRO, now());
        profile("fin9", "office1", Role.FINANCEIRO, UserStatus.ACTIVE, null);
        mvc.perform(get("/api/deadlines").header("Authorization", "Bearer t-fin9"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/calendar/events").param("from", "2026-11-01T00:00:00Z").param("to", "2026-12-01T00:00:00Z")
                        .header("Authorization", "Bearer t-fin9"))
                .andExpect(status().isForbidden());
    }

    @Test
    void secretariaConsultaAgenda() throws Exception {
        token("t-sec9", "sec9", "office1", Role.SECRETARIA, now());
        profile("sec9", "office1", Role.SECRETARIA, UserStatus.ACTIVE, null);
        when(calendarService.events(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(List.of());
        mvc.perform(get("/api/calendar/events").param("from", "2026-11-01T00:00:00Z").param("to", "2026-12-01T00:00:00Z")
                        .header("Authorization", "Bearer t-sec9"))
                .andExpect(status().isOk());
    }

    @Test
    void prazoExigeCamposObrigatorios() throws Exception {
        token("t-adv9", "adv9", "office1", Role.ADVOGADO, now());
        profile("adv9", "office1", Role.ADVOGADO, UserStatus.ACTIVE, null);
        mvc.perform(post("/api/deadlines").header("Authorization", "Bearer t-adv9")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"days\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.description").exists())
                .andExpect(jsonPath("$.fields.days").exists())
                .andExpect(jsonPath("$.fields.responsibleUid").exists());
    }

    // ------------------------------------------------------------------ Etapa 10: financeiro

    @Test
    void financeiroAcessaContasEAdvogadoNao() throws Exception {
        token("t-fin10", "fin10", "office1", Role.FINANCEIRO, now());
        profile("fin10", "office1", Role.FINANCEIRO, UserStatus.ACTIVE, null);
        when(financeService.list(any(), any(), any(), anyInt())).thenReturn(new CursorPage<>(List.of(), null));
        mvc.perform(get("/api/finance/transactions").header("Authorization", "Bearer t-fin10"))
                .andExpect(status().isOk());

        token("t-adv10", "adv10", "office1", Role.ADVOGADO, now());
        profile("adv10", "office1", Role.ADVOGADO, UserStatus.ACTIVE, null);
        mvc.perform(get("/api/finance/transactions").header("Authorization", "Bearer t-adv10"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/finance/dashboard").header("Authorization", "Bearer t-adv10"))
                .andExpect(status().isForbidden());
    }

    @Test
    void secretariaNaoLancaNemBaixaContas() throws Exception {
        token("t-sec10", "sec10", "office1", Role.SECRETARIA, now());
        profile("sec10", "office1", Role.SECRETARIA, UserStatus.ACTIVE, null);
        mvc.perform(post("/api/finance/transactions").header("Authorization", "Bearer t-sec10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\":\"RECEITA\",\"category\":\"HONORARIOS\",\"description\":\"x\","
                                + "\"amountCents\":1000,\"dueDate\":\"2026-12-01\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/finance/transactions/abc/pay").header("Authorization", "Bearer t-sec10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paidDate\":\"2026-11-01\",\"paidAmountCents\":1000,\"paymentMethod\":\"PIX\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void lancamentoValidaValorPositivo() throws Exception {
        token("t-fin11", "fin11", "office1", Role.FINANCEIRO, now());
        profile("fin11", "office1", Role.FINANCEIRO, UserStatus.ACTIVE, null);
        mvc.perform(post("/api/finance/transactions").header("Authorization", "Bearer t-fin11")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\":\"RECEITA\",\"category\":\"HONORARIOS\",\"description\":\"x\","
                                + "\"amountCents\":-5,\"dueDate\":\"2026-12-01\",\"installments\":99}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.amountCents").exists())
                .andExpect(jsonPath("$.fields.installments").exists());
    }

    // ------------------------------------------------------------------ Etapa 11: documentos e contratos

    @Test
    void estagiarioConsultaDocumentosMasNaoEnvia() throws Exception {
        token("t-est11", "est11", "office1", Role.ESTAGIARIO, now());
        profile("est11", "office1", Role.ESTAGIARIO, UserStatus.ACTIVE, null);
        when(documentService.list(any(), any(), any(), anyInt())).thenReturn(new CursorPage<>(List.of(), null));
        mvc.perform(get("/api/documents").header("Authorization", "Bearer t-est11")).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/documents")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "a.pdf", "application/pdf", "%PDF-1.4".getBytes()))
                        .file(new org.springframework.mock.web.MockMultipartFile("meta", "", "application/json",
                                "{\"title\":\"x\",\"category\":\"PETICAO\"}".getBytes()))
                        .header("Authorization", "Bearer t-est11"))
                .andExpect(status().isForbidden());
    }

    @Test
    void financeiroLeContratosMasNaoAltera() throws Exception {
        token("t-fin12", "fin12", "office1", Role.FINANCEIRO, now());
        profile("fin12", "office1", Role.FINANCEIRO, UserStatus.ACTIVE, null);
        when(contractService.list(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), anyInt()))
                .thenReturn(new CursorPage<>(List.of(), null));
        mvc.perform(get("/api/contracts").header("Authorization", "Bearer t-fin12")).andExpect(status().isOk());
        mvc.perform(post("/api/contracts/abc/activate").header("Authorization", "Bearer t-fin12"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/documents").header("Authorization", "Bearer t-fin12"))
                .andExpect(status().isForbidden());
    }

    @Test
    void secretariaNaoAcessaContratos() throws Exception {
        token("t-sec12", "sec12", "office1", Role.SECRETARIA, now());
        profile("sec12", "office1", Role.SECRETARIA, UserStatus.ACTIVE, null);
        mvc.perform(get("/api/contracts").header("Authorization", "Bearer t-sec12")).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ painel master, cargos, quadros e CRM

    @Test
    void masterDaPlataformaAcessaPainelSemEscritorio() throws Exception {
        token("t-master", "master", null, null, now());
        when(platformService.overview()).thenReturn(new br.com.erpadv.dto.PlatformDtos.PlatformOverview(
                new br.com.erpadv.dto.PlatformDtos.PlatformStats(0, 0, 0, 0, 0), List.of()));
        mvc.perform(get("/api/platform/offices").header("Authorization", "Bearer t-master"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.offices").value(0));
        // Sem escritório, o master não usa os módulos internos.
        mvc.perform(get("/api/clients").header("Authorization", "Bearer t-master"))
                .andExpect(status().isForbidden());
    }

    @Test
    void socioAdministradorNaoAcessaPainelDaPlataforma() throws Exception {
        token("t-socio", "socio", "office1", Role.SOCIO_ADMIN, now());
        profile("socio", "office1", Role.SOCIO_ADMIN, UserStatus.ACTIVE, null);
        mvc.perform(get("/api/platform/offices").header("Authorization", "Bearer t-socio"))
                .andExpect(status().isForbidden());
        verify(platformService, never()).overview();
    }

    @Test
    void criacaoDeEscritorioValidaDadosDoAdministrador() throws Exception {
        token("t-master2", "master", null, null, now());
        mvc.perform(post("/api/platform/offices").header("Authorization", "Bearer t-master2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Escritório\",\"plan\":\"PROFISSIONAL\",\"admin\":{\"fullName\":\"\","
                                + "\"cpf\":\"\",\"oabNumber\":\"#\",\"email\":\"x\",\"password\":\"123\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields['admin.fullName']").exists())
                .andExpect(jsonPath("$.fields['admin.password']").exists());
    }

    @Test
    void escritorioSuspensoBloqueiaEquipe() throws Exception {
        token("t-susp", "susp", "officeS", Role.ADVOGADO, now());
        profile("susp", "officeS", Role.ADVOGADO, UserStatus.ACTIVE, null);
        when(officeStatusCache.isSuspended("officeS")).thenReturn(true);
        mvc.perform(get("/api/clients").header("Authorization", "Bearer t-susp"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OFFICE_SUSPENDED"));
    }

    @Test
    void estagiarioUsaQuadrosMasNaoOCrmNemCargos() throws Exception {
        token("t-est9", "est9", "office1", Role.ESTAGIARIO, now());
        profile("est9", "office1", Role.ESTAGIARIO, UserStatus.ACTIVE, null);
        when(boardService.list(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(List.of());
        mvc.perform(get("/api/boards").header("Authorization", "Bearer t-est9")).andExpect(status().isOk());
        mvc.perform(get("/api/crm/leads").header("Authorization", "Bearer t-est9")).andExpect(status().isForbidden());
        mvc.perform(post("/api/roles").header("Authorization", "Bearer t-est9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"baseRole\":\"ESTAGIARIO\",\"permissions\":[\"CLIENTS_READ\"]}"))
                .andExpect(status().isForbidden());
        verify(crmService, never()).list(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void financeiroNaoConverteOportunidadeEmPrazoNemCliente() throws Exception {
        token("t-fin9", "fin9", "office1", Role.FINANCEIRO, now());
        profile("fin9", "office1", Role.FINANCEIRO, UserStatus.ACTIVE, null);
        mvc.perform(get("/api/crm/metrics").header("Authorization", "Bearer t-fin9")).andExpect(status().isForbidden());
        mvc.perform(post("/api/boards/cards/c1/deadline").header("Authorization", "Bearer t-fin9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Contestação\",\"type\":\"CONTESTACAO\",\"startDate\":\"2026-11-09\","
                                + "\"days\":15,\"countingMode\":\"UTEIS\",\"responsibleUid\":\"u1\",\"priority\":\"ALTA\"}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ Fase 1: exclusão, exportação e pesquisa global

    @Test
    void somenteQuemPodeExcluiRemoveClienteEProcesso() throws Exception {
        token("t-adv7", "adv7", "office1", Role.ADVOGADO, now());
        profile("adv7", "office1", Role.ADVOGADO, UserStatus.ACTIVE, null);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/clients/c1")
                        .header("Authorization", "Bearer t-adv7"))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/cases/p1")
                        .header("Authorization", "Bearer t-adv7"))
                .andExpect(status().isForbidden());
        verify(clientService, never()).delete(any(), any(), any());

        token("t-soc7", "soc7", "office1", Role.SOCIO_ADMIN, now());
        profile("soc7", "office1", Role.SOCIO_ADMIN, UserStatus.ACTIVE, null);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/clients/c1")
                        .param("reason", "cadastro em duplicidade").header("Authorization", "Bearer t-soc7"))
                .andExpect(status().isNoContent());
        verify(clientService).delete(any(), org.mockito.ArgumentMatchers.eq("c1"), org.mockito.ArgumentMatchers.eq("cadastro em duplicidade"));
    }

    @Test
    void exportacaoDeDadosExigeAcessoARelatorios() throws Exception {
        token("t-est7", "est7", "office1", Role.ESTAGIARIO, now());
        profile("est7", "office1", Role.ESTAGIARIO, UserStatus.ACTIVE, null);
        mvc.perform(get("/api/clients/export").header("Authorization", "Bearer t-est7")).andExpect(status().isForbidden());
        mvc.perform(get("/api/cases/export").header("Authorization", "Bearer t-est7")).andExpect(status().isForbidden());
        verify(clientService, never()).export(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void pesquisaGlobalEhDaEquipeENaoDoPortal() throws Exception {
        token("t-cli7", "cli7", "office1", Role.CLIENTE, now());
        profile("cli7", "office1", Role.CLIENTE, UserStatus.ACTIVE, null);
        mvc.perform(get("/api/search").param("q", "silva").header("Authorization", "Bearer t-cli7"))
                .andExpect(status().isForbidden());

        token("t-sec7", "sec7", "office1", Role.SECRETARIA, now());
        profile("sec7", "office1", Role.SECRETARIA, UserStatus.ACTIVE, null);
        when(searchService.search(any(), any())).thenReturn(List.of());
        mvc.perform(get("/api/search").param("q", "silva").header("Authorization", "Bearer t-sec7"))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ Fase 2: operação jurídica

    @Test
    void estagiarioConsultaDocumentosMasNaoOrganizaNemEditaPecas() throws Exception {
        token("t-est20", "est20", "office1", Role.ESTAGIARIO, now());
        profile("est20", "office1", Role.ESTAGIARIO, UserStatus.ACTIVE, null);
        String bearer = "Bearer t-est20";
        mvc.perform(post("/api/documents/d1/trash").header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(post("/api/documents/d1/move").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"folderId\":\"f1\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/documents/folders").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Petições\"}")).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/documents/d1")
                .header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(post("/api/legal-editor/documents").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"templateId\":\"sys-procuracao\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/legal-editor/documents/x/finalize").header("Authorization", bearer)).andExpect(status().isForbidden());
        verify(documentService, never()).trash(any(), any());
        verify(legalEditorService, never()).create(any(), any());

        // leitura e favoritos (pessoais) continuam permitidos
        when(legalEditorService.templates(any())).thenReturn(List.of());
        mvc.perform(get("/api/legal-editor/templates").header("Authorization", bearer)).andExpect(status().isOk());
        mvc.perform(post("/api/documents/d1/favorite").header("Authorization", bearer)).andExpect(status().isOk());
        verify(documentService).favorite(any(), org.mockito.ArgumentMatchers.eq("d1"), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void eliminacaoDefinitivaExigePermissaoPropria() throws Exception {
        token("t-adv20", "adv20", "office1", Role.ADVOGADO, now());
        profile("adv20", "office1", Role.ADVOGADO, UserStatus.ACTIVE, null);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/documents/d1")
                .param("reason", "duplicado").header("Authorization", "Bearer t-adv20")).andExpect(status().isForbidden());
        verify(documentService, never()).purge(any(), any(), any());

        token("t-soc20", "soc20", "office1", Role.SOCIO_ADMIN, now());
        profile("soc20", "office1", Role.SOCIO_ADMIN, UserStatus.ACTIVE, null);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/documents/d1")
                .param("reason", "duplicado").header("Authorization", "Bearer t-soc20")).andExpect(status().isNoContent());
        verify(documentService).purge(any(), org.mockito.ArgumentMatchers.eq("d1"), org.mockito.ArgumentMatchers.eq("duplicado"));
    }

    @Test
    void financeiroSemEditorJuridicoEPortalSemTarefas() throws Exception {
        token("t-fin20", "fin20", "office1", Role.FINANCEIRO, now());
        profile("fin20", "office1", Role.FINANCEIRO, UserStatus.ACTIVE, null);
        mvc.perform(get("/api/legal-editor/documents").header("Authorization", "Bearer t-fin20")).andExpect(status().isForbidden());
        mvc.perform(get("/api/documents/folders").header("Authorization", "Bearer t-fin20")).andExpect(status().isForbidden());
        mvc.perform(get("/api/calendar/hearings").param("from", "2026-11-01T00:00:00Z").param("to", "2026-12-01T00:00:00Z")
                .header("Authorization", "Bearer t-fin20")).andExpect(status().isForbidden());

        token("t-cli20", "cli20", "office1", Role.CLIENTE, now());
        profile("cli20", "office1", Role.CLIENTE, UserStatus.ACTIVE, null);
        mvc.perform(get("/api/tasks").header("Authorization", "Bearer t-cli20")).andExpect(status().isForbidden());
        mvc.perform(get("/api/legal-editor/templates").header("Authorization", "Bearer t-cli20")).andExpect(status().isForbidden());
    }

    @Test
    void relatoriosDePrazosEAgendaExigemAcessoARelatorios() throws Exception {
        token("t-sec20", "sec20", "office1", Role.SECRETARIA, now());
        profile("sec20", "office1", Role.SECRETARIA, UserStatus.ACTIVE, null);
        mvc.perform(get("/api/deadlines/report").header("Authorization", "Bearer t-sec20")).andExpect(status().isForbidden());
        mvc.perform(get("/api/calendar/report").param("from", "2026-11-01T00:00:00Z").param("to", "2026-12-01T00:00:00Z")
                .header("Authorization", "Bearer t-sec20")).andExpect(status().isForbidden());
        // secretaria consulta prazos, mas não anexa comprovante (exige DEADLINES_WRITE)
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/deadlines/d1/receipt")
                .file(new org.springframework.mock.web.MockMultipartFile("file", "c.pdf", "application/pdf", "%PDF-1.4".getBytes()))
                .header("Authorization", "Bearer t-sec20")).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ Fase 3: gestão empresarial

    @Test
    void estagiarioSemFinanceiroNemRelatoriosMasVeAsPropriasComissoes() throws Exception {
        token("t-est30", "est30", "office1", Role.ESTAGIARIO, now());
        profile("est30", "office1", Role.ESTAGIARIO, UserStatus.ACTIVE, null);
        String bearer = "Bearer t-est30";
        mvc.perform(get("/api/reports").header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/fees/summary").param("from", "2026-01-01").param("to", "2026-12-31").header("Authorization", bearer))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/finance/bank-accounts").header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(post("/api/finance/cost-centers").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Contencioso\",\"active\":true}")).andExpect(status().isForbidden());
        when(feeService.commissions(any(), any(), any(), org.mockito.ArgumentMatchers.eq("est30"))).thenReturn(List.of());
        mvc.perform(get("/api/fees/commissions/mine").param("from", "2026-01-01").param("to", "2026-12-31").header("Authorization", bearer))
                .andExpect(status().isOk());
        verify(feeService).commissions(any(), any(), any(), org.mockito.ArgumentMatchers.eq("est30"));
    }

    @Test
    void comissoesDeOutrosERegrasExigemFinanceiro() throws Exception {
        token("t-adv30", "adv30", "office1", Role.ADVOGADO, now());
        profile("adv30", "office1", Role.ADVOGADO, UserStatus.ACTIVE, null);
        mvc.perform(get("/api/fees/commissions").param("from", "2026-01-01").param("to", "2026-12-31").header("Authorization", "Bearer t-adv30"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/fees/commission-rules").header("Authorization", "Bearer t-adv30").contentType(MediaType.APPLICATION_JSON)
                .content("{\"beneficiaryUid\":\"adv30\",\"percentBp\":5000,\"active\":true}")).andExpect(status().isForbidden());
        verify(feeService, never()).saveRule(any(), any(), any());

        token("t-cli30", "cli30", "office1", Role.CLIENTE, now());
        profile("cli30", "office1", Role.CLIENTE, UserStatus.ACTIVE, null);
        mvc.perform(get("/api/fees/commissions/mine").param("from", "2026-01-01").param("to", "2026-12-31").header("Authorization", "Bearer t-cli30"))
                .andExpect(status().isForbidden());
    }

    @Test
    void renovacaoDeContratoExigeEdicaoDeContratos() throws Exception {
        token("t-ass30", "ass30", "office1", Role.ADVOGADO_ASSOCIADO, now());
        profile("ass30", "office1", Role.ADVOGADO_ASSOCIADO, UserStatus.ACTIVE, null);
        mvc.perform(post("/api/contracts/c1/renew").header("Authorization", "Bearer t-ass30").contentType(MediaType.APPLICATION_JSON)
                .content("{\"newEndDate\":\"2027-12-31\"}")).andExpect(status().isForbidden());
        verify(contractService, never()).renew(any(), any(), any());
    }

    @Test
    void financeiroGeraRelatoriosDoSeuModulo() throws Exception {
        token("t-fin30", "fin30", "office1", Role.FINANCEIRO, now());
        profile("fin30", "office1", Role.FINANCEIRO, UserStatus.ACTIVE, null);
        when(reportCenterService.catalog(any())).thenReturn(List.of());
        mvc.perform(get("/api/reports").header("Authorization", "Bearer t-fin30")).andExpect(status().isOk());
        mvc.perform(get("/api/finance/transactions/t1/recibo").header("Authorization", "Bearer t-fin30"))
                .andExpect(status().is(org.hamcrest.Matchers.not(403)));
    }
}
