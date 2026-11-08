package br.com.erpadv.controllers;

import br.com.erpadv.dto.BoardDtos.CardRequest;
import br.com.erpadv.dto.BoardDtos.MyCard;
import br.com.erpadv.models.BoardCard;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.BoardService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Módulo Tarefas: visão em lista dos cartões de todos os quadros visíveis + tarefas avulsas. */
@RestController
@RequestMapping("/api/tasks")
@PreAuthorize("hasAuthority('PERM_BOARDS_USE')")
public class TaskController {

    private final BoardService boards;

    public TaskController(BoardService boards) {
        this.boards = boards;
    }

    @GetMapping
    public List<MyCard> list(@AuthenticationPrincipal AuthenticatedUser user,
                             @RequestParam(defaultValue = "mine") String scope,
                             @RequestParam(defaultValue = "open") String state,
                             @RequestParam(required = false) String clientId,
                             @RequestParam(required = false) String caseId) {
        return boards.tasks(user, scope, state, clientId, caseId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BoardCard create(@AuthenticationPrincipal AuthenticatedUser user,
                            @RequestParam(required = false) String boardId,
                            @Valid @RequestBody CardRequest request) {
        return boards.createTask(user, boardId, request);
    }

    @PostMapping("/{id}/complete")
    public BoardCard complete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return boards.setTaskDone(user, id, true);
    }

    @PostMapping("/{id}/reopen")
    public BoardCard reopen(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return boards.setTaskDone(user, id, false);
    }
}
