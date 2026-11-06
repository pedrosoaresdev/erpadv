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

    private static HttpServletRequest current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }
}
