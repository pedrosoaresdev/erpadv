package br.com.erpadv.controllers;

import br.com.erpadv.models.Communication;
import br.com.erpadv.models.MessageTemplate;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.CommunicationService;
import br.com.erpadv.services.CommunicationService.RegisterRequest;
import br.com.erpadv.services.CommunicationService.Rendered;
import br.com.erpadv.services.CommunicationService.SendRequest;
import br.com.erpadv.services.CommunicationService.TemplateRequest;
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
import java.util.Map;

/** Central de comunicação (MESSAGES_USE). */
@RestController
@RequestMapping("/api/communications")
@PreAuthorize("hasAuthority('PERM_MESSAGES_USE')")
public class CommunicationController {

    private final CommunicationService service;

    public CommunicationController(CommunicationService service) {
        this.service = service;
    }

    @GetMapping("/channels")
    public Map<String, Boolean> channels(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.channels(user);
    }

    @GetMapping
    public List<Communication> list(@AuthenticationPrincipal AuthenticatedUser user,
                                    @RequestParam(required = false) String clientId,
                                    @RequestParam(required = false) String caseId,
                                    @RequestParam(required = false) Communication.Channel channel,
                                    @RequestParam(defaultValue = "false") boolean followUps) {
        return service.list(user, clientId, caseId, channel, followUps);
    }

    @PostMapping("/send")
    @ResponseStatus(HttpStatus.CREATED)
    public Communication send(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody SendRequest req) {
        return service.send(user, req);
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Communication register(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody RegisterRequest req) {
        return service.register(user, req);
    }

    @PostMapping("/{id}/follow-up-done")
    public Communication followUpDone(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return service.completeFollowUp(user, id);
    }

    @PostMapping("/appointments/{id}/invite")
    @PreAuthorize("hasAuthority('PERM_CALENDAR_WRITE')")
    public Map<String, Integer> invite(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return Map.of("sent", service.inviteGuests(user, id));
    }

    @GetMapping("/templates")
    public List<MessageTemplate> templates(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.templates(user);
    }

    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageTemplate createTemplate(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody TemplateRequest req) {
        return service.saveTemplate(user, null, req);
    }

    @PutMapping("/templates/{id}")
    public MessageTemplate updateTemplate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id, @RequestBody TemplateRequest req) {
        return service.saveTemplate(user, id, req);
    }

    @DeleteMapping("/templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTemplate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        service.deleteTemplate(user, id);
    }

    @GetMapping("/templates/{id}/render")
    public Rendered render(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                           @RequestParam(required = false) String clientId, @RequestParam(required = false) String caseId) {
        return service.render(user, id, clientId, caseId);
    }
}
