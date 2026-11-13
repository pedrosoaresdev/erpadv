package br.com.erpadv.controllers;

import br.com.erpadv.models.Unit;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.platform.UnitService;
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

/** Unidades e filiais do escritório (consulta para a equipe; cadastro para quem configura o escritório). */
@RestController
@RequestMapping("/api/units")
public class UnitController {

    public record MembersRequest(List<String> uids) {
    }

    private final UnitService units;

    public UnitController(UnitService units) {
        this.units = units;
    }

    @GetMapping
    public List<UnitService.UnitView> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return units.list(user);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public Unit create(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody UnitService.UnitRequest req) {
        return units.save(user, null, req);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public Unit update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id, @RequestBody UnitService.UnitRequest req) {
        return units.save(user, id, req);
    }

    @PutMapping("/{id}/members")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public UnitService.UnitView members(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id, @RequestBody MembersRequest req) {
        return units.setMembers(user, id, req.uids());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        units.delete(user, id);
    }
}
