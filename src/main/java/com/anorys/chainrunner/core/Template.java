package com.anorys.chainrunner.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code {{name}}} placeholders. A value that is only a placeholder keeps the variable's type (a boolean
 * stays a boolean in a JSON body, a list stays a list in {@code forEach}); inside text, values are
 * written as text, lists joined with commas.
 * <p>
 * Filters transform a value: {@code {{today | date("YYYY")}}}, {@code {{today | plusMonths(-1) | date("MM")}}},
 * {@code {{n | number}}}. See {@link #apply}.
 */
public final class Template {

    /** {@code {{name}}} or {@code {{name | filter(arg) | filter}}}: group 1 the name, group 2 the filters. */
    static final Pattern VAR = Pattern.compile(
            "\\{\\{\\s*([A-Za-z_][A-Za-z0-9_.-]*)\\s*((?:\\|\\s*[A-Za-z]+\\s*(?:\\([^)]*\\))?\\s*)*)}}");
    private static final Pattern FILTER = Pattern.compile("\\|\\s*([A-Za-z]+)\\s*(?:\\(([^)]*)\\))?");

    private Template() {
    }

    /** Renders {@code text}; an unknown variable fails with its name. */
    public static Object value(String text, Map<String, Object> vars) {
        if (text == null) {
            return null;
        }
        Matcher full = VAR.matcher(text.strip());
        if (full.matches()) {
            String name = full.group(1);
            if (!vars.containsKey(name)) {
                throw new UnknownVariableException(name);
            }
            return apply(vars.get(name), full.group(2));
        }
        return text(text, vars);
    }

    public static String text(String text, Map<String, Object> vars) {
        return render(text, vars, false);
    }

    /** Renders what is known and leaves the other placeholders as they are (for names shown before a run). */
    public static String preview(String text, Map<String, Object> vars) {
        return render(text, vars, true);
    }

    private static String render(String text, Map<String, Object> vars, boolean keepUnknown) {
        if (text == null) {
            return null;
        }
        Matcher m = VAR.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String replacement;
            if (vars.containsKey(name)) {
                replacement = format(apply(vars.get(name), m.group(2)));
            } else if (keepUnknown) {
                replacement = m.group();
            } else {
                throw new UnknownVariableException(name);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * The filters of a placeholder, applied in order (a list: to each of its values).
     * <ul>
     * <li>{@code date("YYYY")}, {@code date("MM")}, {@code date("YYYYMMdd")}: a date written with a pattern
     * ({@code YYYY}/{@code yyyy} year, {@code MM} month, {@code DD}/{@code dd} day, {@code M} month
     * without zero, {@code HH:mm} time); the value is a date ({@code 2026-10-28}) or a date and time.</li>
     * <li>{@code plusDays(n)}, {@code plusMonths(n)}, {@code plusYears(n)}: a date moved by {@code n}
     * ({@code -1} for yesterday or last month).</li>
     * <li>{@code number}: the value as a number ({@code "07"} → 7).</li>
     * <li>{@code upper}, {@code lower}, {@code trim}.</li>
     * </ul>
     */
    static Object apply(Object value, String filters) {
        if (filters == null || filters.isBlank()) {
            return value;
        }
        Matcher f = FILTER.matcher(filters);
        while (f.find()) {
            String name = f.group(1);
            String arg = f.group(2) == null ? null : unquote(f.group(2));
            value = each(value, v -> filter(name, arg, v));
        }
        return value;
    }

    private static Object each(Object value, java.util.function.UnaryOperator<Object> op) {
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(v -> out.add(op.apply(v)));
            return List.copyOf(out);
        }
        return op.apply(value);
    }

    private static Object filter(String name, String arg, Object value) {
        switch (name) {
            case "date" -> {
                String pattern = arg == null || arg.isBlank() ? "yyyy-MM-dd"
                        : arg.replace("YYYY", "yyyy").replace("YY", "yy").replace("DD", "dd");
                try {
                    return DateTimeFormatter.ofPattern(pattern).format(temporal(value, name));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("date(\"" + arg + "\"): invalid format (" + e.getMessage() + ")", e);
                }
            }
            case "plusDays", "plusMonths", "plusYears" -> {
                long n;
                try {
                    n = Long.parseLong(arg == null ? "" : arg.strip().replaceFirst("^\\+", ""));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(name + "(n): a number is expected, not '" + arg + "'");
                }
                Temporal t = temporal(value, name);
                Temporal moved = switch (name) {
                    case "plusDays" -> t instanceof LocalDate d ? d.plusDays(n) : ((LocalDateTime) t).plusDays(n);
                    case "plusMonths" -> t instanceof LocalDate d ? d.plusMonths(n) : ((LocalDateTime) t).plusMonths(n);
                    default -> t instanceof LocalDate d ? d.plusYears(n) : ((LocalDateTime) t).plusYears(n);
                };
                return moved.toString();
            }
            case "number" -> {
                String s = format(value).strip();
                try {
                    return Long.parseLong(s);
                } catch (NumberFormatException e) {
                    try {
                        return Double.parseDouble(s.replace(',', '.'));
                    } catch (NumberFormatException e2) {
                        throw new IllegalArgumentException("number: '" + s + "' is not a number");
                    }
                }
            }
            case "upper" -> {
                return format(value).toUpperCase(java.util.Locale.ROOT);
            }
            case "lower" -> {
                return format(value).toLowerCase(java.util.Locale.ROOT);
            }
            case "trim" -> {
                return format(value).strip();
            }
            default -> throw new IllegalArgumentException("unknown filter '" + name
                    + "' (date, plusDays, plusMonths, plusYears, number, upper, lower, trim)");
        }
    }

    /** A date ({@code 2026-10-28}) or a date and time ({@code 2026-10-28T14:05:00}). */
    private static Temporal temporal(Object value, String filter) {
        if (value instanceof LocalDate || value instanceof LocalDateTime) {
            return (Temporal) value;
        }
        String s = format(value).strip();
        try {
            return s.length() <= 10 ? LocalDate.parse(s) : LocalDateTime.parse(s.replace(' ', 'T'));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(filter + ": '" + s + "' is not a date (2026-10-28 or 2026-10-28T14:05:00)");
        }
    }

    private static String unquote(String arg) {
        String s = arg.strip();
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    public static String format(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            list.forEach(v -> parts.add(format(v)));
            return String.join(",", parts);
        }
        if (value instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
            return String.valueOf(d.longValue());
        }
        return String.valueOf(value);
    }

    /** A copy of a JSON body with its strings rendered. */
    public static JsonNode json(JsonNode node, Map<String, Object> vars) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            return toNode(value(node.asText(), vars));
        }
        if (node.isObject()) {
            ObjectNode copy = f.objectNode();
            node.fields().forEachRemaining(e -> copy.set(text(e.getKey(), vars), json(e.getValue(), vars)));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = f.arrayNode();
            node.forEach(n -> copy.add(json(n, vars)));
            return copy;
        }
        return node;
    }

    private static JsonNode toNode(Object value) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        if (value instanceof JsonNode node) {
            return node;
        }
        if (value instanceof Boolean b) {
            return f.booleanNode(b);
        }
        if (value instanceof Long l) {
            return f.numberNode(l);
        }
        if (value instanceof Integer i) {
            return f.numberNode(i);
        }
        if (value instanceof Double d) {
            return f.numberNode(d);
        }
        if (value instanceof List<?> list) {
            ArrayNode array = f.arrayNode();
            list.forEach(v -> array.add(toNode(v)));
            return array;
        }
        return value == null ? f.nullNode() : f.textNode(String.valueOf(value));
    }

    /** A {@code {{name}}} with no value. */
    public static final class UnknownVariableException extends RuntimeException {

        private final String name;

        public UnknownVariableException(String name) {
            super("unknown variable {{" + name + "}}");
            this.name = name;
        }

        public String name() {
            return name;
        }
    }
}
