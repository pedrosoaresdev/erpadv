package br.com.erpadv.controllers;

import br.com.erpadv.dto.BoardDtos.BoardInsights;
import br.com.erpadv.dto.BoardDtos.BoardRequest;
import br.com.erpadv.dto.BoardDtos.BoardSummary;
import br.com.erpadv.dto.BoardDtos.BoardTemplate;
import br.com.erpadv.dto.BoardDtos.BoardView;
import br.com.erpadv.dto.BoardDtos.CardDetail;
import br.com.erpadv.dto.BoardDtos.CardRequest;
import br.com.erpadv.dto.BoardDtos.ColumnsRequest;
import br.com.erpadv.dto.BoardDtos.CommentRequest;
import br.com.erpadv.dto.BoardDtos.LabelsRequest;
import br.com.erpadv.dto.BoardDtos.MoveRequest;
import br.com.erpadv.dto.BoardDtos.MyCard;
import br.com.erpadv.dto.DeadlineDtos.DeadlineRequest;
import br.com.erpadv.models.BoardCard;
import br.com.erpadv.models.CardActivity;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.BoardService;
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

/** Quadros de tarefas (Kanban jurídico). */
@RestController
@RequestMapping("/api/boards")
@PreAuthorize("hasAuthority('PERM_BOARDS_USE')")
public class BoardController {

    private final BoardService boards;

    public BoardController(BoardService boards) {
        this.boards = boards;
    }

    @GetMapping
    public List<BoardSummary> list(@AuthenticationPrincipal AuthenticatedUser user,
                                   @RequestParam(defaultValue = "false") boolean archived) {
        return boards.list(user, archived);
    }

    @GetMapping("/templates")
    public List<BoardTemplate> templates() {
        return boards.templates();
    }

    @GetMapping("/my-cards")
    public List<MyCard> myCards(@AuthenticationPrincipal AuthenticatedUser user) {
        return boards.myCards(user);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BoardView create(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody BoardRequest request) {
        return boards.create(user, request);
    }

    @GetMapping("/{id}")
    public BoardView view(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return boards.view(user, id);
    }

    @PutMapping("/{id}")
    public BoardView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                            @Valid @RequestBody BoardRequest request) {
        return boards.update(user, id, request);
    }

    @PutMapping("/{id}/columns")
    public BoardView columns(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                             @Valid @RequestBody ColumnsRequest request) {
        return boards.updateColumns(user, id, request.columns());
    }

    @PutMapping("/{id}/labels")
    public BoardView labels(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                            @Valid @RequestBody LabelsRequest request) {
        return boards.updateLabels(user, id, request.labels());
    }

    @PostMapping("/{id}/archive")
    public BoardView archive(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return boards.setArchived(user, id, true);
    }

    @PostMapping("/{id}/restore")
    public BoardView restore(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return boards.setArchived(user, id, false);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        boards.delete(user, id);
    }

    @GetMapping("/{id}/insights")
    public BoardInsights insights(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return boards.insights(user, id);
    }

    @GetMapping("/{id}/archived-cards")
    public List<BoardCard> archivedCards(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return boards.archivedCards(user, id);
    }

    @PostMapping("/{id}/cards")
    @ResponseStatus(HttpStatus.CREATED)
    public BoardCard createCard(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
                                @Valid @RequestBody CardRequest request) {
        return boards.createCard(user, id, request);
    }

    @GetMapping("/cards/{cardId}")
    public CardDetail card(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String cardId) {
        return boards.card(user, cardId);
    }

    @PutMapping("/cards/{cardId}")
    public BoardCard updateCard(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String cardId,
                                @Valid @RequestBody CardRequest request) {
        return boards.updateCard(user, cardId, request);
    }

    @PostMapping("/cards/{cardId}/move")
    public BoardCard move(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String cardId,
                          @Valid @RequestBody MoveRequest request) {
        return boards.move(user, cardId, request);
    }

    @PostMapping("/cards/{cardId}/checklist/{itemId}/toggle")
    public BoardCard toggle(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String cardId,
                            @PathVariable String itemId) {
        return boards.toggleChecklist(user, cardId, itemId);
    }

    @PostMapping("/cards/{cardId}/archive")
    public BoardCard archiveCard(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String cardId) {
        return boards.setCardArchived(user, cardId, true);
    }

    @PostMapping("/cards/{cardId}/restore")
    public BoardCard restoreCard(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String cardId) {
        return boards.setCardArchived(user, cardId, false);
    }

    @PostMapping("/cards/{cardId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CardActivity comment(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String cardId,
                                @Valid @RequestBody CommentRequest request) {
        return boards.comment(user, cardId, request);
    }

    @PostMapping("/cards/{cardId}/deadline")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_BOARDS_USE') and hasAuthority('PERM_DEADLINES_WRITE')")
    public CardDetail toDeadline(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String cardId,
                                 @Valid @RequestBody DeadlineRequest request) {
        return boards.convertToDeadline(user, cardId, request);
    }
}
