package br.com.erpadv.firebase;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.cloud.firestore.DocumentSnapshot;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Converte entidades ⇄ mapas do Firestore.
 * <ul>
 *   <li>{@code Instant} → epoch millis (número, ordenável e comparável em consultas)</li>
 *   <li>{@code LocalDate} → "yyyy-MM-dd" (string ordenável)</li>
 *   <li>enums → nome; nulos são omitidos</li>
 * </ul>
 */
@Component
public class FirestoreMapper {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ObjectMapper mapper;

    public FirestoreMapper() {
        this.mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
                .disable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .build();
        mapper.configOverride(LocalDate.class).setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING));
    }

    public Map<String, Object> toMap(Object entity) {
        return mapper.convertValue(entity, MAP);
    }

    public <T> T fromMap(Map<String, Object> data, Class<T> type) {
        return mapper.convertValue(data, type);
    }

    public <T> T fromSnapshot(DocumentSnapshot snapshot, Class<T> type) {
        if (snapshot == null || !snapshot.exists() || snapshot.getData() == null) {
            return null;
        }
        Map<String, Object> data = new HashMap<>(snapshot.getData());
        data.put("id", snapshot.getId());
        return fromMap(data, type);
    }

    /** Valor de um campo como gravado no Firestore (útil para cursores e filtros). */
    public Object toFirestoreValue(Object value) {
        if (value == null) {
            return null;
        }
        return mapper.convertValue(Map.of("v", value), MAP).get("v");
    }
}
