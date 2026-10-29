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
import java.util.List;
import java.util.Map;

/**
 * A query run once on one of the environment's databases (read-only: a {@code select}). {@code extract}
 * reads the first row ({@code column}) or every row ({@code column[]}). Without a row the step fails,
 * unless {@code allowEmpty}: then the lists are empty and the single values are not set.
 */
public record SqlStep(String sql, boolean allowEmpty) implements Step {

    /** {@code type: sql}. */
    public static final class Kind implements StepKind {

        @Override
        public String type() {
            return "sql";
        }

        @Override
        public Step parse(StepFields f) {
            return new SqlStep(f.required("sql", "for a sql step"), f.bool("allowEmpty", false));
        }
    }

    @Override
    public String defaultName() {
        return "sql";
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
        return "waiting for the rows…";
    }

    @Override
    public List<Detail> details() {
        return allowEmpty ? List.of(new Detail("no row", "allowed")) : List.of();
    }

    @Override
    public String outcome(CallResult result) {
        return result.status() == Status.OK && !result.notes().isEmpty() ? result.notes().get(0) : null;
    }

    @Override
    public boolean usesDatabase() {
        return true;
    }

    @Override
    public Request request(StepContext context, Call call, Map<String, Object> vars) {
        return Runner.sql(context.env().database(call.database()), sql, vars);
    }

    @Override
    public CallResult run(StepContext context, Call call, Request request, Map<String, Object> vars) {
        long start = System.nanoTime();
        List<Map<String, Object>> rows;
        try {
            rows = context.database(call.database()).query(request.body(), request.params());
        } catch (StepException | IllegalArgumentException e) {
            return CallResult.failed(request, null, Runner.since(start), null, e.getMessage(), List.of(), true);
        } catch (SQLException e) {
            return CallResult.failed(request, null, Runner.since(start), null, "query refused: " + Database.message(e), List.of(), true);
        }
        String shown = Database.shown(rows);
        if (rows.isEmpty() && !allowEmpty) {
            return CallResult.failed(request, null, Runner.since(start), shown, "no row (allowEmpty: true to go on)", List.of(), false);
        }
        try {
            Map<String, Object> extracted = Rows.extract(rows, call.extract());
            return new CallResult(Status.OK, request, null, Runner.since(start), shown, null, Map.copyOf(extracted),
                    List.of(rows.size() + (rows.size() == 1 ? " row" : " rows")), false);
        } catch (Rows.ExtractException e) {
            return CallResult.failed(request, null, Runner.since(start), shown, e.getMessage(), List.of(), false);
        }
    }
}
