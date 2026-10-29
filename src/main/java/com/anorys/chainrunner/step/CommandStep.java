package com.anorys.chainrunner.step;

import com.anorys.chainrunner.config.ChainRunnerConfig.Call;
import com.anorys.chainrunner.config.StepFields;
import com.anorys.chainrunner.core.CallResult;
import com.anorys.chainrunner.core.CallResult.Request;
import com.anorys.chainrunner.core.CallResult.Status;
import com.anorys.chainrunner.core.Runner;
import com.anorys.chainrunner.core.Template;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs a program: the way to add what chainrunner does not do itself, in any language. {@code run} is a
 * list (the program and its arguments, no shell) or a text (run by {@code sh -c}, {@code cmd /c} on
 * Windows). {@code input} is written to its standard input (YAML as JSON, or a text). {@code extract} reads
 * {@code stdout} (the whole output), {@code stderr}, {@code exitCode}, or a JSON pointer of the output.
 */
public record CommandStep(List<String> args, String shell, String dir, Map<String, String> env, JsonNode input,
                          Duration timeout, List<Integer> expectExit, boolean stopOnError) implements Step {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TICK_MILLIS = 100;

    /** {@code type: command}. */
    public static final class Kind implements StepKind {

        @Override
        public String type() {
            return "command";
        }

        @Override
        public Step parse(StepFields f) {
            JsonNode run = f.node("run");
            List<String> args = null;
            String shell = null;
            if (run != null && run.isArray() && !run.isEmpty()) {
                args = new ArrayList<>();
                for (JsonNode arg : run) {
                    if (!arg.isValueNode()) {
                        throw f.error("run: each argument must be a value");
                    }
                    args.add(arg.asText());
                }
                args = List.copyOf(args);
            } else if (run != null && run.isValueNode() && !run.asText().isBlank()) {
                shell = run.asText().strip();
            } else {
                throw f.error("run is required for a command step: [program, arg, ...] or a shell command line");
            }
            List<Integer> expect = new ArrayList<>();
            JsonNode expectNode = f.node("expectExit");
            if (expectNode != null && expectNode.isArray()) {
                expectNode.forEach(n -> expect.add(n.asInt()));
            } else if (expectNode != null && expectNode.isNumber()) {
                expect.add(expectNode.asInt());
            }
            return new CommandStep(args, shell, f.text("dir", null), f.strings("env"), f.node("input"),
                    f.duration("timeout", null), expect.isEmpty() ? List.of(0) : List.copyOf(expect), f.bool("stopOnError", false));
        }
    }

    @Override
    public String defaultName() {
        return args != null ? args.get(0) : shell.split("\\s+", 2)[0];
    }

    @Override
    public String label() {
        return "RUN";
    }

    @Override
    public String summary() {
        return "RUN " + (args != null ? String.join(" ", args) : shell);
    }

    @Override
    public String requestTitle() {
        return "COMMAND";
    }

    @Override
    public String waiting() {
        return "waiting for the program to end…";
    }

    @Override
    public List<Detail> details() {
        List<Detail> details = new ArrayList<>();
        if (dir != null) {
            details.add(new Detail("in", dir));
        }
        if (timeout != null) {
            details.add(new Detail("at most", Runner.clock(timeout)));
        }
        if (!expectExit.equals(List.of(0))) {
            details.add(new Detail("exit codes", expectExit.toString()));
        }
        return details;
    }

    @Override
    public String outcome(CallResult result) {
        for (String note : result.notes()) {
            if (note.startsWith("exit code ")) {
                return "exit " + note.substring(10);
            }
        }
        return null;
    }

    /** {@code RUN}, the command line, the environment variables it adds as headers, and its input. */
    @Override
    public Request request(StepContext context, Call call, Map<String, Object> vars) {
        Map<String, String> added = new LinkedHashMap<>();
        env.forEach((k, v) -> added.put(k, Template.text(v, vars)));
        return new Request("RUN", line(command(vars)), Map.copyOf(added), input(vars));
    }

    @Override
    public CallResult run(StepContext context, Call call, Request request, Map<String, Object> vars) throws InterruptedException {
        long start = System.nanoTime();
        List<String> command = command(vars);
        Path base = context.options().baseDir();
        Path workDir = dir == null ? base : base.resolve(Template.text(dir, vars)).normalize();
        if (!Files.isDirectory(workDir)) {
            return CallResult.failed(request, null, Runner.since(start), null, "no such directory: " + workDir, List.of(), stopOnError);
        }
        ProcessBuilder builder = new ProcessBuilder(command).directory(workDir.toFile());
        builder.environment().putAll(request.headers());
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return CallResult.failed(request, null, Runner.since(start), null, "cannot run " + command.get(0) + ": " + e.getMessage(),
                    List.of(), stopOnError);
        }
        Reader out = new Reader(process.getInputStream());
        Reader err = new Reader(process.getErrorStream());
        try (OutputStream stdin = process.getOutputStream()) {
            if (request.body() != null) {
                stdin.write(request.body().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // The program does not read its input: what it prints says the rest.
        }
        Duration limit = timeout != null ? timeout : context.options().timeout();
        long deadline = start + limit.toNanos();
        while (!process.waitFor(TICK_MILLIS, TimeUnit.MILLISECONDS)) {
            if (context.cancelled()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return CallResult.failed(request, null, Runner.since(start), shown(context, out.text(), err.text()),
                        "stopped by the user", List.of(), true);
            }
            if (System.nanoTime() >= deadline) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return CallResult.failed(request, null, Runner.since(start), shown(context, out.text(), err.text()),
                        "still running after " + Runner.clock(limit) + ": stopped", List.of(), stopOnError);
            }
        }
        int exit = process.exitValue();
        String stdout = out.text();
        String stderr = err.text();
        Duration took = Runner.since(start);
        String shown = shown(context, stdout, stderr);
        List<String> notes = List.of("exit code " + exit);
        if (!expectExit.contains(exit)) {
            return CallResult.failed(request, null, took, shown, "exit code " + exit
                    + (expectExit.equals(List.of(0)) ? " (0 expected)" : " (expected " + expectExit + ")"), notes, stopOnError);
        }
        Map<String, Object> extracted = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : call.extract().entrySet()) {
            Object value = switch (e.getValue().strip()) {
                case "stdout" -> stdout.strip();
                case "stderr" -> stderr.strip();
                case "exitCode" -> (long) exit;
                default -> Json.value(stdout, e.getValue());
            };
            if (value == null) {
                return CallResult.failed(request, null, took, shown,
                        "extract " + e.getKey() + ": " + e.getValue() + " is not in the output", notes, stopOnError);
            }
            extracted.put(e.getKey(), value);
        }
        return new CallResult(Status.OK, request, null, took, shown, null, Map.copyOf(extracted), notes, false);
    }

    /** The program and its arguments, rendered; a text through the system's shell. */
    private List<String> command(Map<String, Object> vars) {
        if (args != null) {
            return args.stream().map(a -> Template.text(a, vars)).toList();
        }
        String line = Template.text(shell, vars);
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
                ? List.of("cmd", "/c", line) : List.of("sh", "-c", line);
    }

    private String input(Map<String, Object> vars) {
        if (input == null) {
            return null;
        }
        if (input.isTextual()) {
            return Template.text(input.asText(), vars);
        }
        try {
            return JSON.writeValueAsString(Template.json(input, vars));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid JSON input: " + e.getOriginalMessage(), e);
        }
    }

    /** The command line as shown: arguments with spaces quoted. */
    private static String line(List<String> command) {
        List<String> shown = new ArrayList<>();
        for (String arg : command) {
            shown.add(arg.isEmpty() || arg.matches(".*[\\s\"'].*") ? "\"" + arg.replace("\"", "\\\"") + "\"" : arg);
        }
        return String.join(" ", shown);
    }

    private static String shown(StepContext context, String stdout, String stderr) {
        String shown = context.display(stdout.stripTrailing(), null);
        return stderr.isBlank() ? shown : (shown.isEmpty() ? "" : shown + "\n") + "--- stderr ---\n" + stderr.strip();
    }

    /** Reads a stream of the program on its own thread, so that a full pipe never blocks it. */
    private static final class Reader {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final Thread thread;

        Reader(InputStream in) {
            thread = new Thread(() -> {
                try (in) {
                    in.transferTo(bytes);
                } catch (IOException e) {
                    // The program ended or was stopped: what was read is kept.
                }
            }, "chainrunner-command");
            thread.setDaemon(true);
            thread.start();
        }

        String text() throws InterruptedException {
            thread.join(1000);
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }
}
