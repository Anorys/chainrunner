package com.anorys.chainrunner.core;

import com.anorys.chainrunner.config.ChainRunnerConfig;
import com.anorys.chainrunner.config.ChainRunnerConfig.Call;
import com.anorys.chainrunner.config.ChainRunnerConfig.Environment;
import com.anorys.chainrunner.config.ChainRunnerConfig.Guard;
import com.anorys.chainrunner.config.ChainRunnerConfig.Options;
import com.anorys.chainrunner.core.CallResult.Request;
import com.anorys.chainrunner.core.CallResult.Status;
import com.anorys.chainrunner.core.Planner.PlannedCall;
import com.anorys.chainrunner.core.Template.UnknownVariableException;
import com.anorys.chainrunner.step.StepContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.http.HttpClient;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Makes the planned steps one after the other: renders each one with the variables known at that point
 * (including the values extracted by earlier steps), and runs it. What a step does is up to its
 * {@link com.anorys.chainrunner.step.Step} (an HTTP call, a query polled until it finds a row...); the
 * runner keeps what every step shares.
 * A step with {@code skipIf} (or {@code onlyIf}) first runs that query, and is not made when it returns a
 * row (or none). Each step calls one of the environment's APIs ({@code api}), with that API's token, and
 * queries one of its databases ({@code database}), each opened at its first use.
 * A value extracted inside a {@code forEach} belongs to that value: the {@code docId} found for ACC1 is
 * only seen by the steps for ACC1. When a step fails for one value without stopping the sequence, the
 * later steps for that value are skipped and the other values go on.
 */
public final class Runner {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final long TICK_MILLIS = 200;

    private final HttpClient http;
    private final TokenClients tokens;
    private final Environment env;
    private final Options options;
    private final Database.Opener opener;
    private final Function<ChainRunnerConfig.Database, ChainRunnerConfig.Database> passwords;

    /** The connections opened so far, by database name; closed at the end of {@link #run}. */
    private final Map<String, Database> open = new LinkedHashMap<>();
    private BooleanSupplier cancelled = () -> false;

    /** A runner for HTTP steps only, or to render requests ({@link #request}). */
    public Runner(HttpClient http, TokenClients tokens, Environment env, Options options) {
        this(http, tokens, env, options, Database::postgres, Function.identity());
    }

    /**
     * @param passwords gives a database's settings with its password once asked for (the settings as
     *                  they are when none is needed)
     */
    public Runner(HttpClient http, TokenClients tokens, Environment env, Options options, Database.Opener opener,
                  Function<ChainRunnerConfig.Database, ChainRunnerConfig.Database> passwords) {
        this.http = http;
        this.tokens = tokens;
        this.env = env;
        this.options = options;
        this.opener = opener;
        this.passwords = passwords;
    }

    /** Called on the runner's thread. */
    public interface Listener {

        void started(PlannedCall call, Request request);

        /** A step still running: a poll after each attempt. */
        default void progress(PlannedCall call, CallResult running) {
        }

        void finished(PlannedCall call, CallResult result);
    }

    /** Runs {@code calls} in order; returns one result per call, the ones not run as skipped. */
    public List<CallResult> run(List<PlannedCall> calls, Map<String, Object> vars, Listener listener,
                                BooleanSupplier cancelled) throws InterruptedException {
        this.cancelled = cancelled;
        Map<String, Object> global = new LinkedHashMap<>(vars);
        Map<Map<String, Object>, Map<String, Object>> scoped = new LinkedHashMap<>();
        Map<Map<String, Object>, String> abandoned = new LinkedHashMap<>();
        List<CallResult> results = new ArrayList<>();
        String stop = null;
        try {
            for (PlannedCall call : calls) {
                if (stop == null && cancelled.getAsBoolean()) {
                    stop = "stopped by the user";
                }
                String skip = stop != null ? stop : abandonedFor(call.loop(), abandoned);
                if (skip != null) {
                    CallResult skipped = CallResult.skipped(skip);
                    results.add(skipped);
                    listener.finished(call, skipped);
                    continue;
                }
                if (!results.isEmpty() && !options.delay().isZero()) {
                    Thread.sleep(options.delay().toMillis());
                }
                Map<String, Object> scope = new LinkedHashMap<>(global);
                scoped.forEach((loop, values) -> {
                    if (within(loop, call.loop())) {
                        scope.putAll(values);
                    }
                });
                CallResult result = execute(call, scope, listener);
                if (call.loop().isEmpty()) {
                    global.putAll(result.extracted());
                } else if (!result.extracted().isEmpty()) {
                    scoped.computeIfAbsent(call.loop(), k -> new LinkedHashMap<>()).putAll(result.extracted());
                }
                results.add(result);
                listener.finished(call, result);
                if (result.halt()) {
                    stop = "not run: step " + (call.index() + 1) + " stopped the sequence"
                            + (result.httpStatus() == null ? "" : " (HTTP " + result.httpStatus() + ")");
                } else if (result.status() == Status.FAILED && !call.loop().isEmpty()) {
                    abandoned.putIfAbsent(call.loop(), "not run: '" + call.name() + "' failed");
                }
            }
        } finally {
            open.values().forEach(Database::close);
            open.clear();
        }
        return results;
    }

