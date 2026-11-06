package br.com.erpadv.exceptions;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Converte exceções em {@code application/problem+json}, sem vazar detalhes internos. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ProblemFactory problems;

    public GlobalExceptionHandler(ProblemFactory problems) {
        this.problems = problems;
    }

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApi(ApiException ex) {
        if (ex.getStatus().is5xxServerError()) {
            log.error("Erro {}: {}", ex.getCode(), ex.getMessage(), ex.getCause());
        } else {
            log.info("Requisição recusada {}: {}", ex.getCode(), ex.getMessage());
        }
        return problems.create(ex.getStatus(), ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(e -> fields.putIfAbsent(e.getField(), e.getDefaultMessage()));
        ex.getBindingResult().getGlobalErrors()
                .forEach(e -> fields.putIfAbsent(e.getObjectName(), e.getDefaultMessage()));
        ProblemDetail problem = problems.create(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Dados inválidos.");
        problem.setProperty("fields", fields);
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraint(ConstraintViolationException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v -> fields.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
        ProblemDetail problem = problems.create(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Dados inválidos.");
        problem.setProperty("fields", fields);
        return problem;
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail handleUnreadable(Exception ex) {
        return problems.create(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Requisição malformada.");
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ProblemDetail handleDenied(Exception ex) {
        return problems.create(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Você não tem permissão para esta operação.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethod(HttpRequestMethodNotSupportedException ex) {
        return problems.create(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Método não suportado.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException ex) {
        return problems.create(HttpStatus.NOT_FOUND, "NOT_FOUND", "Recurso não encontrado.");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Erro inesperado", ex);
        return problems.create(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Erro interno. Informe o código de rastreio ao suporte.");
    }
}
