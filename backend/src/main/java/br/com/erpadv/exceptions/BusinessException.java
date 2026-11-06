package br.com.erpadv.exceptions;

import org.springframework.http.HttpStatus;

/** Violação de regra de negócio (HTTP 422). */
public class BusinessException extends ApiException {
    public BusinessException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
