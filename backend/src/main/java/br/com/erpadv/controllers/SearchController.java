package br.com.erpadv.controllers;

import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.SearchService;
import br.com.erpadv.services.SearchService.SearchGroup;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Pesquisa global do cabeçalho (resultados agrupados por categoria e filtrados por permissão). */
@RestController
public class SearchController {

    private final SearchService search;

    public SearchController(SearchService search) {
        this.search = search;
    }

    @GetMapping("/api/search")
    public List<SearchGroup> search(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam String q) {
        return search.search(user, q);
    }
}
