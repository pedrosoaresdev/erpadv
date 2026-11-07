package br.com.erpadv.controllers;

import br.com.erpadv.dto.EmployeeDtos.CustomRoleRequest;
import br.com.erpadv.dto.EmployeeDtos.CustomRoleResponse;
import br.com.erpadv.dto.EmployeeDtos.RolePreset;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.RoleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Cargos personalizados do escritório. */
@RestController
@RequestMapping("/api/roles")
public class RoleController {

    private final RoleService roles;

    public RoleController(RoleService roles) {
        this.roles = roles;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_EMPLOYEES_READ')")
    public List<CustomRoleResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return roles.list(user);
    }

    @GetMapping("/presets")
    @PreAuthorize("hasAuthority('PERM_EMPLOYEES_READ')")
    public List<RolePreset> presets() {
        return roles.presets();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_EMPLOYEES_MANAGE')")
    public CustomRoleResponse create(@AuthenticationPrincipal AuthenticatedUser user,
                                     @Valid @RequestBody CustomRoleRequest request) {
        return roles.create(user, request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_EMPLOYEES_MANAGE')")
    public CustomRoleResponse update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                     @Valid @RequestBody CustomRoleRequest request) {
        return roles.update(user, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_EMPLOYEES_MANAGE')")
    public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        roles.delete(user, id);
    }
}
