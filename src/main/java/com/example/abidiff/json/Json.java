package com.example.abidiff.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Shared Jackson mapper; ordering is fixed so content hashes are stable. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private Json() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("json write failed", e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("json read failed for " + type.getSimpleName(), e);
        }
    }

    public static JsonNode tree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("json tree failed", e);
        }
    }

    public static <T> T convert(JsonNode node, Class<T> type) {
        return MAPPER.convertValue(node, type);
    }
}
