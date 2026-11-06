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
import jakarta.validation.Valid;
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

    public ClientController(ClientService clients, CaseService cases) {
        this.clients = clients;
        this.cases = cases;
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
        return cases.list(user, new CaseFilter(null, null, null, null, null, id, true), cursor, size);
    }
}
