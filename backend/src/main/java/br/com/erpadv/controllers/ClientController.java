package br.com.erpadv.controllers;

import br.com.erpadv.dto.CaseDtos.CaseFilter;
import br.com.erpadv.dto.CaseDtos.CaseResponse;
import br.com.erpadv.dto.ClientDtos.ClientRequest;
import br.com.erpadv.dto.ClientDtos.ClientResponse;
import br.com.erpadv.dto.ClientDtos.ClientSummary;
import br.com.erpadv.dto.ClientDtos.DefaultingRequest;
import br.com.erpadv.dto.ClientDtos.InactivateRequest;
import br.com.erpadv.dto.ClientDtos.InteractionRequest;
import br.com.erpadv.dto.CursorPage;
import br.com.erpadv.models.ClientInteraction;
import br.com.erpadv.models.ClientStatus;
import br.com.erpadv.models.ClientType;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.CaseService;
import br.com.erpadv.services.ClientService;
import br.com.erpadv.audit.AuditLog;
import br.com.erpadv.audit.AuditService;
import br.com.erpadv.dto.ClientDtos.DuplicateMatch;
import br.com.erpadv.security.Permission;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/clients")
public class ClientController {

    private final ClientService clients;
    private final CaseService cases;
    private final AuditService audit;

    public ClientController(ClientService clients, CaseService cases, AuditService audit) {
        this.clients = clients;
        this.cases = cases;
        this.audit = audit;
    }

    /** Possíveis cadastros duplicados (CPF/CNPJ, telefone, e-mail ou nome idêntico). */
    @GetMapping("/duplicates")
    @PreAuthorize("hasAuthority('PERM_CLIENTS_READ')")
    public List<DuplicateMatch> duplicates(@AuthenticationPrincipal AuthenticatedUser user,
                                           @RequestParam(required = false) String name,
                                           @RequestParam(required = false) String document,
                                           @RequestParam(required = false) String phone,
                                           @RequestParam(required = false) String email,
                                           @RequestParam(required = false) String excludeId) {
        return clients.duplicates(user, name, document, phone, email, excludeId);
    }

    /** Clientes ativos sem atendimento ou movimentação recente. */
    @GetMapping("/stale")
    @PreAuthorize("hasAuthority('PERM_CLIENTS_READ')")
    public List<ClientSummary> stale(@AuthenticationPrincipal AuthenticatedUser user,
                                     @RequestParam(defaultValue = "90") int days) {
        return clients.stale(user, days);
    }

