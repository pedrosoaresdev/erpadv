package br.com.erpadv.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** IP e user-agent da requisição corrente (o IP real vem do Nginx via X-Forwarded-For). */
public final class RequestInfo {

    private RequestInfo() {
    }

    public static String ip() {
        HttpServletRequest request = current();
        return request == null ? null : request.getRemoteAddr();
    }

    public static String userAgent() {
        HttpServletRequest request = current();
        if (request == null) {
            return null;
        }
        String ua = request.getHeader("User-Agent");
        return ua == null ? null : ua.substring(0, Math.min(ua.length(), 256));
    }

    public static final String JUSTIFICATION_HEADER = "X-Audit-Justification";

    /** Justificativa enviada pela interface (codificada em URL para aceitar acentos). Máx. 500 caracteres. */
    public static String justification() {
        HttpServletRequest request = current();
        String raw = request == null ? null : request.getHeader(JUSTIFICATION_HEADER);
        if ((raw == null || raw.isBlank()) && request != null && "DELETE".equalsIgnoreCase(request.getMethod())) {
            // Exclusões que já pedem o motivo na própria tela (ex.: clientes) enviam ?reason=
            raw = request.getParameter("reason");
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v;
        try {
            v = java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException e) {
            v = raw.trim();
        }
        v = v.replaceAll("\\p{Cntrl}", " ");
        return v.isBlank() ? null : v.substring(0, Math.min(v.length(), 500));
    }

    /** Origem da operação: rotina automática (sem requisição), webhook de provedor, celular ou navegador. */
    public static String origin() {
        HttpServletRequest request = current();
        if (request == null) {
            return "SISTEMA";
        }
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/api/hooks/")) {
            return "WEBHOOK";
        }
        String ua = request.getHeader("User-Agent");
        if (ua == null || ua.isBlank()) {
            return "API";
        }
        String l = ua.toLowerCase(java.util.Locale.ROOT);
        return l.contains("mobile") || l.contains("android") || l.contains("iphone") || l.contains("ipad") ? "CELULAR" : "WEB";
    }

    private static HttpServletRequest current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }
}
