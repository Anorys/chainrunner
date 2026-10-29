package com.anorys.chainrunner.core;

import com.anorys.chainrunner.config.ChainRunnerConfig;
import com.anorys.chainrunner.core.Template.UnknownVariableException;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The database a {@code poll} step queries: read-only, one query at a time. */
public interface Database extends AutoCloseable {

    /** The rows of {@code sql} ({@code ?} bound to {@code params} in order), each a column → value map. */
    List<Map<String, Object>> query(String sql, List<Object> params) throws SQLException;

    @Override
    void close();

    /** Opens a connection; the runner opens it at the first {@code poll} step and closes it at the end. */
    @FunctionalInterface
    interface Opener {
        Database open(ChainRunnerConfig.Database config, Duration timeout) throws SQLException;
    }

    /**
     * PostgreSQL through its JDBC driver, made directly rather than found by {@code DriverManager} (no
     * service lookup in the native image). {@code stringtype=unspecified} lets the server type the
     * parameters, so that {@code '2026-10-28'} compares with a {@code date} column and {@code '42'} with an
     * integer one.
     */
    static Database postgres(ChainRunnerConfig.Database config, Duration timeout) throws SQLException {
        Properties props = new Properties();
        if (config.username() != null && !config.username().isBlank()) {
            props.setProperty("user", config.username());
        }
        if (config.password() != null) {
            props.setProperty("password", config.password());
        }
        props.setProperty("stringtype", "unspecified");
        props.setProperty("ApplicationName", "chainrunner");
        props.setProperty("readOnly", "true");
        // The driver's default ("transaction") ignores readOnly in autocommit: "always" makes the whole
        // session read-only, so that even a "with ... delete ... select" is refused by the server.
        props.setProperty("readOnlyMode", "always");
        long seconds = Math.max(1, timeout.toSeconds());
        props.setProperty("connectTimeout", String.valueOf(Math.min(seconds, 30)));
        props.setProperty("socketTimeout", String.valueOf(seconds));
        Connection connection = new org.postgresql.Driver().connect(config.url(), props);
        if (connection == null) {
            throw new SQLException("URL not accepted by the PostgreSQL driver: " + config.url());
        }
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        return new Jdbc(connection, (int) Math.min(Integer.MAX_VALUE, seconds));
    }

    /** A JDBC connection; values come back as String, Long, Double or Boolean, the others as text. */
    final class Jdbc implements Database {

        private final Connection connection;
        private final int timeoutSeconds;

        Jdbc(Connection connection, int timeoutSeconds) {
            this.connection = connection;
            this.timeoutSeconds = timeoutSeconds;
        }

        @Override
        public List<Map<String, Object>> query(String sql, List<Object> params) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(timeoutSeconds);
                statement.setMaxRows(100);
                for (int i = 0; i < params.size(); i++) {
                    Object p = params.get(i);
                    if (p instanceof Long l) {
                        statement.setLong(i + 1, l);
                    } else if (p instanceof Double d) {
                        statement.setDouble(i + 1, d);
                    } else if (p instanceof Boolean b) {
                        statement.setBoolean(i + 1, b);
                    } else {
                        statement.setString(i + 1, Template.format(p));
                    }
                }
                List<Map<String, Object>> rows = new ArrayList<>();
                try (ResultSet rs = statement.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int c = 1; c <= meta.getColumnCount(); c++) {
                            row.put(meta.getColumnLabel(c), value(rs.getObject(c)));
                        }
                        rows.add(row);
                    }
                }
                return rows;
            } finally {
                // Each query in its own transaction, never committed: the next attempt sees new rows.
                connection.rollback();
            }
        }

        private static Object value(Object v) {
            if (v == null || v instanceof String || v instanceof Boolean || v instanceof Long || v instanceof Double) {
                return v;
            }
            if (v instanceof Integer || v instanceof Short || v instanceof Byte) {
                return ((Number) v).longValue();
            }
            if (v instanceof Float f) {
                return f.doubleValue();
            }
            if (v instanceof BigDecimal d) {
                return d.scale() <= 0 || d.stripTrailingZeros().scale() <= 0 ? (Object) d.longValue() : (Object) d.doubleValue();
            }
            return v.toString();
        }

        @Override
        public void close() {
            try {
                connection.close();
            } catch (SQLException e) {
                // Closing at the end of a run: nothing left to do with it.
            }
        }
    }

    /** A query whose {@code {{variables}}} became {@code ?}, with their values in order. */
    record Statement(String sql, List<Object> params) {

        private static final Pattern QUOTED = Pattern.compile("'\\s*(\\{\\{[^}]*}})\\s*'");
        private static final Pattern COMMENTS = Pattern.compile("(?s)(--[^\\n]*\\n?|/\\*.*?\\*/|\\s+)*");

        /**
         * Binds each {@code {{name}}} as a parameter (never as text in the query: no SQL injection, no
         * quoting); a list becomes {@code ?, ?, ?} for {@code in (...)}. Only a query that reads is accepted.
         */
        public static Statement of(String template, Map<String, Object> vars) {
            Matcher lead = COMMENTS.matcher(template);
            String start = lead.lookingAt() ? template.substring(lead.end()) : template;
            String verb = start.split("[\\s(]", 2)[0].toLowerCase(Locale.ROOT);
            if (!verb.equals("select") && !verb.equals("with")) {
                throw new IllegalArgumentException("only select (or with ... select) queries are accepted, not '" + verb + "'");
            }
            // '{{x}}' is read as {{x}}: the value is a parameter, quotes around it would make it the text "?".
            Matcher m = Template.VAR.matcher(QUOTED.matcher(template).replaceAll("$1"));
            StringBuilder sql = new StringBuilder();
            List<Object> params = new ArrayList<>();
            while (m.find()) {
                String name = m.group(1);
                if (!vars.containsKey(name)) {
                    throw new UnknownVariableException(name);
                }
                Object value = Template.apply(vars.get(name), m.group(2));
                String marks;
                if (value instanceof List<?> list && !list.isEmpty()) {
                    params.addAll(list);
                    marks = String.join(", ", java.util.Collections.nCopies(list.size(), "?"));
                } else {
                    params.add(value);
                    marks = "?";
                }
                m.appendReplacement(sql, Matcher.quoteReplacement(marks));
            }
            m.appendTail(sql);
            return new Statement(sql.toString(), List.copyOf(params));
        }

        /** The query with its values written in, for the screen and the log only. */
        public String shown() {
            StringBuilder out = new StringBuilder();
            int p = 0;
            for (char c : sql.toCharArray()) {
                if (c == '?' && p < params.size()) {
                    Object v = params.get(p++);
                    out.append(v instanceof String s ? "'" + s.replace("'", "''") + "'" : Template.format(v));
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
    }
}
