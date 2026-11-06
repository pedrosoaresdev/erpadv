package br.com.erpadv.firebase;

import br.com.erpadv.exceptions.ApiException;
import br.com.erpadv.exceptions.DataAccessFailure;
import com.google.api.core.ApiFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Aguarda operações assíncronas do Firebase com timeout e traduz falhas para HTTP 503. */
public final class Futures {

    private static final long TIMEOUT_SECONDS = 15;

    private Futures() {
    }

    public static <T> T await(ApiFuture<T> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataAccessFailure("Operação interrompida.", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ApiException api) {
                throw api;
            }
            throw new DataAccessFailure("Serviço de dados indisponível no momento.", e.getCause());
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new DataAccessFailure("Tempo de resposta do serviço de dados excedido.", e);
        }
    }
}
