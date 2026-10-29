package com.anorys.chainrunner.tui;

import com.anorys.chainrunner.config.ChainRunnerConfig;
import com.anorys.chainrunner.config.ChainRunnerConfig.Environment;
import com.anorys.chainrunner.config.ChainRunnerConfig.Sequence;
import com.anorys.chainrunner.core.CallResult;
import com.anorys.chainrunner.core.Planner.PlannedCall;
import com.anorys.chainrunner.core.TokenClient.Token;
import com.anorys.chainrunner.tui.theme.Tone;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Everything the screen shows. Only touched on the render thread. */
public final class AppState {

    public enum TokenPhase {
        NONE, FETCHING, VALID, FAILED
    }

    private final ChainRunnerConfig config;
    private Environment env;
    private int sequenceIndex;
    private final Map<String, Object> overrides = new LinkedHashMap<>();
    private Map<String, Object> variables = Map.of();

    private List<PlannedCall> calls = List.of();
    private List<CallResult> results = new ArrayList<>();
    private String planError;
    private int selected;
    private int detailScroll;

    private boolean running;
    private boolean stopping;
    private int runTotal;
    private Instant runStarted;
    private Path lastLog;

    /** The token of each API of the environment that uses one, in the order of the configuration. */
    private final Map<String, ApiToken> tokens = new LinkedHashMap<>();

    private Toast toast;
    private Dialog dialog;
    private Menu menu;
    private Input input;
    private boolean help;
    private long tick;

    public AppState(ChainRunnerConfig config, Environment env, int sequenceIndex) {
        this.config = config;
        this.env = env;
        this.sequenceIndex = sequenceIndex;
    }

    public ChainRunnerConfig config() {
        return config;
    }

    public Environment env() {
        return env;
    }

    public void env(Environment env) {
        this.env = env;
    }

    public Sequence sequence() {
        return config.sequences().get(sequenceIndex);
    }

    public int sequenceIndex() {
        return sequenceIndex;
    }

    public void sequenceIndex(int index) {
        this.sequenceIndex = Math.floorMod(index, config.sequences().size());
    }

    /** The values set from the UI or the command line, over the configuration's. */
    public Map<String, Object> overrides() {
        return overrides;
    }

    public Map<String, Object> variables() {
        return variables;
    }

    public void plan(Map<String, Object> variables, List<PlannedCall> calls, String error) {
        this.variables = Map.copyOf(variables);
        this.calls = List.copyOf(calls);
        this.planError = error;
        this.results = new ArrayList<>();
        calls.forEach(c -> results.add(CallResult.pending()));
        select(selected);
    }

    public List<PlannedCall> calls() {
        return calls;
    }

    public List<CallResult> results() {
        return results;
    }

    public CallResult result(int index) {
        return index >= 0 && index < results.size() ? results.get(index) : CallResult.pending();
    }

    public void result(int index, CallResult result) {
        if (index >= 0 && index < results.size()) {
            results.set(index, result);
        }
    }

    public String planError() {
        return planError;
    }

    public int selected() {
        return selected;
    }

    public void select(int index) {
        int clamped = calls.isEmpty() ? 0 : Math.max(0, Math.min(calls.size() - 1, index));
        if (clamped != selected) {
            detailScroll = 0;
        }
        selected = clamped;
    }

    public int detailScroll() {
        return detailScroll;
    }

    public void detailScroll(int scroll) {
        this.detailScroll = Math.max(0, scroll);
    }

    public boolean running() {
        return running;
    }

    public boolean stopping() {
        return stopping;
    }

    public void stopping(boolean stopping) {
        this.stopping = stopping;
    }

    public void runStarted(int total, Instant now) {
        running = true;
        stopping = false;
        runTotal = total;
        runStarted = now;
    }

    public void runFinished(Path log) {
        running = false;
        stopping = false;
        lastLog = log;
    }

    public int runTotal() {
        return runTotal;
    }

    public Instant runStartedAt() {
        return runStarted;
    }

