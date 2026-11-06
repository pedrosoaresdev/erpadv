package br.com.erpadv.exceptions;

import org.springframework.http.HttpStatus;

public class NotFoundException extends ApiException {
    public NotFoundException(String entity) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", entity + " não encontrado(a).");
    }
}
