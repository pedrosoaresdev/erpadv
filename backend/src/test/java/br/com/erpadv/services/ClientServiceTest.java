package br.com.erpadv.services;

import br.com.erpadv.TestFixtures;
import br.com.erpadv.audit.AuditService;
import br.com.erpadv.dto.ClientDtos.ClientRequest;
import br.com.erpadv.dto.ClientDtos.ClientResponse;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.ConflictException;
import br.com.erpadv.firebase.CryptoService;
import br.com.erpadv.models.Client;
import br.com.erpadv.models.ClientType;
import br.com.erpadv.repositories.ClientRepository;
import br.com.erpadv.repositories.UserRepository;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClientServiceTest {

    private ClientRepository clients;
    private AuditService audit;
    private CryptoService crypto;
    private ClientService service;

    private final AuthenticatedUser advogado = TestFixtures.user("adv", "office1", Role.ADVOGADO);
    private final AuthenticatedUser secretaria = TestFixtures.user("sec", "office1", Role.SECRETARIA);

    @BeforeEach
    void setUp() {
        clients = mock(ClientRepository.class);
        audit = mock(AuditService.class);
        crypto = new CryptoService(TestFixtures.properties(false));
        service = new ClientService(clients, mock(UserRepository.class), crypto, audit);
        when(clients.save(anyString(), any(Client.class))).thenAnswer(inv -> {
            Client c = inv.getArgument(1);
            c.setId("c1");
            c.setOfficeId(inv.getArgument(0));
            return c;
        });
        when(clients.findByDocumentHash(anyString(), anyString())).thenReturn(Optional.empty());
    }

    private ClientRequest pf(String cpf) {
        return new ClientRequest(ClientType.PF, "Maria da Silva", null, cpf, "12.345.678-9", null,
                "(11) 98888-7777", null, "MARIA@EMAIL.COM", null, "Professora", "Casada", null, null);
    }

    @Test
    void cadastraClienteComDadosSensiveisCriptografados() {
        ClientResponse response = service.create(advogado, pf("529.982.247-25"));

        ArgumentCaptor<Client> saved = ArgumentCaptor.forClass(Client.class);
        verify(clients).save(eq("office1"), saved.capture());
        Client c = saved.getValue();
        assertThat(c.getDocumentEnc()).startsWith("v1:").doesNotContain("52998224725");
        assertThat(c.getRgEnc()).startsWith("v1:");
        assertThat(c.getDocumentMasked()).isEqualTo("***.***.247-25");
        assertThat(c.getEmail()).isEqualTo("maria@email.com");
        assertThat(c.getSearchTokens()).contains("maria", "silva", "p:988887777", "d:" + crypto.hmac("52998224725"));
        // Advogado tem CLIENTS_SENSITIVE_READ: recebe o documento completo.
        assertThat(response.document()).isEqualTo("529.982.247-25");
        verify(audit).record(eq(advogado), eq("CLIENT_CREATED"), eq("client"), eq("c1"), anyString());
    }

    @Test
    void secretariaRecebeDocumentoMascarado() {
        ClientResponse response = service.create(secretaria, pf("529.982.247-25"));
        assertThat(response.document()).isEqualTo("***.***.247-25");
        assertThat(response.rg()).isEqualTo("***");
        assertThat(response.sensitiveVisible()).isFalse();
    }

    @Test
    void recusaDocumentoDuplicado() {
        when(clients.findByDocumentHash(eq("office1"), anyString())).thenReturn(Optional.of(new Client()));
        assertThatThrownBy(() -> service.create(advogado, pf("529.982.247-25")))
                .isInstanceOf(ConflictException.class);
        verify(clients, never()).save(anyString(), any());
    }

    @Test
    void pessoaFisicaExigeCpf() {
        assertThatThrownBy(() -> service.create(advogado, pf("11.222.333/0001-81")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CPF");
    }

    @Test
    void edicaoSemDocumentoMantemOAtual() {
        service.create(advogado, pf("529.982.247-25"));
        ArgumentCaptor<Client> saved = ArgumentCaptor.forClass(Client.class);
        verify(clients).save(eq("office1"), saved.capture());
        Client existing = saved.getValue();
        when(clients.findById("office1", "c1")).thenReturn(Optional.of(existing));

        ClientRequest edit = new ClientRequest(ClientType.PF, "Maria S. Souza", null, null, null, null,
                null, null, null, null, null, null, null, null);
        ClientResponse response = service.update(secretaria, "c1", edit);
        assertThat(response.name()).isEqualTo("Maria S. Souza");
        assertThat(existing.getDocumentHash()).isEqualTo(crypto.hmac("52998224725"));
        // Secretária não vê o RG; ele não pode ser apagado pela edição.
        assertThat(existing.getRgEnc()).isNotNull();
    }

    @Test
    void tokensDeBusca() {
        assertThat(service.searchToken("529.982.247-25")).isEqualTo("d:" + crypto.hmac("52998224725"));
        assertThat(service.searchToken("98888-7777")).isEqualTo("p:988887777");
        assertThat(service.searchToken("Maria Conceição")).isEqualTo("conceicao");
        assertThat(service.searchToken("Maria@Email.com")).isEqualTo("e:maria@email.com");
    }
}
