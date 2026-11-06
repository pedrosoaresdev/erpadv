package br.com.erpadv.exceptions;

import org.springframework.http.HttpStatus;

/** Falha de comunicação com Firebase/Firestore (HTTP 503). O detalhe técnico vai só para o log. */
public class DataAccessFailure extends ApiException {
    public DataAccessFailure(String message, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "DATA_UNAVAILABLE", message, cause);
    }
}
