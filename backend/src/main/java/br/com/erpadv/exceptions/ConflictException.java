package br.com.erpadv.exceptions;

import org.springframework.http.HttpStatus;

public class ConflictException extends ApiException {
    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
