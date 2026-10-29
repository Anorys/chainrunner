package com.anorys.chainrunner.core;

import com.anorys.chainrunner.config.ChainRunnerConfig;
import com.anorys.chainrunner.config.ChainRunnerConfig.Api;
import com.anorys.chainrunner.config.ChainRunnerConfig.Call;
import com.anorys.chainrunner.config.ChainRunnerConfig.Environment;
import com.anorys.chainrunner.config.ChainRunnerConfig.Guard;
import com.anorys.chainrunner.config.ChainRunnerConfig.OnManyRows;
import com.anorys.chainrunner.config.ChainRunnerConfig.OnTimeout;
import com.anorys.chainrunner.config.ChainRunnerConfig.Options;
import com.anorys.chainrunner.config.ChainRunnerConfig.Poll;
import com.anorys.chainrunner.config.ChainRunnerConfig.Save;
import com.anorys.chainrunner.core.CallResult.Request;
import com.anorys.chainrunner.core.CallResult.Status;
import com.anorys.chainrunner.core.Planner.PlannedCall;
import com.anorys.chainrunner.core.Template.UnknownVariableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Makes the planned steps one after the other: renders each one with the variables known at that point
 * (including the values extracted by earlier steps), and runs it.
 * <ul>
 * <li>An HTTP step gets the token and checks the status. A 2xx goes on; a 401 renews the token (refresh
 * token, or a new login) and retries once; a 5xx stops the sequence (see {@link #halts}). With
 * {@code saveTo}, the response is written to a file.</li>
 * <li>A {@code poll} step runs its query every {@code every} until it returns a row, or {@code timeout}
 * has passed.</li>
 * </ul>
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

    /**
     * Whether a failed HTTP step stops the sequence: no response (network, token, variable) or a 401 that
     * a new token did not fix always does; a 5xx and other statuses as configured for the step, or in
     * the options.
     */
    boolean halts(Call call, Integer status) {
        if (status == null || status == 401) {
            return true;
        }
        if (status >= 500) {
            return call.stopOnServerError() != null ? call.stopOnServerError() : options.stopOnServerError();
        }
        return call.stopOnClientError() != null ? call.stopOnClientError() : options.stopOnClientError();
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
        return planned.call().isPoll() ? poll(planned, request, listener) : http(planned, request, vars);
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
            return failed(request, null, since(start), null, guard.keyword() + ": query refused: " + sqlMessage(e), List.of(), true);
        }
        boolean found = !rows.isEmpty();
        if (found != guard.skipWhenFound()) {
            return null;
        }
        listener.started(planned, request);
        String why = guard.skipWhenFound() ? "not needed: skipIf found " + (rows.size() == 1 ? "a row" : rows.size() + " rows")
                : "not needed: onlyIf found no row";
        return new CallResult(Status.UNNEEDED, request, null, since(start), rows(rows), why, Map.of(), List.of(), false);
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
            throw new ConnectionException("cannot connect to database " + config.name() + ": " + sqlMessage(e));
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

    // ---------------------------------------------------------------- HTTP

    private CallResult http(PlannedCall planned, Request request, Map<String, Object> vars) throws InterruptedException {
        Call call = planned.call();
        long start = System.nanoTime();
        TokenClient client = tokens.of(env.api(call.api()));
        try {
            HttpResponse<byte[]> response = send(request, call.auth() ? client : null);
            if (response.statusCode() == 401 && call.auth() && client.auth().enabled()) {
                client.invalidate();
                response = send(request, client);
                if (response.statusCode() == 401) {
                    return failed(request, 401, since(start), display(text(response), null),
                            "401 even with a new token: missing permissions?", List.of(), true);
                }
            }
            Duration took = since(start);
            int status = response.statusCode();
            String body = text(response);
            Save save = call.save();
            List<Integer> expected = call.expectStatus();
            boolean statusOk = expected.isEmpty() ? status / 100 == 2 : expected.contains(status);
            if (!statusOk) {
                return failed(request, status, took, display(body, null), "status " + status
                        + (expected.isEmpty() ? " (2xx expected)" : " (expected " + expected + ")"), List.of(),
                        halts(call, status));
            }
            boolean binary = save != null && save.base64Pointer() == null;
            String shown = binary ? "(" + size(response.body().length) + " received)" : display(body, save == null ? null : save.base64Pointer());
            Map<String, Object> extracted = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : call.extract().entrySet()) {
                Object value = extract(response.headers(), binary ? "" : body, e.getValue());
                if (value == null) {
                    return failed(request, status, took, shown,
                            "extract " + e.getKey() + ": " + e.getValue() + " is not in the response", List.of(), halts(call, status));
                }
                extracted.put(e.getKey(), value);
            }
            List<String> notes = new ArrayList<>();
            if (save != null) {
                String error = save(save, vars, response.body(), body, notes);
                if (error != null) {
                    return failed(request, status, took, shown, error, notes, false);
                }
            }
            return new CallResult(Status.OK, request, status, took, shown, null, Map.copyOf(extracted), List.copyOf(notes), false);
        } catch (IOException e) {
            String message = e instanceof TokenClient.TokenException ? "token refused: " + e.getMessage()
                    : e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return failed(request, null, since(start), null, message, List.of(), true);
        }
    }

    /** Writes a download; returns the error, or null and a note with the file. */
    private String save(Save save, Map<String, Object> vars, byte[] raw, String body, List<String> notes) {
        Path target;
        try {
            target = options.baseDir().resolve(Template.text(save.saveTo(), vars)).normalize();
        } catch (UnknownVariableException | IllegalArgumentException e) {
            return "saveTo: " + e.getMessage();
        }
        byte[] bytes = raw;
        if (save.base64Pointer() != null) {
            JsonNode node;
            try {
                node = JSON.readTree(body).at(save.base64Pointer());
            } catch (IOException e) {
                return "the response is not JSON: cannot read " + save.base64Pointer();
            }
            if (node == null || node.isMissingNode() || node.isNull() || !node.isTextual()) {
                return save.base64Pointer() + " is not in the response (base64 text expected)";
            }
            try {
                bytes = Base64.getMimeDecoder().decode(node.asText());
            } catch (IllegalArgumentException e) {
                return save.base64Pointer() + " is not base64: " + e.getMessage();
            }
        }
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            if (save.overwrite()) {
                Files.write(target, bytes);
            } else {
                Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
        } catch (FileAlreadyExistsException e) {
            return "the file already exists: " + target + " (overwrite: true to replace it)";
        } catch (IOException e) {
            return "cannot write " + target + ": " + e.getMessage();
        }
        notes.add("file: " + target + " (" + size(bytes.length) + ")");
        return null;
    }

    /** Sends {@code request}, with the token of {@code client} unless it is null. */
    private HttpResponse<byte[]> send(Request request, TokenClient client) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.url())).timeout(options.timeout());
        request.headers().forEach(builder::header);
        if (client != null) {
            client.header().forEach(builder::setHeader);
        }
        builder.method(request.method(), request.body() == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(request.body()));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String text(HttpResponse<byte[]> response) {
        return response.body() == null ? "" : new String(response.body(), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- poll

    private CallResult poll(PlannedCall planned, Request request, Listener listener) throws InterruptedException {
        Poll poll = planned.call().poll();
        Map<String, String> columns = planned.call().extract();
        long start = System.nanoTime();
        Database db;
        try {
            db = connection(env.database(planned.call().database()));
        } catch (ConnectionException | IllegalArgumentException e) {
            return failed(request, null, since(start), null, e.getMessage(), List.of(), true);
        }
        long deadline = start + poll.timeout().toNanos();
        for (int attempt = 1; ; attempt++) {
            List<Map<String, Object>> rows;
            try {
                rows = db.query(request.body(), request.params());
            } catch (SQLException e) {
                return failed(request, null, since(start), null, "query refused: " + sqlMessage(e),
                        List.of(attemptNote(attempt, start, poll)), true);
            }
            String missing = missingColumn(rows, columns);
            if (missing != null) {
                return failed(request, null, since(start), rows(rows), missing, List.of(attemptNote(attempt, start, poll)), false);
            }
            List<Map<String, Object>> hits = rows.stream().filter(r -> found(r, columns)).toList();
            if (!hits.isEmpty()) {
                String when = "found at attempt " + attempt + " after " + clock(since(start));
                if (hits.size() > 1 && poll.onManyRows() == OnManyRows.FAIL) {
                    return failed(request, null, since(start), rows(hits), hits.size() + " rows, one expected (onManyRows: fail)",
                            List.of(when), false);
                }
                Map<String, Object> row = hits.get(0);
                Map<String, Object> extracted = new LinkedHashMap<>();
                if (columns.isEmpty()) {
                    row.forEach((k, v) -> {
                        if (v != null) {
                            extracted.put(k, v);
                        }
                    });
                } else {
                    columns.forEach((name, column) -> extracted.put(name, column(row, column)));
                }
                List<String> notes = new ArrayList<>();
                notes.add(when);
                if (hits.size() > 1) {
                    notes.add(hits.size() + " rows: the first one is taken");
                }
                return new CallResult(Status.OK, request, null, since(start), rows(hits), null, Map.copyOf(extracted),
                        List.copyOf(notes), false);
            }
            long now = System.nanoTime();
            if (now >= deadline) {
                return failed(request, null, since(start), null, "no row after " + clock(poll.timeout())
                                + (poll.onTimeout() == OnTimeout.STOP ? " (onTimeout: stop)" : ""),
                        List.of(attemptNote(attempt, start, poll)), poll.onTimeout() == OnTimeout.STOP);
            }
            listener.progress(planned, CallResult.running(request, since(start), List.of(attemptNote(attempt, start, poll))));
            long wait = Math.min(poll.every().toNanos(), deadline - now);
            if (!sleep(wait)) {
                return failed(request, null, since(start), null, "stopped by the user",
                        List.of(attemptNote(attempt, start, poll)), true);
            }
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

    private static String attemptNote(int attempt, long start, Poll poll) {
        return "attempt " + attempt + " · " + clock(since(start)) + " / " + clock(poll.timeout());
    }

    /** A row is found when every extracted column has a value (all its columns without {@code extract}). */
    private static boolean found(Map<String, Object> row, Map<String, String> columns) {
        for (String column : columns.values()) {
            if (column(row, column) == null) {
                return false;
            }
        }
        return true;
    }

    private static String missingColumn(List<Map<String, Object>> rows, Map<String, String> columns) {
        if (rows.isEmpty()) {
            return null;
        }
        for (String column : columns.values()) {
            if (rows.get(0).keySet().stream().noneMatch(k -> k.equalsIgnoreCase(column))) {
                return "extract: column '" + column + "' is not in the result (columns: "
                        + String.join(", ", rows.get(0).keySet()) + ")";
            }
        }
        return null;
    }

    /** A column of a row, its name in any case: PostgreSQL folds {@code docID} to {@code docid}. */
    private static Object column(Map<String, Object> row, String name) {
        if (row.containsKey(name)) {
            return row.get(name);
        }
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String rows(List<Map<String, Object>> rows) {
        StringBuilder out = new StringBuilder();
        int shown = Math.min(rows.size(), 10);
        for (int i = 0; i < shown; i++) {
            List<String> cells = new ArrayList<>();
            rows.get(i).forEach((k, v) -> cells.add(k + " = " + (v == null ? "null" : Template.format(v))));
            out.append(String.join("  ·  ", cells)).append('\n');
        }
        if (rows.size() > shown) {
            out.append("… ").append(rows.size() - shown).append(" more rows\n");
        }
        return out.toString().stripTrailing();
    }

    private static String sqlMessage(SQLException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().strip();
        return e.getSQLState() == null ? message : message + " [" + e.getSQLState() + "]";
    }

    // ---------------------------------------------------------------- requests

    /**
     * The request with every variable rendered: for an HTTP step the URL with its query, the headers and
     * the body; for a {@code poll} step the query with its parameters.
     */
    public Request request(Call call, Map<String, Object> vars) {
        if (call.isPoll()) {
            return sql(env.database(call.database()), call.poll().sql(), vars);
        }
        Api api = env.api(call.api());
        String url;
        if (call.url() != null && !call.url().isBlank()) {
            url = Template.text(call.url(), vars);
        } else {
            if (api.baseUrl() == null || api.baseUrl().isBlank()) {
                throw new IllegalArgumentException("empty baseUrl for environment " + env.name()
                        + ", API " + api.name());
            }
            String path = Template.text(call.path(), vars);
            url = api.baseUrl().replaceAll("/+$", "") + "/" + path.replaceAll("^/+", "");
        }
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, Object> q : call.query().entrySet()) {
            Object value = q.getValue() instanceof String s ? Template.value(s, vars) : q.getValue();
            List<?> values = value instanceof List<?> list ? list : java.util.Collections.singletonList(value);
            for (Object v : values) {
                pairs.add(enc(q.getKey()) + "=" + enc(Template.format(v)));
            }
        }
        if (!pairs.isEmpty()) {
            url += (url.contains("?") ? "&" : "?") + String.join("&", pairs);
        }
        URI.create(url);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", call.save() != null && call.save().base64Pointer() == null ? "*/*" : "application/json");
        env.headers().forEach((k, v) -> headers.put(k, Template.text(v, vars)));
        api.headers().forEach((k, v) -> headers.put(k, Template.text(v, vars)));
        call.headers().forEach((k, v) -> headers.put(k, Template.text(v, vars)));

        String body = null;
        JsonNode template = call.body();
        if (template != null) {
            if (template.isTextual()) {
                body = Template.text(template.asText(), vars);
                headers.putIfAbsent("Content-Type", "text/plain; charset=utf-8");
            } else {
                try {
                    body = JSON.writeValueAsString(Template.json(template, vars));
                } catch (JsonProcessingException e) {
                    throw new IllegalArgumentException("invalid JSON body: " + e.getOriginalMessage(), e);
                }
                headers.putIfAbsent("Content-Type", "application/json");
            }
        }
        return new Request(call.method(), url, Map.copyOf(headers), body);
    }

    /** A query of a {@code poll} step or of a guard, its variables as parameters. */
    public Request sql(ChainRunnerConfig.Database database, String template, Map<String, Object> vars) {
        Database.Statement statement = Database.Statement.of(template, vars);
        return new Request("SQL", database.url(), Map.of(), statement.sql(), statement.params());
    }

    /** The guard's query of {@code call}, rendered as {@link #request} renders the step. */
    public Request guardRequest(Call call, Map<String, Object> vars) {
        Guard guard = call.guard();
        return sql(env.database(guard.database() != null ? guard.database() : call.database()), guard.sql(), vars);
    }

    /**
     * A value of the response: {@code header:Name}, or a JSON pointer ({@code /data/id}); {@code $.data.id}
     * and {@code data.id} are read as {@code /data/id}.
     */
    static Object extract(HttpHeaders headers, String body, String path) {
        if (path.regionMatches(true, 0, "header:", 0, 7)) {
            return headers.firstValue(path.substring(7).strip()).orElse(null);
        }
        JsonNode node;
        try {
            node = JSON.readTree(body).at(pointer(path));
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
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

    private static String pointer(String path) {
        String pointer = path.strip();
        if (pointer.startsWith("$")) {
            pointer = pointer.substring(1);
        }
        if (!pointer.startsWith("/")) {
            pointer = "/" + pointer.replaceFirst("^\\.", "").replace('.', '/').replaceAll("\\[(\\d+)]", "/$1");
        }
        return pointer;
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
        return new CallResult(Status.FAILED, request, status, took, body, error, Map.of(), List.copyOf(notes), halt);
    }

    private static Duration since(long start) {
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

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
