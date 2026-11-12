package br.com.erpadv.config;

import br.com.erpadv.audit.RequestInfo;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.services.security.SecurityPolicyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * Quando a política do escritório exige, toda exclusão (DELETE) e eliminação definitiva precisa vir com a
 * justificativa no cabeçalho {@code X-Audit-Justification}; ela é gravada no registro de auditoria da operação.
 */
@Component
public class JustificationInterceptor implements HandlerInterceptor {

    /** Ações pessoais ou de sessão que não são exclusão de registros do escritório. */
    private static final List<String> EXEMPT = List.of("/api/notifications", "/api/auth/", "/api/security/sessions",
            "/api/integrations/google/me", "/api/boards/", "/api/onboarding");

    private final SecurityPolicyService policies;

    public JustificationInterceptor(SecurityPolicyService policies) {
        this.policies = policies;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI();
        boolean deletion = "DELETE".equalsIgnoreCase(request.getMethod())
                || ("POST".equalsIgnoreCase(request.getMethod()) && (uri.endsWith("/purge") || uri.endsWith("/anonymize")));
        if (!deletion || !uri.startsWith("/api/") || EXEMPT.stream().anyMatch(uri::startsWith)) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedUser user) || !user.hasOffice()) {
            return true;
        }
        var policy = policies.get(user.officeId());
        if (policy != null && policy.isRequireJustificationOnDelete() && RequestInfo.justification() == null) {
            throw new BusinessException("JUSTIFICATION_REQUIRED", "Informe a justificativa da exclusão (exigida pela política do escritório).");
        }
        return true;
    }
}