    /** Exporta a lista filtrada ou a seleção (ids) em PDF ou Excel. */
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('PERM_CLIENTS_READ') and hasAuthority('PERM_REPORTS_VIEW')")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal AuthenticatedUser user,
                                         @RequestParam(required = false) String search,
                                         @RequestParam(required = false) ClientStatus status,
                                         @RequestParam(required = false) ClientType type,
                                         @RequestParam(required = false) Boolean defaulting,
                                         @RequestParam(required = false) List<String> ids,
                                         @RequestParam(defaultValue = "xlsx") String format) {
        return DashboardController.file(clients.export(user, search, status, type, defaulting, ids, format));
    }

    /** Ficha cadastral (PDF para imprimir/arquivar ou Excel). */
    @GetMapping("/{id}/sheet")
    @PreAuthorize("hasAuthority('PERM_CLIENTS_READ')")
    public ResponseEntity<byte[]> sheet(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                        @RequestParam(defaultValue = "pdf") String format) {
        List<List<String>> rows = null;
        if (user.has(Permission.CASES_READ)) {
            rows = new ArrayList<>();
            for (CaseResponse c : cases.list(user, new CaseFilter(null, null, null, null, null, id, true, null), null, 100).items()) {
                rows.add(List.of(c.cnjNumber() == null ? "Sem número" : c.cnjNumber(), c.areaLabel() == null ? "" : c.areaLabel(),
                        c.court() == null ? "" : c.court(), c.statusLabel() == null ? "" : c.statusLabel()));
            }
        }
        return DashboardController.file(clients.sheet(user, id, format, rows));
    }

    /** Vínculos que impedem a exclusão definitiva (processos, contratos, financeiro…). */
    @GetMapping("/{id}/links")
    @PreAuthorize("hasAuthority('PERM_CLIENTS_READ')")
    public Map<String, Long> links(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return clients.links(user, id);
    }

    /** Histórico de alterações do cadastro (trilha de auditoria filtrada pelo cliente). */
    @GetMapping("/{id}/history")
    @PreAuthorize("hasAuthority('PERM_CLIENTS_READ')")
    public CursorPage<AuditLog> history(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                        @RequestParam(required = false) String cursor,
                                        @RequestParam(defaultValue = "30") int size) {
        clients.load(user, id);
        return audit.list(user.officeId(), "client", id, null, cursor, size);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_CLIENTS_DELETE')")
    public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                       @RequestParam(required = false) String reason) {
        clients.delete(user, id, reason);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_CLIENTS_READ')")
    public CursorPage<ClientSummary> list(@AuthenticationPrincipal AuthenticatedUser user,
                                          @RequestParam(required = false) String search,
                                          @RequestParam(required = false) ClientStatus status,
                                          @RequestParam(required = false) ClientType type,
                                          @RequestParam(required = false) Boolean defaulting,
                                          @RequestParam(required = false) String cursor,
                                          @RequestParam(defaultValue = "20") int size) {
        return clients.list(user, search, status, type, defaulting, cursor, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_CLIENTS_READ')")
    public ClientResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return clients.get(user, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_CLIENTS_WRITE')")
    public ClientResponse create(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody ClientRequest request) {
        return clients.create(user, request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_CLIENTS_WRITE')")
    public ClientResponse update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                 @Valid @RequestBody ClientRequest request) {
        return clients.update(user, id, request);
    }

    @PostMapping("/{id}/inactivate")
    @PreAuthorize("hasAuthority('PERM_CLIENTS_INACTIVATE')")
    public ClientResponse inactivate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                     @Valid @RequestBody InactivateRequest request) {
        return clients.inactivate(user, id, request.reason());
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAuthority('PERM_CLIENTS_INACTIVATE')")
    public ClientResponse reactivate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return clients.reactivate(user, id);
    }

    @PatchMapping("/{id}/defaulting")
    @PreAuthorize("hasAuthority('PERM_CLIENTS_WRITE')")
    public ClientResponse defaulting(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                     @RequestBody DefaultingRequest request) {
        return clients.setDefaulting(user, id, request.defaulting());
    }

    @GetMapping("/{id}/interactions")
    @PreAuthorize("hasAuthority('PERM_CLIENTS_READ')")
    public CursorPage<ClientInteraction> interactions(@AuthenticationPrincipal AuthenticatedUser user,
                                                      @PathVariable String id,
                                                      @RequestParam(required = false) String cursor,
                                                      @RequestParam(defaultValue = "20") int size) {
        return clients.interactions(user, id, cursor, size);
    }

    @PostMapping("/{id}/interactions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_CLIENTS_WRITE')")
    public ClientInteraction addInteraction(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                            @Valid @RequestBody InteractionRequest request) {
        return clients.addInteraction(user, id, request);
    }

    /** Processos vinculados ao cliente (respeita a restrição criminal). */
    @GetMapping("/{id}/cases")
    @PreAuthorize("hasAuthority('PERM_CLIENTS_READ') and hasAuthority('PERM_CASES_READ')")
    public CursorPage<CaseResponse> cases(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                          @RequestParam(required = false) String cursor,
                                          @RequestParam(defaultValue = "20") int size) {
        clients.load(user, id);
        return cases.list(user, new CaseFilter(null, null, null, null, null, id, true, null), cursor, size);
    }
}
