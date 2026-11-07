package br.com.erpadv.controllers;

import br.com.erpadv.dto.CrmDtos.ActivityRequest;
import br.com.erpadv.dto.CrmDtos.ConvertRequest;
import br.com.erpadv.dto.CrmDtos.ConvertResponse;
import br.com.erpadv.dto.CrmDtos.CrmMetrics;
import br.com.erpadv.dto.CrmDtos.LeadDetail;
import br.com.erpadv.dto.CrmDtos.LeadRequest;
import br.com.erpadv.dto.CrmDtos.LeadResponse;
import br.com.erpadv.dto.CrmDtos.StageRequest;
import br.com.erpadv.models.CrmEnums.LeadStage;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.CrmService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** CRM comercial (funil de captação de clientes). */
@RestController
@RequestMapping("/api/crm")
@PreAuthorize("hasAuthority('PERM_CRM_USE')")
public class CrmController {

    private final CrmService crm;

    public CrmController(CrmService crm) {
        this.crm = crm;
    }

    @GetMapping("/leads")
    public List<LeadResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                   @RequestParam(required = false) LeadStage stage,
                                   @RequestParam(required = false) String ownerUid,
                                   @RequestParam(required = false) String search,
                                   @RequestParam(defaultValue = "false") boolean open) {
        return crm.list(user, stage, ownerUid, search, open);
    }

    @GetMapping("/leads/{id}")
    public LeadDetail get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return crm.get(user, id);
    }

    @PostMapping("/leads")
    @ResponseStatus(HttpStatus.CREATED)
    public LeadResponse create(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody LeadRequest request) {
        return crm.create(user, request);
    }

    @PutMapping("/leads/{id}")
    public LeadResponse update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                               @Valid @RequestBody LeadRequest request) {
        return crm.update(user, id, request);
    }

    @PostMapping("/leads/{id}/stage")
    public LeadResponse stage(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                              @Valid @RequestBody StageRequest request) {
        return crm.changeStage(user, id, request.stage(), request.lostReason());
    }

    @PostMapping("/leads/{id}/activities")
    @ResponseStatus(HttpStatus.CREATED)
    public LeadDetail activity(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                               @Valid @RequestBody ActivityRequest request) {
        return crm.addActivity(user, id, request);
    }

    @PostMapping("/leads/{id}/convert")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_CRM_USE') and hasAuthority('PERM_CLIENTS_WRITE')")
    public ConvertResponse convert(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                   @Valid @RequestBody ConvertRequest request) {
        return crm.convert(user, id, request.client());
    }

    @DeleteMapping("/leads/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        crm.delete(user, id);
    }

    @GetMapping("/metrics")
    public CrmMetrics metrics(@AuthenticationPrincipal AuthenticatedUser user,
                              @RequestParam(defaultValue = "6") int months) {
        return crm.metrics(user, months);
    }
}
