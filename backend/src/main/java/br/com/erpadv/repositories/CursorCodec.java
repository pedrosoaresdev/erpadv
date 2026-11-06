package br.com.erpadv.repositories;

import br.com.erpadv.exceptions.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Codifica o cursor de paginação como base64url de {@code [valorDaOrdenacao, idDoDocumento]}.
 * Assim a próxima página usa {@code startAfter} sem precisar reler o último documento.
 */
final class CursorCodec {

    private static final ObjectMapper JSON = new ObjectMapper();

    private CursorCodec() {
    }

    static String encode(Object orderValue, String id) {
        try {
            byte[] json = JSON.writeValueAsBytes(new Object[]{orderValue, id});
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static Object[] decode(String cursor) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(cursor);
            List<?> values = JSON.readValue(json, List.class);
            if (values.size() != 2 || !(values.get(1) instanceof String)) {
                throw new IllegalArgumentException("cursor");
            }
            Object order = values.get(0) instanceof Integer i ? Long.valueOf(i) : values.get(0);
            return new Object[]{order, values.get(1)};
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "Cursor de paginação inválido.");
        }
    }
}
