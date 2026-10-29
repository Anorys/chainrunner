package com.anorys.chainrunner.cli;

import com.anorys.chainrunner.Main;
import com.anorys.chainrunner.config.ChainRunnerConfig;
import com.anorys.chainrunner.config.ChainRunnerConfig.Environment;
import com.anorys.chainrunner.config.ChainRunnerConfig.Sequence;
import com.anorys.chainrunner.core.CallResult;
import com.anorys.chainrunner.core.CallResult.Request;
import com.anorys.chainrunner.core.Database;
import com.anorys.chainrunner.core.Http;
import com.anorys.chainrunner.core.Planner;
import com.anorys.chainrunner.core.Planner.PlannedCall;
import com.anorys.chainrunner.core.RunLog;
import com.anorys.chainrunner.core.Runner;
import com.anorys.chainrunner.core.Template.UnknownVariableException;
import com.anorys.chainrunner.core.TokenClient;
import com.anorys.chainrunner.core.TokenClients;

import java.io.Console;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** {@code chainrunner --run}: the calls without the UI, one line per call, for scripts and CI. */
public final class Batch {

    private final ChainRunnerConfig config;
    private final PrintStream out;
    private final PrintStream err;
    private final Ansi ansi;
    private final Database.Opener opener;
    /** Whether a progress line is on screen, to be overwritten by the next line. */
    private boolean progressShown;

    public Batch(ChainRunnerConfig config, PrintStream out, PrintStream err) {
        this(config, out, err, Database::postgres);
    }

    public Batch(ChainRunnerConfig config, PrintStream out, PrintStream err, Database.Opener opener) {
        this.config = config;
        this.opener = opener;
        this.out = out;
        this.err = err;
        this.ansi = new Ansi(System.console() != null && System.getenv("NO_COLOR") == null, unicode(out));
    }

    public static int list(ChainRunnerConfig config, PrintStream out) {
        out.println("Environments:");
        for (Environment env : config.environments().values()) {
            out.println("  " + env.name() + (env.name().equals(config.defaultEnvironment()) ? " (default)" : "")
                    + (env.protectedEnv() ? " [protected]" : "") + "  apis: " + String.join(", ", env.apis().keySet())
                    + (env.apis().isEmpty() ? "" : "  (" + env.hosts() + ")")
                    + (env.databases().isEmpty() ? "" : "  databases: " + String.join(", ", env.databases().keySet())));
        }
        out.println("Sequences:");
        for (Sequence s : config.sequences()) {
            out.println("  " + s.name() + "  " + s.calls().size() + " step(s)"
                    + (s.description() == null ? "" : "  - " + s.description()));
        }
        return Main.OK;
    }

