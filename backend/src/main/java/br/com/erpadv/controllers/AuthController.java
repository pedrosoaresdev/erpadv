package br.com.erpadv.controllers;

import br.com.erpadv.dto.AuthDtos.CreateOfficeRequest;
import br.com.erpadv.dto.AuthDtos.MeResponse;
import br.com.erpadv.dto.AuthDtos.OfficeCreatedResponse;
import br.com.erpadv.services.AuthService;
import br.com.erpadv.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** Perfil, papel e permissões efetivas do usuário autenticado. */
    @GetMapping("/api/auth/me")
    public MeResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return authService.me(user);
    }

    /** Registra o login (último acesso, ativação de convite, auditoria). */
    @PostMapping("/api/auth/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void session(@AuthenticationPrincipal AuthenticatedUser user) {
        authService.registerSession(user);
    }

    /** Primeiro acesso: cria o escritório e torna o usuário sócio administrador. */
    @PostMapping("/api/onboarding/office")
    @ResponseStatus(HttpStatus.CREATED)
    public OfficeCreatedResponse createOffice(@AuthenticationPrincipal AuthenticatedUser user,
                                              @Valid @RequestBody CreateOfficeRequest request) {
        return authService.createOffice(user, request);
    }
}
