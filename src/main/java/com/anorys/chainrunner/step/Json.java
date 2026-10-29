package com.anorys.chainrunner.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/** Values read from a JSON text by {@code extract}. */
final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    /**
     * The value at a JSON pointer ({@code /data/id}; {@code $.data.id} and {@code data.id} are read as
     * {@code /data/id}): a Boolean, Long, Double or String, an object or a list as JSON text; null when
     * missing or when {@code json} is not JSON.
     */
    static Object value(String json, String path) {
        JsonNode node;
        try {
            node = MAPPER.readTree(json).at(pointer(path));
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
        return value(node);
    }

    static Object value(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }

    static String pointer(String path) {
        String pointer = path.strip();
        if (pointer.startsWith("$")) {
            pointer = pointer.substring(1);
        }
        if (!pointer.startsWith("/")) {
            pointer = "/" + pointer.replaceFirst("^\\.", "").replace('.', '/').replaceAll("\\[(\\d+)]", "/$1");
        }
        return pointer;
    }
}