    public int run(String envName, String sequenceName, Map<String, Object> overrides, boolean dryRun, boolean yes) {
        Environment env = config.environment(envName != null ? envName : config.defaultEnvironment()).orElseThrow();
        Sequence sequence = sequenceName != null ? config.sequence(sequenceName).orElseThrow() : config.sequences().get(0);
        Map<String, Object> vars = Planner.variables(config, env, overrides);
        List<PlannedCall> calls;
        try {
            calls = Planner.plan(sequence, vars);
        } catch (UnknownVariableException e) {
            err.println("chainrunner: " + e.getMessage() + " (define it in 'variables' or with --var " + e.name() + "=...)");
            return Main.USAGE;
        }
        if (calls.isEmpty()) {
            out.println("Nothing to run.");
            return Main.OK;
        }

        out.println(ansi.accent(ansi.glyph("◆", "*") + " ") + ansi.bold("chainrunner") + "  " + sequence.name() + " on "
                + (env.protectedEnv() ? ansi.red(env.title()) : ansi.accent(env.title())) + "  " + ansi.muted(env.hosts()));
        HttpClient http = Http.client(config.options().timeout(), config.options().insecureTls());
        TokenClients tokens = new TokenClients(http, env, config.options().timeout(), Clock.systemUTC());
        Runner preview = new Runner(http, tokens, env, config.options(), opener, Function.identity());

        if (dryRun) {
            for (PlannedCall call : calls) {
                Map<String, Object> scope = new java.util.LinkedHashMap<>(vars);
                scope.putAll(call.loop());
                try {
                    Request r = preview.request(call.call(), scope);
                    out.println(ansi.muted(String.format(Locale.ROOT, "%3d. ", call.index() + 1)) + call.name());
                    var guard = call.call().guard();
                    if (guard != null) {
                        Request g = preview.guardRequest(call.call(), scope);
                        String sql = new Database.Statement(g.body(), g.params()).shown();
                        out.println("     " + ansi.amber(guard.keyword()) + " " + ansi.muted(sql.replace("\n", "\n     ")));
                    }
                    out.println("     " + ansi.accent(r.method()) + " " + r.url());
                    if (r.sql()) {
                        String sql = new Database.Statement(r.body(), r.params()).shown();
                        out.println("     " + ansi.muted(sql.replace("\n", "\n     ")));
                        var poll = call.call().poll();
                        out.println("     " + ansi.muted("every " + Runner.clock(poll.every()) + ", at most " + Runner.clock(poll.timeout())
                                + (call.call().extract().isEmpty() ? "" : "  extract " + call.call().extract())));
                    } else if (r.body() != null) {
                        out.println("     " + ansi.muted(r.body()));
                    }
                } catch (UnknownVariableException e) {
                    out.println(ansi.muted(String.format(Locale.ROOT, "%3d. ", call.index() + 1)) + call.name()
                            + "  " + ansi.amber("(" + e.getMessage() + ", known after an earlier step?)"));
                } catch (IllegalArgumentException e) {
                    out.println(ansi.muted(String.format(Locale.ROOT, "%3d. ", call.index() + 1)) + call.name()
                            + "  " + ansi.red(e.getMessage()));
                }
            }
            out.println(ansi.muted(calls.size() + " step(s), nothing was sent (--dry-run)."));
            return Main.OK;
        }

        for (PlannedCall call : calls) {
            out.println(ansi.muted(String.format(Locale.ROOT, "%3d. ", call.index() + 1)) + call.name());
        }
        Console console = System.console();
        if (!yes || env.protectedEnv() && console != null) {
            if (console == null) {
                err.println("chainrunner: cannot ask for confirmation without a terminal; add --yes to run.");
                return Main.USAGE;
            }
            String question = env.protectedEnv()
                    ? "Protected environment: type '" + env.name() + "' to run " + calls.size() + " step(s): "
                    : "Run " + calls.size() + " step(s) on " + env.title() + "? [y/N] ";
            String answer = console.readLine("%s", question);
            boolean confirmed = answer != null && (env.protectedEnv() ? answer.strip().equals(env.name())
                    : answer.strip().toLowerCase(Locale.ROOT).matches("y|yes"));
            if (!confirmed) {
                out.println("Cancelled.");
                return Main.CALLS_FAILED;
            }
        }
        for (TokenClient client : tokens.forCalls(calls)) {
            if (!client.auth().needsPassword()) {
                continue;
            }
            if (console == null) {
                err.println("chainrunner: missing password for " + client.auth().username() + " (" + client.auth().tokenUrl()
                        + "); set it in .env or run from a terminal.");
                return Main.USAGE;
            }
            char[] password = console.readPassword("Password of %s (%s): ", client.auth().username(), client.auth().tokenUrl());
            if (password == null || password.length == 0) {
                out.println("Cancelled.");
                return Main.CALLS_FAILED;
            }
            client.password(new String(password));
        }
        Map<String, ChainRunnerConfig.Database> databases = new LinkedHashMap<>();
        try {
            for (ChainRunnerConfig.Database database : Planner.databases(env, calls)) {
                databases.put(database.name(), database);
            }
        } catch (IllegalArgumentException e) {
            err.println("chainrunner: " + e.getMessage());
            return Main.USAGE;
        }
        for (ChainRunnerConfig.Database database : List.copyOf(databases.values())) {
            if (!database.needsPassword()) {
                continue;
            }
            if (console == null) {
                err.println("chainrunner: missing password for database " + database.name() + " (" + database.username()
                        + "); set it in .env or run from a terminal.");
                return Main.USAGE;
            }
            char[] password = console.readPassword("Password of database %s (%s): ", database.name(), database.username());
            if (password == null || password.length == 0) {
                out.println("Cancelled.");
                return Main.CALLS_FAILED;
            }
            databases.put(database.name(), database.withPassword(new String(password)));
        }
        Runner runner = new Runner(http, tokens, env, config.options(), opener,
                database -> databases.getOrDefault(database.name(), database));

        RunLog log = RunLog.open(config.options().logDir(), env.name(), sequence.name(), calls.size());
        Runner.Listener listener = new Runner.Listener() {
            @Override
            public void started(PlannedCall call, Request request) {
                if (log != null) {
                    log.started(call, request);
                }
            }

            @Override
            public void progress(PlannedCall call, CallResult running) {
                if (log != null) {
                    log.progress(call, running);
                }
                if (console != null) {
                    String n = String.format(Locale.ROOT, "%3d. ", call.index() + 1);
                    out.print("\r" + ansi.amber(ansi.glyph("◷", "~") + " ") + ansi.muted(n) + call.name() + "  "
                            + ansi.muted(String.join("  ", running.notes())) + (ansi.on() ? "\033[K" : "    "));
                    out.flush();
                    progressShown = true;
                }
            }

            @Override
            public void finished(PlannedCall call, CallResult result) {
                if (log != null) {
                    log.finished(call, result);
                }
                if (progressShown) {
                    out.print("\r" + (ansi.on() ? "\033[K" : ""));
                    progressShown = false;
                }
                print(call, result);
            }
        };
        out.println();
        List<CallResult> results;
        try {
            results = runner.run(calls, vars, listener, () -> false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Main.CALLS_FAILED;
        } finally {
            if (log != null) {
                log.close();
            }
        }
        long ok = results.stream().filter(CallResult::ok).count();
        long unneeded = results.stream().filter(r -> r.status() == CallResult.Status.UNNEEDED).count();
        long failed = results.stream().filter(r -> r.status() == CallResult.Status.FAILED).count();
        long skipped = results.size() - ok - unneeded - failed;
        out.println();
        out.println((failed == 0 && skipped == 0 ? ansi.green(ansi.glyph("✔", "+") + " ") : ansi.red(ansi.glyph("✘", "x") + " ")) + ok + " OK, " + (unneeded == 0 ? "" : unneeded + " not needed, ") + failed + " failed, "
                + skipped + " not run" + (log == null ? "" : ansi.muted("  log: " + log.file())));
        return failed == 0 && skipped == 0 ? Main.OK : Main.CALLS_FAILED;
    }

    private void print(PlannedCall call, CallResult r) {
        String n = String.format(Locale.ROOT, "%3d. ", call.index() + 1);
        switch (r.status()) {
            case OK -> {
                out.println(ansi.green(ansi.glyph("✔", "+") + " ") + ansi.muted(n) + call.name() + "  "
                        + ansi.green(r.httpStatus() == null ? "found" : String.valueOf(r.httpStatus()))
                        + ansi.muted("  " + took(r)) + (r.extracted().isEmpty() ? "" : ansi.muted("  " + r.extracted())));
                notes(r);
            }
            case FAILED -> {
                out.println(ansi.red(ansi.glyph("✘", "x") + " ") + ansi.muted(n) + call.name() + "  " + ansi.red(r.error().replace("\n", "\n       "))
                        + (r.duration() == null ? "" : ansi.muted("  " + took(r))));
                notes(r);
                if (r.responseBody() != null && !r.responseBody().isEmpty()) {
                    String body = r.responseBody().length() > 2000 ? r.responseBody().substring(0, 2000) + "…" : r.responseBody();
                    out.println(ansi.muted("       " + body.replace("\n", "\n       ")));
                }
            }
            case UNNEEDED -> out.println(ansi.muted(ansi.glyph("○", "-") + " " + n + call.name() + "  " + r.error()
                    + "  " + took(r)));
            default -> out.println(ansi.muted(ansi.glyph("◌", ".") + " " + n + call.name() + "  " + r.error()));
        }
    }

    private void notes(CallResult r) {
        for (String note : r.notes()) {
            out.println(ansi.muted("       " + note));
        }
    }

    /** {@code 312 ms}, or {@code 1:05} for a poll that waited. */
    private static String took(CallResult r) {
        long ms = r.duration().toMillis();
        return ms < 10_000 ? ms + " ms" : Runner.clock(r.duration());
    }

    /** Whether the output can show the symbols: not on a console with an old code page, nor with CHAINRUNNER_ASCII=1. */
    private static boolean unicode(PrintStream out) {
        String ascii = System.getenv("CHAINRUNNER_ASCII");
        if (ascii != null && !ascii.isEmpty() && !ascii.equals("0")) {
            return false;
        }
        return out.charset().newEncoder().canEncode("✔◆✘◌◷○");
    }

    /** The Anorys state colors as 24-bit escapes, or nothing. */
    private record Ansi(boolean on, boolean unicode) {

        String glyph(String symbol, String ascii) {
            return unicode ? symbol : ascii;
        }

        String color(String hex, String s) {
            if (!on) {
                return s;
            }
            int rgb = Integer.parseInt(hex, 16);
            return "\033[38;2;" + (rgb >> 16 & 255) + ";" + (rgb >> 8 & 255) + ";" + (rgb & 255) + "m" + s + "\033[0m";
        }

        String bold(String s) {
            return on ? "\033[1m" + s + "\033[0m" : s;
        }

        String accent(String s) {
            return color("4FC1B1", s);
        }

        String green(String s) {
            return color("5FD39A", s);
        }

        String red(String s) {
            return color("F07178", s);
        }

        String amber(String s) {
            return color("F0A766", s);
        }

        String muted(String s) {
            return color("6E7875", s);
        }
    }
}
