package com.anorys.chainrunner.core;

import com.anorys.chainrunner.core.CallResult.Request;
import com.anorys.chainrunner.core.Planner.PlannedCall;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * A trace of each run in {@code logs/}: every request as sent (without the token) and every response,
 * so that what was called on which environment can be checked afterwards.
 */
public final class RunLog implements Runner.Listener, AutoCloseable {

    private static final DateTimeFormatter FILE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter LINE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path file;
    private final BufferedWriter out;

    private RunLog(Path file, BufferedWriter out) {
        this.file = file;
        this.out = out;
    }

    /** Opens a new log in {@code dir}, or none when {@code dir} is null. */
    public static RunLog open(Path dir, String env, String sequence, int calls) {
        if (dir == null) {
            return null;
        }
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("chainrunner-" + LocalDateTime.now().format(FILE) + "-" + safe(env) + "-" + safe(sequence) + ".log");
            BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            RunLog log = new RunLog(file, out);
            log.line("# " + LocalDateTime.now().format(LINE) + "  environment=" + env + "  sequence=" + sequence + "  steps=" + calls);
            return log;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the log in " + dir + ": " + e.getMessage(), e);
        }
    }

    public Path file() {
        return file;
    }

    @Override
    public synchronized void started(PlannedCall call, Request request) {
        line("");
        line("[" + (call.index() + 1) + "] " + call.name() + "  (" + LocalDateTime.now().format(LINE) + ")");
        line("> " + request.method() + " " + request.url());
        if (request.sql()) {
            line("> " + request.body().replace("\n", "\n> "));
            if (!request.params().isEmpty()) {
                line("> parameters: " + request.params());
            }
            flush();
            return;
        }
        for (Map.Entry<String, String> h : request.headers().entrySet()) {
            boolean secret = h.getKey().toLowerCase(java.util.Locale.ROOT).matches(".*(auth|key|secret|token|password).*");
            line("> " + h.getKey() + ": " + (secret ? "***" : h.getValue()));
        }
        if (request.body() != null) {
            line("> " + request.body().replace("\n", "\n> "));
        }
    }

    @Override
    public synchronized void progress(PlannedCall call, CallResult running) {
        line("~ " + LocalDateTime.now().format(LINE) + "  " + String.join("  ", running.notes()) + "  no row");
        flush();
    }

    @Override
    public synchronized void finished(PlannedCall call, CallResult result) {
        if (result.status() == CallResult.Status.SKIPPED) {
            line("");
            line("[" + (call.index() + 1) + "] " + call.name() + "  SKIPPED: " + result.error());
            return;
        }
        line("< " + (result.httpStatus() != null ? result.httpStatus() : result.request() != null && result.request().sql() ? "SQL" : "-")
                + (result.duration() == null ? "" : "  " + result.duration().toMillis() + " ms")
                + "  " + result.status() + (result.error() == null ? "" : ": " + result.error()));
        if (result.responseBody() != null && !result.responseBody().isEmpty()) {
            line("< " + result.responseBody().replace("\n", "\n< "));
        }
        for (String note : result.notes()) {
            line("  " + note);
        }
        if (!result.extracted().isEmpty()) {
            line("= " + result.extracted());
        }
        flush();
    }

    private void line(String text) {
        try {
            out.write(text);
            out.newLine();
        } catch (IOException e) {
            // The log is a convenience: a full disk must not stop the calls.
        }
    }

    private void flush() {
        try {
            out.flush();
        } catch (IOException e) {
            // As above.
        }
    }

    @Override
    public synchronized void close() {
        try {
            out.close();
        } catch (IOException e) {
            // As above.
        }
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