    public Path lastLog() {
        return lastLog;
    }

    public long count(CallResult.Status status) {
        return results.stream().filter(r -> r.status() == status).count();
    }

    /** Where each API's token stands: what the header shows. */
    public Map<String, ApiToken> tokens() {
        return tokens;
    }

    public void token(String api, TokenPhase phase, Token token, String error) {
        tokens.put(api, new ApiToken(phase, token, error));
    }

    public void clearTokens() {
        tokens.clear();
    }

    public record ApiToken(TokenPhase phase, Token token, String error) {
    }

    public Toast toast() {
        return toast;
    }

    public void toast(Toast toast) {
        this.toast = toast;
    }

    public Dialog dialog() {
        return dialog;
    }

    public void dialog(Dialog dialog) {
        this.dialog = dialog;
    }

    public Menu menu() {
        return menu;
    }

    public void menu(Menu menu) {
        this.menu = menu;
    }

    public Input input() {
        return input;
    }

    public void input(Input input) {
        this.input = input;
    }

    public boolean help() {
        return help;
    }

    public void help(boolean help) {
        this.help = help;
    }

    public long tick() {
        return tick;
    }

    public void tick(long tick) {
        this.tick = tick;
    }

    /** Something moves on screen: the spinners and the wordmark need frames. */
    public boolean animating() {
        return running || tokens.values().stream().anyMatch(t -> t.phase() == TokenPhase.FETCHING);
    }

    public record Toast(Tone tone, String message, Instant until) {
    }

    public record DialogLine(Tone tone, String text) {
    }

    /** A message with an optional action: {@code y} or enter confirms, esc cancels. */
    public record Dialog(String title, List<DialogLine> lines, String confirmLabel, Runnable onConfirm) {
    }

    /** A list to pick from. */
    public static final class Menu {

        public record Item(String key, String label, String detail, String note, Tone tone) {
        }

        private final String title;
        private final List<Item> items;
        private final String hint;
        private final Consumer<Item> onPick;
        private int selected;

        public Menu(String title, List<Item> items, int selected, String hint, Consumer<Item> onPick) {
            this.title = title;
            this.items = List.copyOf(items);
            this.selected = Math.max(0, Math.min(items.size() - 1, selected));
            this.hint = hint;
            this.onPick = onPick;
        }

        public String title() {
            return title;
        }

        public List<Item> items() {
            return items;
        }

        public String hint() {
            return hint;
        }

        public int selected() {
            return selected;
        }

        public void move(int delta) {
            if (!items.isEmpty()) {
                selected = Math.floorMod(selected + delta, items.size());
            }
        }

        public void pick() {
            if (!items.isEmpty()) {
                onPick.accept(items.get(selected));
            }
        }
    }

    /** A one-line text field: a value, a password, or a word to type to confirm. */
    public static final class Input {

        private final String title;
        private final List<DialogLine> lines;
        private final String label;
        private final boolean masked;
        private final Consumer<String> onSubmit;
        private final StringBuilder value;

        public Input(String title, List<DialogLine> lines, String label, String value, boolean masked, Consumer<String> onSubmit) {
            this.title = title;
            this.lines = List.copyOf(lines);
            this.label = label;
            this.masked = masked;
            this.onSubmit = onSubmit;
            this.value = new StringBuilder(value == null ? "" : value);
        }

        public String title() {
            return title;
        }

        public List<DialogLine> lines() {
            return lines;
        }

        public String label() {
            return label;
        }

        public boolean masked() {
            return masked;
        }

        public String value() {
            return value.toString();
        }

        public void type(String text) {
            value.append(text.replace("\r", "").replace("\n", ""));
        }

        public void backspace() {
            if (!value.isEmpty()) {
                value.setLength(value.offsetByCodePoints(value.length(), -1));
            }
        }

        public void clear() {
            value.setLength(0);
        }

        public void submit() {
            onSubmit.accept(value.toString());
        }
    }
}
