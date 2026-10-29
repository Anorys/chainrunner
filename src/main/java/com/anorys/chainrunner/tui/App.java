package com.anorys.chainrunner.tui;

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
import com.anorys.chainrunner.core.Template;
import com.anorys.chainrunner.core.Template.UnknownVariableException;
import com.anorys.chainrunner.core.TokenClient;
import com.anorys.chainrunner.core.TokenClients;
import com.anorys.chainrunner.tui.AppState.Dialog;
import com.anorys.chainrunner.tui.AppState.DialogLine;
import com.anorys.chainrunner.tui.AppState.Input;
import com.anorys.chainrunner.tui.AppState.Menu;
import com.anorys.chainrunner.tui.AppState.TokenPhase;
import com.anorys.chainrunner.tui.AppState.Toast;
import com.anorys.chainrunner.tui.theme.Theme;
import com.anorys.chainrunner.tui.theme.Tone;
import com.anorys.chainrunner.tui.view.MainView;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.PasteEvent;
import dev.tamboui.tui.event.TickEvent;
import org.jline.terminal.Terminal;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The terminal UI: the event loop, the keys, and the background run that feeds the state. The state
 * is only touched on the render thread; the run hands its results back with {@link TuiRunner#runLater}.
 */
public final class App {

    private static final Duration TICK = Duration.ofMillis(80);
    private static final Duration TOAST = Duration.ofSeconds(6);

    private final AppState state;
    private final MainView view;
    private final HttpClient http;
    private final Clock clock;
    /** One token per environment, kept while the UI is open. */
    private final Map<String, TokenClients> tokens = new HashMap<>();
    /** Database passwords typed in the UI, by {@code environment/database}, kept while the UI is open. */
    private final Map<String, String> dbPasswords = new HashMap<>();
    /** How the poll steps open the database; tests put a fake one. */
    Database.Opener opener = Database::postgres;
    // Platform threads: in the Windows native image, virtual threads crash.
    private final ExecutorService background = Executors.newCachedThreadPool(
            Thread.ofPlatform().name("chainrunner-run-", 1).daemon().factory());
    private final AtomicBoolean cancel = new AtomicBoolean();

    private final boolean synchronous;
    private TuiRunner runner;
    private boolean dirty = true;

    public App(ChainRunnerConfig config, Theme theme, String envName, String sequenceName, Map<String, Object> overrides) {
        this(config, theme, envName, sequenceName, overrides, Http.client(config.options().timeout(), config.options().insecureTls()),
                Clock.systemUTC(), false);
    }

    /** With {@code synchronous}, background work runs on the calling thread: for tests, with no terminal. */
    App(ChainRunnerConfig config, Theme theme, String envName, String sequenceName, Map<String, Object> overrides,
        HttpClient http, Clock clock, boolean synchronous) {
        Environment env = config.environment(envName != null ? envName : config.defaultEnvironment()).orElseThrow();
        int sequence = 0;
        if (sequenceName != null) {
            for (int i = 0; i < config.sequences().size(); i++) {
                if (config.sequences().get(i).name().equals(sequenceName)) {
                    sequence = i;
                }
            }
        }
        this.state = new AppState(config, env, sequence);
        this.state.overrides().putAll(overrides);
        this.view = new MainView(theme);
        this.http = http;
        this.clock = clock;
        this.synchronous = synchronous;
        replan();
    }

    AppState state() {
        return state;
    }

    /** Shows {@code text} when the UI opens, a little longer than other notices: a newer release, say. */
    public void notice(String text) {
        state.toast(new Toast(Tone.INFO, text, clock.instant().plus(TOAST.multipliedBy(2))));
    }

    /** Runs until the user quits. */
    public void run() throws Exception {
        // The backend is named rather than discovered, so that the native image needs no service lookup.
        // The terminal is opened here, not by TamboUI's backend, which would settle for a dumb terminal.
        try (Terminal terminal = SystemTerminal.open();
             TuiRunner tui = TuiRunner.create(TuiConfig.builder().backend(new ConsoleBackend(terminal)).tickRate(TICK).build())) {
            runner = tui;
            tui.setWindowTitle("chainrunner");
            tui.run(this::handle, frame -> view.render(frame, state, clock.instant()));
        } finally {
            cancel.set(true);
            background.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- planning

    /** The tokens of the current environment, one per API. */
    private TokenClients tokenClients() {
        Environment env = state.env();
        return tokens.computeIfAbsent(env.name(), n -> new TokenClients(http, env, state.config().options().timeout(), clock));
    }

    /** Shows in the header which API has a valid token. */
    private void refreshTokens() {
        state.clearTokens();
        tokenClients().all().forEach((api, client) -> {
            TokenClient.Token token = client.current();
            state.token(api, token != null && token.valid(clock.instant()) ? TokenPhase.VALID : TokenPhase.NONE, token, null);
        });
    }

    /** Recomputes the calls after a change of environment, sequence or variables. */
    private void replan() {
        Map<String, Object> vars = Planner.variables(state.config(), state.env(), state.overrides());
        try {
            state.plan(vars, Planner.plan(state.sequence(), vars), null);
        } catch (UnknownVariableException e) {
            state.plan(vars, List.of(), e.getMessage() + ": set it with v (variables)");
        }
        refreshTokens();
        dirty = true;
    }

    // ---------------------------------------------------------------- run

    /** Asks for confirmation, then runs the calls from {@code from} to {@code to} (exclusive). */
    private void confirmRun(int from, int to) {
        if (state.running()) {
            toast(Tone.INFO, "A run is already going on (x to stop it)");
            return;
        }
        if (state.planError() != null) {
            toast(Tone.ERROR, state.planError());
            return;
        }
        List<PlannedCall> calls = state.calls().subList(from, to);
        if (calls.isEmpty()) {
            return;
        }
        Environment env = state.env();
        List<DialogLine> lines = new ArrayList<>();
        lines.add(new DialogLine(null, env.title() + "  " + env.hosts()));
        lines.add(new DialogLine(null, ""));
        int shown = Math.min(8, calls.size());
        for (int i = 0; i < shown; i++) {
            PlannedCall c = calls.get(i);
            lines.add(new DialogLine(Tone.IDLE, String.format(Locale.ROOT, "%3d. %-6s %s", c.index() + 1, c.call().method(), c.name())));
        }
        if (calls.size() > shown) {
            lines.add(new DialogLine(Tone.IDLE, "     … and " + (calls.size() - shown) + " more"));
        }
        String what = calls.size() == 1 ? "1 step" : calls.size() + " steps";
        List<ChainRunnerConfig.Database> databases;
        try {
            databases = Planner.databases(env, calls);
        } catch (IllegalArgumentException e) {
            toast(Tone.ERROR, e.getMessage());
            return;
        }
        Runnable start = () -> withPasswords(tokenClients().forCalls(calls), () -> withDbPasswords(databases, () -> start(calls)));
        if (env.protectedEnv()) {
            lines.add(new DialogLine(null, ""));
            lines.add(new DialogLine(Tone.ERROR, "Protected environment: type '" + env.name() + "' to confirm."));
            state.input(new Input("Run " + what + "?", lines, "confirm", "", false, typed -> {
                state.input(null);
                if (typed.strip().equals(env.name())) {
                    start.run();
                } else {
                    toast(Tone.WARN, "Cancelled: '" + typed.strip() + "' is not '" + env.name() + "'");
                }
            }));
        } else {
            state.dialog(new Dialog("Run " + what + "?", lines, "run", start));
        }
        dirty = true;
    }

    /** Asks in turn for the passwords these tokens lack, then runs {@code next}. */
    private void withPasswords(List<TokenClient> clients, Runnable next) {
        TokenClient client = clients.stream().filter(c -> c.auth().needsPassword()).findFirst().orElse(null);
        if (client == null) {
            next.run();
            return;
        }
        List<TokenClient> rest = clients.subList(clients.indexOf(client) + 1, clients.size());
        state.input(new Input("Password", List.of(
                new DialogLine(null, "Token of " + state.env().title() + ": " + client.auth().tokenUrl()),
                new DialogLine(Tone.IDLE, "No password in the configuration; it is kept in memory until the UI closes.")),
                client.auth().username(), "", true, password -> {
            state.input(null);
            if (password.isEmpty()) {
                toast(Tone.WARN, "Cancelled: empty password");
                return;
            }
            client.password(password);
            withPasswords(rest, next);
        }));
        dirty = true;
    }

    /** A database of the current environment, with the password typed for it if the configuration has none. */
    private ChainRunnerConfig.Database withTypedPassword(ChainRunnerConfig.Database db) {
        if (!db.needsPassword()) {
            return db;
        }
        String typed = dbPasswords.get(state.env().name() + "/" + db.name());
        return typed == null ? db : db.withPassword(typed);
    }

    /** Asks in turn for the passwords these databases lack, then runs {@code next}. */
    private void withDbPasswords(List<ChainRunnerConfig.Database> databases, Runnable next) {
        ChainRunnerConfig.Database db = databases.stream().filter(d -> withTypedPassword(d).needsPassword()).findFirst().orElse(null);
        if (db == null) {
            next.run();
            return;
        }
        List<ChainRunnerConfig.Database> rest = databases.subList(databases.indexOf(db) + 1, databases.size());
        String key = state.env().name() + "/" + db.name();
        state.input(new Input("Database password", List.of(
                new DialogLine(null, "Database " + db.name() + " of " + state.env().title() + ": " + db.url()),
                new DialogLine(Tone.IDLE, "No password in the configuration; it is kept in memory until the UI closes.")),
                db.username(), "", true, password -> {
            state.input(null);
            if (password.isEmpty()) {
                toast(Tone.WARN, "Cancelled: empty password");
                return;
            }
            dbPasswords.put(key, password);
            withDbPasswords(rest, next);
        }));
        dirty = true;
    }

    private void start(List<PlannedCall> calls) {
        Environment env = state.env();
        Sequence sequence = state.sequence();
        Map<String, Object> vars = state.variables();
        TokenClients clients = tokenClients();
        Runner run = new Runner(http, clients, env, state.config().options(), opener, this::withTypedPassword);
        for (PlannedCall c : calls) {
            state.result(c.index(), CallResult.pending());
        }
        state.select(calls.get(0).index());
        state.runStarted(calls.size(), clock.instant());
        cancel.set(false);
        dirty = true;
        work(() -> {
            RunLog log;
            try {
                log = RunLog.open(state.config().options().logDir(), env.name(), sequence.name(), calls.size());
            } catch (RuntimeException e) {
                log = null;
                ui(() -> toast(Tone.WARN, e.getMessage()));
            }
            RunLog runLog = log;
            Runner.Listener listener = new Runner.Listener() {
                @Override
                public void started(PlannedCall call, Request request) {
                    if (runLog != null) {
                        runLog.started(call, request);
                    }
                    ui(() -> {
                        state.result(call.index(), CallResult.running(request));
                        if (state.selected() < call.index()) {
                            state.select(call.index());
                        }
                    });
                }

                @Override
                public void progress(PlannedCall call, CallResult running) {
                    ui(() -> state.result(call.index(), running));
                }

                @Override
                public void finished(PlannedCall call, CallResult result) {
                    if (runLog != null) {
                        runLog.finished(call, result);
                    }
                    if (result.error() != null && result.error().startsWith("cannot connect to database ")) {
                        // A typed password the server refused is asked for again at the next run.
                        String db = result.error().substring("cannot connect to database ".length()).split(":", 2)[0];
                        dbPasswords.remove(env.name() + "/" + db);
                    }
                    String api;
                    try {
                        api = env.api(call.call().api()).name();
                    } catch (IllegalArgumentException e) {
                        api = null;
                    }
                    String callApi = api;
                    ui(() -> {
                        state.result(call.index(), result);
                        clients.all().forEach((name, c) -> {
                            TokenClient.Token token = c.current();
                            if (token != null && token.valid(clock.instant())) {
                                state.token(name, TokenPhase.VALID, token, null);
                            }
                        });
                        if (callApi != null && result.error() != null && result.error().startsWith("token refused")) {
                            state.token(callApi, TokenPhase.FAILED, null, result.error().lines().findFirst().orElse(""));
                            clients.forgetTypedPasswords();
                        }
                    });
                }
            };
            List<CallResult> results;
            try {
                results = run.run(calls, vars, listener, cancel::get);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                results = List.of();
            } finally {
                if (runLog != null) {
                    runLog.close();
                }
            }
            long ok = results.stream().filter(CallResult::fine).count();
            long failed = results.stream().filter(r -> r.status() == CallResult.Status.FAILED).count();
            long skipped = results.size() - ok - failed;
            ui(() -> {
                state.runFinished(runLog == null ? null : runLog.file());
                Duration took = Duration.between(state.runStartedAt(), clock.instant());
                if (failed == 0 && skipped == 0) {
                    toast(Tone.OK, ok + " step(s) OK in " + MainView.duration(took));
                } else {
                    toast(failed > 0 ? Tone.ERROR : Tone.WARN, ok + " OK, " + failed + " failed, " + skipped + " not run"
                            + "; details in the right panel");
                }
            });
        });
    }

    /** Asks every API of the environment for a token now, and shows what a refusal sent. */
    private void fetchToken() {
        TokenClients clients = tokenClients();
        Map<String, TokenClient> all = clients.all();
        if (all.isEmpty()) {
            toast(Tone.INFO, state.env().title() + " uses no token");
            return;
        }
        if (state.running()) {
            toast(Tone.INFO, "A run is going on");
            return;
        }
        withPasswords(List.copyOf(all.values()), () -> {
            all.forEach((api, client) -> {
                client.forget();
                state.token(api, TokenPhase.FETCHING, null, null);
            });
            dirty = true;
            work(() -> {
                List<DialogLine> refused = new ArrayList<>();
                for (Map.Entry<String, TokenClient> e : all.entrySet()) {
                    String api = e.getKey();
                    TokenClient client = e.getValue();
                    try {
                        client.accessToken();
                        TokenClient.Token token = client.current();
                        ui(() -> state.token(api, TokenPhase.VALID, token, null));
                    } catch (Exception ex) {
                        String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                        if (!refused.isEmpty()) {
                            refused.add(new DialogLine(null, ""));
                        }
                        refused.add(new DialogLine(Tone.ERROR, "API " + api + ": " + message.lines().findFirst().orElse(message)));
                        message.lines().skip(1).forEach(l -> refused.add(new DialogLine(null, l)));
                        ui(() -> state.token(api, TokenPhase.FAILED, null, message.lines().findFirst().orElse(message)));
                    }
                }
                ui(() -> {
                    if (refused.isEmpty()) {
                        toast(Tone.OK, all.size() == 1 ? "Token received" : all.size() + " tokens received");
                    } else {
                        clients.forgetTypedPasswords();
                        state.dialog(new Dialog("Token refused", refused, "close", () -> { }));
                    }
                });
            });
        });
    }

    // ---------------------------------------------------------------- menus

    private void chooseEnvironment() {
        if (blockedWhileRunning()) {
            return;
        }
        List<Menu.Item> items = new ArrayList<>();
        int current = 0;
        for (Environment env : state.config().environments().values()) {
            if (env.name().equals(state.env().name())) {
                current = items.size();
            }
            items.add(new Menu.Item(env.name(), env.title(), env.hosts(), env.protectedEnv() ? "protected" : null,
                    env.protectedEnv() ? Tone.ERROR : Tone.OK));
        }
        state.menu(new Menu("Environment", items, current, "choose", item -> {
            state.menu(null);
            state.env(state.config().environment(item.key()).orElseThrow());
            replan();
            toast(Tone.INFO, "Environment: " + state.env().title());
        }));
        dirty = true;
    }

    private void editVariables() {
        if (blockedWhileRunning()) {
            return;
        }
        Map<String, Object> vars = Planner.userVariables(state.config(), state.env(), state.overrides());
        if (vars.isEmpty()) {
            toast(Tone.INFO, "No variable in the configuration");
            return;
        }
        List<Menu.Item> items = new ArrayList<>();
        vars.forEach((k, v) -> items.add(new Menu.Item(k, k, Template.format(v),
                state.overrides().containsKey(k) ? "changed" : null, state.overrides().containsKey(k) ? Tone.INFO : Tone.IDLE)));
        state.menu(new Menu("Variables", items, 0, "edit", item -> {
            state.menu(null);
            Object old = vars.get(item.key());
            boolean list = old instanceof List<?>;
            state.input(new Input("Edit " + item.key(), List.of(
                    new DialogLine(Tone.IDLE, list ? "A list: values split by commas." : "Value for this session (the file is not changed)."),
                    new DialogLine(Tone.IDLE, "Clear it all then Enter restores the value of the configuration.")),
                    item.key(), Template.format(old), false, typed -> {
                state.input(null);
                if (typed.isBlank()) {
                    state.overrides().remove(item.key());
                } else {
                    state.overrides().put(item.key(), parse(typed.strip(), old));
                }
                replan();
                toast(Tone.OK, item.key() + " = " + Template.format(state.variables().get(item.key())) + "; "
                        + state.calls().size() + " step(s) planned");
            }));
            dirty = true;
        }));
        dirty = true;
    }

    /** A typed value, with the type of the value it replaces. */
    static Object parse(String typed, Object old) {
        if (old instanceof List<?>) {
            return List.copyOf(Planner.split(typed));
        }
        if (old instanceof Boolean && (typed.equalsIgnoreCase("true") || typed.equalsIgnoreCase("false"))) {
            return Boolean.parseBoolean(typed);
        }
        if (old instanceof Long) {
            try {
                return Long.parseLong(typed);
            } catch (NumberFormatException e) {
                return typed;
            }
        }
        return typed;
    }

    private boolean blockedWhileRunning() {
        if (state.running()) {
            toast(Tone.INFO, "Wait for the run to end (or x to stop it)");
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- events

    boolean handle(Event event, TuiRunner tui) {
        if (event instanceof TickEvent) {
            state.tick(state.tick() + 1);
            Toast toast = state.toast();
            if (toast != null && clock.instant().isAfter(toast.until())) {
                state.toast(null);
                dirty = true;
            }
            boolean redraw = dirty || state.animating() || state.tick() % 12 == 0;
            dirty = false;
            return redraw;
        }
        if (event instanceof PasteEvent paste && state.input() != null) {
            state.input().type(paste.text());
            return true;
        }
        if (event instanceof KeyEvent key) {
            key(key, tui);
            return true;
        }
        return false;
    }

    void key(KeyEvent key, TuiRunner tui) {
        if (key.isCtrlC()) {
            quit(tui);
            return;
        }
        Input input = state.input();
        if (input != null) {
            if (key.isKey(KeyCode.ENTER)) {
                input.submit();
            } else if (key.isKey(KeyCode.ESCAPE)) {
                state.input(null);
            } else if (key.isKey(KeyCode.BACKSPACE)) {
                input.backspace();
            } else if (key.hasCtrl() && key.isCharIgnoreCase('u')) {
                input.clear();
            } else if (key.isKey(KeyCode.CHAR) && !key.hasCtrl() && !key.hasAlt()) {
                input.type(key.string());
            }
            return;
        }
        Menu menu = state.menu();
        if (menu != null) {
            if (key.isKey(KeyCode.UP) || key.isChar('k')) {
                menu.move(-1);
            } else if (key.isKey(KeyCode.DOWN) || key.isChar('j')) {
                menu.move(1);
            } else if (key.isKey(KeyCode.ENTER)) {
                menu.pick();
            } else if (key.isKey(KeyCode.ESCAPE) || key.isChar('q')) {
                state.menu(null);
            }
            return;
        }
        Dialog dialog = state.dialog();
        if (dialog != null) {
            if (dialog.onConfirm() != null && (key.isCharIgnoreCase('y') || key.isKey(KeyCode.ENTER))) {
                state.dialog(null);
                dialog.onConfirm().run();
            } else if (key.isKey(KeyCode.ESCAPE) || key.isCharIgnoreCase('n') || key.isChar('q')
                    || dialog.onConfirm() == null && key.isKey(KeyCode.ENTER)) {
                state.dialog(null);
            }
            return;
        }
        if (state.help()) {
            state.help(false);
            if (key.isChar('?') || key.isKey(KeyCode.ESCAPE)) {
                return;
            }
        }
        int count = state.calls().size();
        int sequences = state.config().sequences().size();
        if (key.isKey(KeyCode.UP) || key.isChar('k')) {
            state.select(state.selected() - 1);
        } else if (key.isKey(KeyCode.DOWN) || key.isChar('j')) {
            state.select(state.selected() + 1);
        } else if (key.isKey(KeyCode.HOME) || key.isChar('g')) {
            state.select(0);
        } else if (key.isKey(KeyCode.END) || key.isChar('G')) {
            state.select(count - 1);
        } else if (key.isKey(KeyCode.PAGE_DOWN) || key.isChar('J')) {
            state.detailScroll(state.detailScroll() + 5);
        } else if (key.isKey(KeyCode.PAGE_UP) || key.isChar('K')) {
            state.detailScroll(state.detailScroll() - 5);
        } else if (key.isKey(KeyCode.TAB) || key.isKey(KeyCode.RIGHT) || key.isKey(KeyCode.LEFT)) {
            if (!blockedWhileRunning()) {
                boolean back = key.hasShift() || key.isKey(KeyCode.LEFT);
                state.sequenceIndex(state.sequenceIndex() + (back ? -1 : 1));
                replan();
            }
        } else if (key.isKey(KeyCode.CHAR) && key.character() >= '1' && key.character() <= '9'
                && key.character() - '1' < sequences) {
            if (!blockedWhileRunning()) {
                state.sequenceIndex(key.character() - '1');
                replan();
            }
        } else if (key.isChar('r')) {
            confirmRun(0, count);
        } else if (key.isKey(KeyCode.ENTER)) {
            confirmRun(state.selected(), Math.min(count, state.selected() + 1));
        } else if (key.isChar('R')) {
            confirmRun(state.selected(), count);
        } else if (key.isChar('x')) {
            if (state.running()) {
                cancel.set(true);
                state.stopping(true);
                toast(Tone.WARN, "Stopping after the current step");
            }
        } else if (key.isChar('e')) {
            chooseEnvironment();
        } else if (key.isChar('v')) {
            editVariables();
        } else if (key.isChar('t')) {
            fetchToken();
        } else if (key.isChar('l')) {
            toast(Tone.INFO, state.lastLog() == null ? "No log yet: start a run"
                    : "Log: " + state.lastLog().toAbsolutePath());
        } else if (key.isChar('?')) {
            state.help(true);
        } else if (key.isChar('q') || key.isKey(KeyCode.ESCAPE)) {
            quit(tui);
        }
        dirty = true;
    }

    private void quit(TuiRunner tui) {
        if (state.running() && state.dialog() == null) {
            state.input(null);
            state.menu(null);
            state.dialog(new Dialog("Quit?", List.of(
                    new DialogLine(Tone.WARN, "A run is going on: quitting interrupts it.")), "quit", () -> {
                cancel.set(true);
                if (tui != null) {
                    tui.quit();
                }
            }));
            return;
        }
        if (tui != null) {
            tui.quit();
        }
    }

    // ---------------------------------------------------------------- plumbing

    private void work(Runnable work) {
        if (synchronous) {
            work.run();
        } else {
            background.submit(work);
        }
    }

    private void ui(Runnable action) {
        if (synchronous) {
            action.run();
            return;
        }
        TuiRunner tui = runner;
        if (tui != null && tui.isRunning()) {
            tui.runLater(() -> {
                action.run();
                dirty = true;
            });
        }
    }

    private void toast(Tone tone, String message) {
        state.toast(new Toast(tone, message, clock.instant().plus(TOAST)));
        dirty = true;
    }

    /** For tests: the key handler without a terminal. */
    void press(KeyEvent key) {
        key(key, null);
    }
}