    /** Whether every value of {@code inner} is also in {@code outer}: the loop {@code outer} is part of it. */
    private static boolean within(Map<String, Object> inner, Map<String, Object> outer) {
        return outer.entrySet().containsAll(inner.entrySet());
    }

    private static String abandonedFor(Map<String, Object> loop, Map<Map<String, Object>, String> abandoned) {
        for (Map.Entry<Map<String, Object>, String> e : abandoned.entrySet()) {
            if (within(e.getKey(), loop)) {
                return e.getValue();
            }
        }
        return null;
    }

    CallResult execute(PlannedCall planned, Map<String, Object> scope, Listener listener) throws InterruptedException {
        Map<String, Object> vars = new LinkedHashMap<>(scope);
        vars.putAll(planned.loop());
        if (planned.call().guard() != null) {
            CallResult unneeded = guard(planned, vars, listener);
            if (unneeded != null) {
                return unneeded;
            }
        }
        Request request;
        try {
            request = request(planned.call(), vars);
        } catch (UnknownVariableException | IllegalArgumentException e) {
            return failed(null, null, null, null, e.getMessage(), List.of(), true);
        }
        listener.started(planned, request);
        return planned.call().step().run(new Context(planned, listener), planned.call(), request, vars);
    }

    // ---------------------------------------------------------------- guard

    /** Runs the step's {@code skipIf} / {@code onlyIf}: the result when the step is not needed (or failed), else null. */
    private CallResult guard(PlannedCall planned, Map<String, Object> vars, Listener listener) {
        Guard guard = planned.call().guard();
        String databaseName = guard.database() != null ? guard.database() : planned.call().database();
        Request request;
        ChainRunnerConfig.Database config;
        try {
            config = env.database(databaseName);
            request = sql(config, guard.sql(), vars);
        } catch (UnknownVariableException | IllegalArgumentException e) {
            return failed(null, null, null, null, guard.keyword() + ": " + e.getMessage(), List.of(), true);
        }
        long start = System.nanoTime();
        List<Map<String, Object>> rows;
        try {
            Database db = connection(config);
            rows = db.query(request.body(), request.params());
        } catch (ConnectionException e) {
            listener.started(planned, request);
            return failed(request, null, since(start), null, e.getMessage(), List.of(), true);
        } catch (SQLException e) {
            listener.started(planned, request);
            return failed(request, null, since(start), null, guard.keyword() + ": query refused: " + Database.message(e), List.of(), true);
        }
        boolean found = !rows.isEmpty();
        if (found != guard.skipWhenFound()) {
            return null;
        }
        listener.started(planned, request);
        String why = guard.skipWhenFound() ? "not needed: skipIf found " + (rows.size() == 1 ? "a row" : rows.size() + " rows")
                : "not needed: onlyIf found no row";
        return new CallResult(Status.UNNEEDED, request, null, since(start), Database.shown(rows), why, Map.of(), List.of(), false);
    }

    /** The connection to {@code config}'s database, opened at its first use. */
    private Database connection(ChainRunnerConfig.Database config) throws ConnectionException {
        Database db = open.get(config.name());
        if (db != null) {
            return db;
        }
        try {
            db = opener.open(passwords.apply(config), options.timeout());
        } catch (SQLException e) {
            throw new ConnectionException("cannot connect to database " + config.name() + ": " + Database.message(e));
        }
        open.put(config.name(), db);
        return db;
    }

    /** The database cannot be reached; the message says which and why. */
    private static final class ConnectionException extends Exception {

