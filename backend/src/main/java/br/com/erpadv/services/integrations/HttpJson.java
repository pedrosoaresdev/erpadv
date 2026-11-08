package br.com.erpadv.services.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Cliente HTTP mínimo para as APIs oficiais (JSON, timeouts e erros legíveis). */
public final class HttpJson {

    public static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private HttpJson() {
    }

    /** Falha de comunicação com o serviço externo (indisponível, credencial recusada, resposta inválida). */
    public static class ExternalServiceException extends RuntimeException {
        private final int status;

        public ExternalServiceException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    public static JsonNode get(String url, Map<String, String> headers) {
        return send(HttpRequest.newBuilder(URI.create(url)).GET(), headers);
    }

    public static JsonNode post(String url, Map<String, String> headers, Object body) {
        try {
            String json = body instanceof String s ? s : JSON.writeValueAsString(body);
            return send(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .header("Content-Type", "application/json"), headers);
        } catch (IOException e) {
            throw new ExternalServiceException(0, "Falha ao preparar a requisição: " + e.getMessage());
        }
    }

    public static JsonNode postForm(String url, Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        form.forEach((k, v) -> {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(java.net.URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=').append(java.net.URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return send(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(sb.toString()))
                .header("Content-Type", "application/x-www-form-urlencoded"), Map.of());
    }

    public static JsonNode request(String method, String url, Map<String, String> headers, Object body) {
        try {
            HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8);
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).method(method, publisher);
            if (body != null) {
                b.header("Content-Type", "application/json");
            }
            return send(b, headers);
        } catch (IOException e) {
            throw new ExternalServiceException(0, "Falha ao preparar a requisição: " + e.getMessage());
        }
    }

    public static byte[] download(String url, Map<String, String> headers, long maxBytes) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(60));
        headers.forEach(b::header);
        try {
            HttpResponse<byte[]> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() / 100 != 2) {
                throw new ExternalServiceException(r.statusCode(), "Download recusado (HTTP " + r.statusCode() + ").");
            }
            if (r.body().length > maxBytes) {
                throw new ExternalServiceException(r.statusCode(), "Arquivo maior que o permitido.");
            }
            return r.body();
        } catch (IOException e) {
            throw new ExternalServiceException(0, "Serviço indisponível: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalServiceException(0, "Operação interrompida.");
        }
    }

    private static JsonNode send(HttpRequest.Builder builder, Map<String, String> headers) {
        builder.timeout(Duration.ofSeconds(30)).header("Accept", "application/json").header("User-Agent", "ERPAdv/1.0 (gestao juridica)");
        headers.forEach(builder::header);
        try {
            HttpResponse<String> r = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = r.body() == null ? "" : r.body();
            if (r.statusCode() / 100 != 2) {
                String detail = body.length() > 300 ? body.substring(0, 300) : body;
                throw new ExternalServiceException(r.statusCode(), switch (r.statusCode()) {
                    case 401, 403 -> "Credencial recusada pelo serviço (HTTP " + r.statusCode() + ").";
                    case 404 -> "Recurso não encontrado no serviço (HTTP 404).";
                    case 429 -> "Limite de requisições do serviço atingido — tente mais tarde.";
                    default -> r.statusCode() >= 500 ? "Serviço indisponível no momento (HTTP " + r.statusCode() + ")."
                            : "Requisição recusada (HTTP " + r.statusCode() + "): " + detail;
                });
            }
            return body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
        } catch (IOException e) {
            throw new ExternalServiceException(0, "Serviço indisponível: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalServiceException(0, "Operação interrompida.");
        }
    }
}
