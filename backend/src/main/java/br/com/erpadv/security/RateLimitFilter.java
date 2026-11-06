package br.com.erpadv.security;

import br.com.erpadv.exceptions.ProblemFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limite de requisições por janela de 1 minuto, por usuário autenticado (ou IP quando anônimo).
 * Complementa o {@code limit_req} do Nginx. Para várias réplicas da API, substituir por um
 * contador distribuído.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private record Window(long minute, AtomicInteger count) {
    }

    private final int limitPerMinute;
    private final ProblemFactory problems;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(int limitPerMinute, ProblemFactory problems) {
        this.limitPerMinute = limitPerMinute;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return limitPerMinute <= 0 || request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long minute = System.currentTimeMillis() / 60_000L;
        String key = resolveKey(request);
        Window window = windows.compute(key, (k, w) -> w == null || w.minute() != minute
                ? new Window(minute, new AtomicInteger())
                : w);
        int count = window.count().incrementAndGet();
        response.setHeader("X-RateLimit-Limit", String.valueOf(limitPerMinute));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, limitPerMinute - count)));
        if (count > limitPerMinute) {
            response.setHeader("Retry-After", "60");
            problems.write(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Muitas requisições. Aguarde alguns instantes.");
            return;
        }
        if (windows.size() > 50_000) {
            windows.entrySet().removeIf(e -> e.getValue().minute() != minute);
        }
        chain.doFilter(request, response);
    }

    private String resolveKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return "u:" + user.uid();
        }
        return "ip:" + request.getRemoteAddr();
    }
}
