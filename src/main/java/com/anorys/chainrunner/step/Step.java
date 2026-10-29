package com.anorys.chainrunner.step;

import com.anorys.chainrunner.config.ChainRunnerConfig.Call;
import com.anorys.chainrunner.core.CallResult;
import com.anorys.chainrunner.core.CallResult.Request;

import java.util.List;
import java.util.Map;

/**
 * What one step does, as read by its {@link StepKind}: an HTTP call, a query polled until it finds a row...
 * The runner keeps what every step shares (forEach, variables, skipIf / onlyIf, stopping the sequence) and
 * leaves the rest to the step: rendering its request, running it, and describing it for the screen.
 */
public interface Step {

    /** The name of a step written without one: {@code GET /orders}. */
    String defaultName();

    /** The METHOD column of the steps list: {@code GET}, {@code POST}, {@code SQL}... */
    String label();

    /** The step as written, its variables not rendered: shown while one of them is not known yet. */
    String summary();

    /** The title of the request in the detail panel. */
    default String requestTitle() {
        return "REQUEST";
    }

    /** What the detail panel says while the step runs. */
    default String waiting() {
        return "waiting for the response…";
    }

    /** The step's settings, shown under its request (and by {@code --dry-run}). */
    default List<Detail> details() {
        return List.of();
    }

    /** Whether the step calls one of the environment's APIs ({@link #api()}). */
    default boolean callsApi() {
        return false;
    }

    /** The API the step calls, as named on the step: null for the environment's only API. */
    default String api() {
        return null;
    }

    /** Whether the step sends the token of its API. */
    default boolean sendsToken() {
        return false;
    }

    /** Whether the step queries the database named by the step's {@code database}. */
    default boolean usesDatabase() {
        return false;
    }

    /**
     * The request with every variable rendered, as the screen and the log show it.
     *
     * @throws com.anorys.chainrunner.core.Template.UnknownVariableException when a variable is not known yet
     * @throws IllegalArgumentException                                      when it cannot be rendered
     */
    Request request(StepContext context, Call call, Map<String, Object> vars);

    /** Runs the step: {@code request} is what {@link #request} rendered, {@code vars} the variables it saw. */
    CallResult run(StepContext context, Call call, Request request, Map<String, Object> vars) throws InterruptedException;

    /** One setting of a step on the screen: {@code every 0:10, at most 15:00}, {@code api orders}. */
    record Detail(String label, String value, Kind kind) {

        public Detail(String label, String value) {
            this(label, value, Kind.TEXT);
        }

        public enum Kind {
            TEXT, API, DATABASE
        }
    }
}
