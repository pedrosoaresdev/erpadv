package br.com.erpadv.controllers;

import br.com.erpadv.services.lgpd.LgpdService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Conteúdo público (sem login): política de privacidade vigente do escritório, para clientes e titulares. */
@RestController
@RequestMapping("/api/public")
public class PublicController {

    private final LgpdService lgpd;

    public PublicController(LgpdService lgpd) {
        this.lgpd = lgpd;
    }

    @GetMapping("/privacy/{officeId}")
    public LgpdService.PublicPolicy privacy(@PathVariable String officeId) {
        if (officeId == null || !officeId.matches("[A-Za-z0-9_-]{6,64}")) {
            throw new br.com.erpadv.exceptions.NotFoundException("Política");
        }
        return lgpd.publicPolicy(officeId);
    }
}
