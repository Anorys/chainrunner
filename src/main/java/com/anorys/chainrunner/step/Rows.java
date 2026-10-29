package com.anorys.chainrunner.step;

import com.anorys.chainrunner.core.Database;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code extract} over rows (a query's, a CSV file's): {@code column} is the value of the first row,
 * {@code column[]} the list of the values of every row. Column names in any case.
 */
final class Rows {

    private Rows() {
    }

    /** The values of {@code columns} by variable name; every column of the first row without them. */
    static Map<String, Object> extract(List<Map<String, Object>> rows, Map<String, String> columns) throws ExtractException {
        Map<String, Object> extracted = new LinkedHashMap<>();
        if (columns.isEmpty()) {
            if (!rows.isEmpty()) {
                rows.get(0).forEach((k, v) -> {
                    if (v != null) {
                        extracted.put(k, v);
                    }
                });
            }
            return extracted;
        }
        for (Map.Entry<String, String> e : columns.entrySet()) {
            String spec = e.getValue().strip();
            boolean all = spec.endsWith("[]");
            String column = all ? spec.substring(0, spec.length() - 2).strip() : spec;
            if (!rows.isEmpty() && rows.get(0).keySet().stream().noneMatch(k -> k.equalsIgnoreCase(column))) {
                throw new ExtractException("extract " + e.getKey() + ": column '" + column + "' is not there (columns: "
                        + String.join(", ", rows.get(0).keySet()) + ")");
            }
            if (all) {
                List<Object> values = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    Object value = Database.column(row, column);
                    if (value != null) {
                        values.add(value);
                    }
                }
                extracted.put(e.getKey(), List.copyOf(values));
            } else if (!rows.isEmpty()) {
                Object value = Database.column(rows.get(0), column);
                if (value == null) {
                    throw new ExtractException("extract " + e.getKey() + ": column '" + column + "' is empty in the first row");
                }
                extracted.put(e.getKey(), value);
            }
        }
        return extracted;
    }

    static final class ExtractException extends Exception {

        ExtractException(String message) {
            super(message);
        }
    }
}
