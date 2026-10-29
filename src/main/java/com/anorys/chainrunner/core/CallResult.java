package com.anorys.chainrunner.core;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * What happened to one step: the request as sent, and the response or the error. {@code notes} are
 * short lines shown under the response (a poll's attempts, the file a download wrote); {@code halt}
 * says that this result stops the sequence.
 */
public record CallResult(Status status, Request request, Integer httpStatus, Duration duration, String responseBody,
                         String error, Map<String, Object> extracted, List<String> notes, boolean halt) {

    public enum Status {
        PENDING, RUNNING, OK, FAILED, SKIPPED,
        /** Not made because its {@code skipIf} / {@code onlyIf} said so: not a failure. */
        UNNEEDED
    }

    public static CallResult pending() {
        return new CallResult(Status.PENDING, null, null, null, null, null, Map.of(), List.of(), false);
    }

    public static CallResult running(Request request) {
        return running(request, null, List.of());
    }

    /** A step still running, with how long it has run and what it has done so far (a poll's attempts). */
    public static CallResult running(Request request, Duration elapsed, List<String> notes) {
        return new CallResult(Status.RUNNING, request, null, elapsed, null, null, Map.of(), List.copyOf(notes), false);
    }

    public static CallResult skipped(String why) {
        return new CallResult(Status.SKIPPED, null, null, null, null, why, Map.of(), List.of(), false);
    }

    public boolean ok() {
        return status == Status.OK;
    }

    /** OK, or not needed: what does not make a run fail. */
    public boolean fine() {
        return status == Status.OK || status == Status.UNNEEDED;
    }

    /**
     * The request with its variables rendered. Headers hold no token: it is added when sending. For a
     * {@code poll} step, the method is {@code SQL}, the URL the JDBC URL, the body the query with its
     * {@code ?} and {@code params} their values.
     */
    public record Request(String method, String url, Map<String, String> headers, String body, List<Object> params) {

        public Request(String method, String url, Map<String, String> headers, String body) {
            this(method, url, headers, body, List.of());
        }

        public boolean sql() {
            return "SQL".equals(method);
        }
    }
}
