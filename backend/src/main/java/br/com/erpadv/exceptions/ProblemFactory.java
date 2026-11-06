package br.com.erpadv.exceptions;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/** Cria respostas de erro padronizadas (RFC 7807) — usado pelo handler global e pelos filtros. */
@Component
public class ProblemFactory {

    private final ObjectMapper objectMapper;

    public ProblemFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ProblemDetail create(HttpStatus status, String code, String message) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, message);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setProperty("traceId", MDC.get("traceId"));
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    public void write(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), create(status, code, message));
    }
}
