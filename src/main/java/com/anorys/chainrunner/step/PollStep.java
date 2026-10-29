package com.anorys.chainrunner.step;

import com.anorys.chainrunner.config.ChainRunnerConfig.Call;
import com.anorys.chainrunner.config.StepFields;
import com.anorys.chainrunner.core.CallResult;
import com.anorys.chainrunner.core.CallResult.Request;
import com.anorys.chainrunner.core.CallResult.Status;
import com.anorys.chainrunner.core.Database;
import com.anorys.chainrunner.core.Runner;
import com.anorys.chainrunner.step.StepContext.StepException;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A SQL query run every {@code every} until it returns a row, or {@code timeout} has passed. A row is found
 * when every column of {@code extract} has a value; those values are extracted (every column of the row
 * without {@code extract}).
 */
public record PollStep(String sql, Duration every, Duration timeout, OnTimeout onTimeout, OnManyRows onManyRows)
        implements Step {

    public enum OnTimeout {
        /** The step fails, the other steps of the same value are skipped, the sequence goes on. */
        NEXT,
        /** The sequence stops. */
        STOP
    }

    public enum OnManyRows {
        FIRST, FAIL
    }

    /** {@code type: poll}. */
    public static final class Kind implements StepKind {

        @Override
        public String type() {
            return "poll";
        }

        @Override
        public Step parse(StepFields f) {
            return new PollStep(f.required("sql", "for a poll step"), f.duration("every", Duration.ofSeconds(10)),
                    f.duration("timeout", Duration.ofMinutes(15)), f.choice("onTimeout", OnTimeout.NEXT),
                    f.choice("onManyRows", OnManyRows.FIRST));
        }
    }

    @Override
    public String defaultName() {
        return "poll";
    }

    @Override
    public String label() {
        return "SQL";
    }

    @Override
    public String summary() {
        return sql;
    }

    @Override
    public String requestTitle() {
        return "SQL QUERY";
    }

    @Override
    public String waiting() {
        return "waiting for a row…";
    }

    @Override
    public List<Detail> details() {
        return List.of(new Detail("every", Runner.clock(every) + ", at most " + Runner.clock(timeout)
                + (onTimeout == OnTimeout.STOP ? ", then stop" : ", then go on")));
    }

    @Override
    public String outcome(CallResult result) {
        if (result.status() == Status.OK) {
            return "found";
        }
        return result.status() == Status.FAILED && result.error() != null && result.error().startsWith("no row") ? "timeout" : null;
    }

    @Override
    public boolean usesDatabase() {
        return true;
    }

    /** The query with its variables as parameters. */
    @Override
    public Request request(StepContext context, Call call, Map<String, Object> vars) {
        return Runner.sql(context.env().database(call.database()), sql, vars);
    }

    @Override
    public CallResult run(StepContext context, Call call, Request request, Map<String, Object> vars) throws InterruptedException {
        Map<String, String> columns = call.extract();
        long start = System.nanoTime();
        Database db;
        try {
            db = context.database(call.database());
        } catch (StepException | IllegalArgumentException e) {
            return CallResult.failed(request, null, Runner.since(start), null, e.getMessage(), List.of(), true);
        }
        long deadline = start + timeout.toNanos();
        for (int attempt = 1; ; attempt++) {
            List<Map<String, Object>> rows;
            try {
                rows = db.query(request.body(), request.params());
            } catch (SQLException e) {
                return CallResult.failed(request, null, Runner.since(start), null, "query refused: " + Database.message(e),
                        List.of(attemptNote(attempt, start)), true);
            }
            String missing = Database.missingColumn(rows, columns.values());
            if (missing != null) {
                return CallResult.failed(request, null, Runner.since(start), Database.shown(rows), missing,
                        List.of(attemptNote(attempt, start)), false);
            }
            List<Map<String, Object>> hits = rows.stream().filter(r -> found(r, columns)).toList();
            if (!hits.isEmpty()) {
                String when = "found at attempt " + attempt + " after " + Runner.clock(Runner.since(start));
                if (hits.size() > 1 && onManyRows == OnManyRows.FAIL) {
                    return CallResult.failed(request, null, Runner.since(start), Database.shown(hits),
                            hits.size() + " rows, one expected (onManyRows: fail)", List.of(when), false);
                }
                List<String> notes = new ArrayList<>();
                notes.add(when);
                if (hits.size() > 1) {
                    notes.add(hits.size() + " rows: the first one is taken");
                }
                return new CallResult(Status.OK, request, null, Runner.since(start), Database.shown(hits), null,
                        extracted(hits.get(0), columns), List.copyOf(notes), false);
            }
            long now = System.nanoTime();
            if (now >= deadline) {
                return CallResult.failed(request, null, Runner.since(start), null, "no row after " + Runner.clock(timeout)
                                + (onTimeout == OnTimeout.STOP ? " (onTimeout: stop)" : ""),
                        List.of(attemptNote(attempt, start)), onTimeout == OnTimeout.STOP);
            }
            context.progress(CallResult.running(request, Runner.since(start), List.of(attemptNote(attempt, start))));
            long wait = Math.min(every.toNanos(), deadline - now);
            if (!context.sleep(wait)) {
                return CallResult.failed(request, null, Runner.since(start), null, "stopped by the user",
                        List.of(attemptNote(attempt, start)), true);
            }
        }
    }

    /** The values of {@code columns} in {@code row}, by variable name; every column with a value without them. */
    static Map<String, Object> extracted(Map<String, Object> row, Map<String, String> columns) {
        Map<String, Object> extracted = new LinkedHashMap<>();
        if (columns.isEmpty()) {
            row.forEach((k, v) -> {
                if (v != null) {
                    extracted.put(k, v);
                }
            });
        } else {
            columns.forEach((name, column) -> extracted.put(name, Database.column(row, column)));
        }
        return Map.copyOf(extracted);
    }

    private String attemptNote(int attempt, long start) {
        return "attempt " + attempt + " · " + Runner.clock(Runner.since(start)) + " / " + Runner.clock(timeout);
    }

    /** A row is found when every extracted column has a value (all its columns without {@code extract}). */
    private static boolean found(Map<String, Object> row, Map<String, String> columns) {
        for (String column : columns.values()) {
            if (Database.column(row, column) == null) {
                return false;
            }
        }
        return true;
    }
}
