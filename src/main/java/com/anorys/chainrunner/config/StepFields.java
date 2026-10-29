package com.anorys.chainrunner.config;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Map;

/**
 * The fields of one step as written in the file, for the {@link com.anorys.chainrunner.step.StepKind} that
 * reads them: the same readers as the rest of the configuration, and errors that say where the field is.
 */
public final class StepFields {

    private final JsonNode node;
    private final String where;

    StepFields(JsonNode node, String where) {
        this.node = node;
        this.where = where;
    }

    /** {@code sequences.<name>.steps[<i>]}: where the step is, for error messages. */
    public String where() {
        return where;
    }

    public boolean has(String field) {
        return node.hasNonNull(field);
    }

    /** The field as written (a value, a list or an object), or null. */
    public JsonNode node(String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value;
    }

    public String text(String field, String fallback) {
        return ConfigLoader.text(node, field, fallback);
    }

    /** A text that must be set and not blank, stripped; {@code why} completes the error message. */
    public String required(String field, String why) {
        String value = text(field, "");
        if (value.isBlank()) {
            throw error(field + " is required" + (why == null ? "" : " " + why));
        }
        return value.strip();
    }

    public boolean bool(String field, boolean fallback) {
        return node.path(field).asBoolean(fallback);
    }

    /** A boolean when set, else null. */
    public Boolean optionalBool(String field) {
        return node.hasNonNull(field) ? node.get(field).asBoolean() : null;
    }

    /** {@code 10s}, {@code 2m}, {@code 1h}, {@code 500ms}, or a number of seconds. */
    public Duration duration(String field, Duration fallback) {
        return ConfigLoader.duration(node, field, fallback, where);
    }

    /** An enum value written in lower case ({@code next}, {@code client_credentials}). */
    public <E extends Enum<E>> E choice(String field, E fallback) {
        return ConfigLoader.choice(node, field, fallback, where);
    }

    /** A map of scalars or lists of scalars, their types kept. */
    public Map<String, Object> values(String field) {
        return ConfigLoader.values(node.path(field), where + "." + field);
    }

    /** A map of texts. */
    public Map<String, String> strings(String field) {
        return ConfigLoader.strings(node.path(field), where + "." + field);
    }

    /** An error about this step: {@code message} is prefixed with where the step is. */
    public ConfigException error(String message) {
        return new ConfigException(where + "." + message);
    }
}
