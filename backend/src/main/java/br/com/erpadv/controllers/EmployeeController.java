package br.com.erpadv.controllers;

import br.com.erpadv.dto.CursorPage;
import br.com.erpadv.dto.EmployeeDtos.BlockRequest;
import br.com.erpadv.dto.EmployeeDtos.EmployeeOption;
import br.com.erpadv.dto.EmployeeDtos.EmployeeResponse;
import br.com.erpadv.dto.EmployeeDtos.InviteRequest;
import br.com.erpadv.dto.EmployeeDtos.InviteResponse;
import br.com.erpadv.dto.EmployeeDtos.PermissionInfo;
import br.com.erpadv.dto.EmployeeDtos.ResetLinkResponse;
import br.com.erpadv.dto.EmployeeDtos.RoleInfo;
import br.com.erpadv.dto.EmployeeDtos.SetPasswordRequest;
import br.com.erpadv.dto.EmployeeDtos.UpdateEmployeeRequest;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.EmployeeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

    private final EmployeeService employees;

    public EmployeeController(EmployeeService employees) {
        this.employees = employees;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_EMPLOYEES_READ')")
    public CursorPage<EmployeeResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                             @RequestParam(required = false) String cursor,
                                             @RequestParam(defaultValue = "50") int size) {
        return employees.list(user, cursor, size);
    }

    /** Advogados ativos — usado no cadastro de processos (qualquer integrante da equipe). */
    @GetMapping("/lawyers")
    public List<EmployeeOption> lawyers(@AuthenticationPrincipal AuthenticatedUser user) {
        return employees.lawyers(user);
    }

    /** Equipe ativa — usado em seletores de responsável (qualquer integrante da equipe). */
    @GetMapping("/options")
    public List<EmployeeOption> options(@AuthenticationPrincipal AuthenticatedUser user) {
        return employees.staff(user);
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('PERM_EMPLOYEES_READ')")
    public List<RoleInfo> roles() {
        return employees.roles();
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('PERM_EMPLOYEES_READ')")
    public List<PermissionInfo> permissions() {
        return employees.permissions();
    }

    @GetMapping("/{uid}")
    @PreAuthorize("hasAuthority('PERM_EMPLOYEES_READ')")
    public EmployeeResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String uid) {
        return employees.get(user, uid);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_EMPLOYEES_MANAGE')")
    public InviteResponse invite(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody InviteRequest request) {
        return employees.invite(user, request);
    }

    @PutMapping("/{uid}")
    @PreAuthorize("hasAuthority('PERM_EMPLOYEES_MANAGE')")
    public EmployeeResponse update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String uid,
                                   @Valid @RequestBody UpdateEmployeeRequest request) {
        return employees.update(user, uid, request);
    }

    @PostMapping("/{uid}/block")
    @PreAuthorize("hasAuthority('PERM_EMPLOYEES_MANAGE')")
    public EmployeeResponse block(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String uid,
                                  @Valid @RequestBody(required = false) BlockRequest request) {
        return employees.block(user, uid, request == null ? null : request.reason());
    }

    @PostMapping("/{uid}/unblock")
    @PreAuthorize("hasAuthority('PERM_EMPLOYEES_MANAGE')")
    public EmployeeResponse unblock(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String uid) {
        return employees.unblock(user, uid);
    }

    @PostMapping("/{uid}/revoke-sessions")
    @PreAuthorize("hasAuthority('PERM_EMPLOYEES_MANAGE')")
    public EmployeeResponse revokeSessions(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String uid) {
        return employees.revokeSessions(user, uid);
    }

    @PostMapping("/{uid}/password")
    @PreAuthorize("hasAuthority('PERM_EMPLOYEES_MANAGE')")
    public EmployeeResponse setPassword(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String uid,
                                        @Valid @RequestBody SetPasswordRequest request) {
        return employees.setPassword(user, uid, request.password());
    }

    @PostMapping("/{uid}/reset-access")
    @PreAuthorize("hasAuthority('PERM_EMPLOYEES_MANAGE')")
    public ResetLinkResponse resetAccess(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String uid) {
        return new ResetLinkResponse(employees.resetAccess(user, uid));
    }
}
