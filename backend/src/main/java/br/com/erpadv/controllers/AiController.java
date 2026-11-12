package br.com.erpadv.controllers;

import br.com.erpadv.dto.LegalEditorDtos.DraftResponse;
import br.com.erpadv.models.AiConversation;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.ai.AiService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** IA Jurídica e Pesquisa Jurídica. Exige AI_USE; cada agente pede ainda a permissão da sua área (ex.: CRIMINAL_READ). */
@RestController
@RequestMapping("/api/ai")
@PreAuthorize("hasAuthority('PERM_AI_USE')")
public class AiController {

    private final AiService ai;

    public AiController(AiService ai) {
        this.ai = ai;
    }

    @GetMapping("/catalog")
    public AiService.Catalog catalog(@AuthenticationPrincipal AuthenticatedUser user) {
        return ai.catalog(user);
    }

    @GetMapping("/conversations")
    public List<AiConversation> conversations(@AuthenticationPrincipal AuthenticatedUser user) {
        return ai.mine(user);
    }

    @GetMapping("/conversations/{id}")
    public AiConversation conversation(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return ai.get(user, id);
    }

    @DeleteMapping("/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        ai.delete(user, id);
    }

    @PostMapping("/ask")
    public AiConversation ask(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody AiService.AskRequest req) {
        return ai.ask(user, req);
    }

    @PostMapping("/conversations/{id}/messages/{index}/review")
    public AiConversation review(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id, @PathVariable int index) {
        return ai.review(user, id, index);
    }

    @PostMapping("/conversations/{id}/messages/{index}/to-editor")
    public DraftResponse toEditor(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id, @PathVariable int index,
                                  @RequestBody(required = false) AiService.EditorRequest req) {
        return ai.toEditor(user, id, index, req);
    }
}
