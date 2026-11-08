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
    @org.springframework.beans.factory.annotation.Autowired
    private br.com.erpadv.services.ProposalService proposalService;

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

    // ------------------------------------------------------------------ propostas

    @GetMapping("/leads/{id}/proposals")
    @PreAuthorize("hasAuthority('PERM_CRM_USE')")
    public java.util.List<br.com.erpadv.dto.ProposalDtos.ProposalResponse> proposals(@AuthenticationPrincipal AuthenticatedUser user,
                                                                                    @PathVariable String id) {
        return proposalService.list(user, id);
    }

    @PostMapping("/leads/{id}/proposals")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_CRM_USE')")
    public br.com.erpadv.dto.ProposalDtos.ProposalResponse createProposal(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                                                         @Valid @RequestBody br.com.erpadv.dto.ProposalDtos.ProposalRequest req) {
        return proposalService.create(user, id, req);
    }

    @PutMapping("/proposals/{id}")
    @PreAuthorize("hasAuthority('PERM_CRM_USE')")
    public br.com.erpadv.dto.ProposalDtos.ProposalResponse updateProposal(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                                                         @Valid @RequestBody br.com.erpadv.dto.ProposalDtos.ProposalRequest req) {
        return proposalService.update(user, id, req);
    }

    @DeleteMapping("/proposals/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_CRM_USE')")
    public void deleteProposal(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        proposalService.delete(user, id);
    }

    @PostMapping("/proposals/{id}/send")
    @PreAuthorize("hasAuthority('PERM_CRM_USE')")
    public br.com.erpadv.dto.ProposalDtos.ProposalResponse sendProposal(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return proposalService.send(user, id);
    }

    @PostMapping("/proposals/{id}/decision")
    @PreAuthorize("hasAuthority('PERM_CRM_USE')")
    public br.com.erpadv.dto.ProposalDtos.ProposalResponse decideProposal(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                                                         @Valid @RequestBody br.com.erpadv.dto.ProposalDtos.DecisionRequest req) {
        return proposalService.decide(user, id, req);
    }

    @GetMapping("/proposals/{id}/pdf")
    @PreAuthorize("hasAuthority('PERM_CRM_USE')")
    public org.springframework.http.ResponseEntity<byte[]> proposalPdf(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return DashboardController.file(proposalService.pdf(user, id));
    }

    @GetMapping("/metrics")
    public CrmMetrics metrics(@AuthenticationPrincipal AuthenticatedUser user,
                              @RequestParam(defaultValue = "6") int months) {
        return crm.metrics(user, months);
    }
}
