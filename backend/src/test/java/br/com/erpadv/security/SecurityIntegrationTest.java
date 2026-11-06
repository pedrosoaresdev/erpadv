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
        "app.security.rate-limit-per-minute=1000"
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
    ClientService clientService;
    @MockitoBean
    AuthService authService;

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
}