        ConnectionException(String message) {
            super(message);
        }
    }

    /** What a step gets from the runner; {@code planned} is null when a request is only rendered. */
    private final class Context implements StepContext {

        private final PlannedCall planned;
        private final Listener listener;

        Context(PlannedCall planned, Listener listener) {
            this.planned = planned;
            this.listener = listener;
        }

        @Override
        public Environment env() {
            return env;
        }

        @Override
        public Options options() {
            return options;
        }

        @Override
        public HttpClient http() {
            return http;
        }

        @Override
        public TokenClients tokens() {
            return tokens;
        }

        @Override
        public Database database(String name) throws StepException {
            try {
                return connection(env.database(name));
            } catch (ConnectionException e) {
                throw new StepException(e.getMessage());
            }
        }

        @Override
        public boolean sleep(long nanos) throws InterruptedException {
            return Runner.this.sleep(nanos);
        }

        @Override
        public boolean cancelled() {
            return cancelled.getAsBoolean();
        }

        @Override
        public void progress(CallResult running) {
            if (planned != null) {
                listener.progress(planned, running);
            }
        }

        @Override
        public String display(String body, String hidden) {
            return Runner.this.display(body, hidden);
        }
    }

    /** Sleeps {@code nanos}, waking up to check for a stop; false when stopped. */
    private boolean sleep(long nanos) throws InterruptedException {
        long end = System.nanoTime() + nanos;
        while (true) {
            if (cancelled.getAsBoolean()) {
                return false;
            }
            long left = end - System.nanoTime();
            if (left <= 0) {
                return true;
            }
            Thread.sleep(Math.min(TICK_MILLIS, Math.max(1, left / 1_000_000)));
        }
    }

    // ---------------------------------------------------------------- requests

    /** The request of {@code call} with every variable rendered, as its step renders it. */
    public Request request(Call call, Map<String, Object> vars) {
        return call.step().request(new Context(null, null), call, vars);
    }

    /** A query of a {@code poll} step or of a guard, its variables as parameters. */
    public static Request sql(ChainRunnerConfig.Database database, String template, Map<String, Object> vars) {
        Database.Statement statement = Database.Statement.of(template, vars);
        return new Request("SQL", database.url(), Map.of(), statement.sql(), statement.params());
    }

    /** The guard's query of {@code call}, rendered as {@link #request} renders the step. */
    public Request guardRequest(Call call, Map<String, Object> vars) {
        Guard guard = call.guard();
        return sql(env.database(guard.database() != null ? guard.database() : call.database()), guard.sql(), vars);
    }

    /**
     * The body as shown and logged: JSON indented (the base64 document at {@code hidden} replaced by its
     * size), the rest as it came, cut at {@code maxBodyChars}.
     */
    private String display(String body, String hidden) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        String shown = body;
        String trimmed = body.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                JsonNode tree = JSON.readTree(trimmed);
                if (hidden != null) {
                    int slash = hidden.lastIndexOf('/');
                    JsonNode parent = tree.at(hidden.substring(0, slash));
                    String field = hidden.substring(slash + 1);
                    if (parent instanceof ObjectNode object && object.path(field).isTextual()) {
                        object.put(field, "<base64, " + object.path(field).asText().length() + " characters>");
                    }
                }
                shown = PRETTY.writeValueAsString(tree);
            } catch (IOException | IllegalArgumentException e) {
                shown = body;
            }
        }
        int max = options.maxBodyChars();
        return max > 0 && shown.length() > max ? shown.substring(0, max) + "\n… (" + (shown.length() - max) + " characters cut)" : shown;
    }

    private static CallResult failed(Request request, Integer status, Duration took, String body, String error,
                                     List<String> notes, boolean halt) {
        return CallResult.failed(request, status, took, body, error, notes, halt);
    }

    /** How long since {@code start}, a {@link System#nanoTime()}. */
    public static Duration since(long start) {
        return Duration.ofNanos(System.nanoTime() - start);
    }

    /** {@code 0:42}, {@code 12:05}, {@code 1:02:05}. */
    public static String clock(Duration d) {
        long s = d.toSeconds();
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** {@code 512 B}, {@code 12.3 KB}, {@code 4.1 MB}. */
    public static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        return kb < 1024 ? String.format(Locale.ROOT, "%.1f KB", kb) : String.format(Locale.ROOT, "%.1f MB", kb / 1024);
    }
}
