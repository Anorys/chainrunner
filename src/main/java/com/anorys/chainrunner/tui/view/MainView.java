package com.anorys.chainrunner.tui.view;

import com.anorys.chainrunner.config.ChainRunnerConfig.Environment;
import com.anorys.chainrunner.config.ChainRunnerConfig.Sequence;
import com.anorys.chainrunner.core.CallResult;
import com.anorys.chainrunner.core.CallResult.Request;
import com.anorys.chainrunner.core.Database;
import com.anorys.chainrunner.core.Planner;
import com.anorys.chainrunner.core.Planner.PlannedCall;
import com.anorys.chainrunner.core.Runner;
import com.anorys.chainrunner.core.Template;
import com.anorys.chainrunner.core.Template.UnknownVariableException;
import com.anorys.chainrunner.step.Step;
import com.anorys.chainrunner.tui.AppState;
import com.anorys.chainrunner.tui.AppState.Dialog;
import com.anorys.chainrunner.tui.AppState.DialogLine;
import com.anorys.chainrunner.tui.AppState.Input;
import com.anorys.chainrunner.tui.AppState.Menu;
import com.anorys.chainrunner.tui.AppState.Toast;
import com.anorys.chainrunner.tui.theme.Theme;
import com.anorys.chainrunner.tui.theme.Tone;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Draws the whole screen from its state, following the Anorys terminal style: a dark header with
 * the faceted mark and the wordmark in the brand gradient, the sequences as tabs, rounded panels, key
 * chips in the footer. Holds no state of its own.
 */
public final class MainView {

    public static final int MIN_WIDTH = 80;
    public static final int MIN_HEIGHT = 24;
    private static final int HEADER = 4;
    /** From this width, the detail panel sits on the right of the calls; below, under them. */
    private static final int WIDE = 120;

    private static final String[] WORDMARK = {
            "█▀▀ █ █ ▄▀█ █ █▄ █ █▀█ █ █ █▄ █ █▄ █ █▀▀ █▀█",
            "█▄▄ █▀█ █▀█ █ █ ▀█ █▀▄ █▄█ █ ▀█ █ ▀█ ██▄ █▀▄"};
    private static final String[] WORDMARK_ASCII = {"chainrunner", ""};
    /** Below this width, the wordmark is written in one line of plain letters, to leave room for the rest. */
    private static final int WORDMARK_MIN_WIDTH = 110;

    private final Theme theme;

    public MainView(Theme theme) {
        this.theme = theme;
    }

    public void render(Frame frame, AppState state, Instant now) {
        Rect area = frame.area();
        Paint paint = new Paint(frame.buffer(), theme);
        paint.fill(area, Theme.BASE);
        if (area.width() < MIN_WIDTH || area.height() < MIN_HEIGHT) {
            String message = "Make the terminal larger: at least " + MIN_WIDTH + " x " + MIN_HEIGHT;
            paint.text(area.x() + Math.max(0, (area.width() - message.length()) / 2), area.y() + area.height() / 2,
                    message, theme.subtext(), area.width());
            return;
        }
        header(paint, area, state, now);
        Rect body = new Rect(area.x() + 1, area.y() + HEADER + 1, area.width() - 2, area.height() - HEADER - 3);
        body(paint, body, state, now);
        toast(paint, new Rect(area.x(), area.bottom() - 2, area.width(), 1), state, now);
        footer(paint, new Rect(area.x(), area.bottom() - 1, area.width(), 1), state);
        if (state.input() != null) {
            input(paint, area, state.input(), state.tick());
        } else if (state.dialog() != null) {
            dialog(paint, area, state.dialog());
        } else if (state.menu() != null) {
            menu(paint, area, state.menu());
        } else if (state.help()) {
            help(paint, area);
        }
    }

    // ---------------------------------------------------------------- header

    private void header(Paint paint, Rect area, AppState state, Instant now) {
        paint.fill(new Rect(area.x(), area.y(), area.width(), 2), Theme.CRUST);
        int x = area.x() + 2;
        int y = area.y();
        x += mark(paint, x, y) + 2;

        // Wordmark: the gradient drifts while calls run.
        double phase = state.animating() ? (state.tick() % 120) / 120.0 : 0;
        String[] word = theme.glyphs().unicode() && area.width() >= WORDMARK_MIN_WIDTH ? WORDMARK : WORDMARK_ASCII;
        for (int row = 0; row < 2; row++) {
            List<Span> spans = new ArrayList<>();
            for (int i = 0; i < word[row].length(); i++) {
                Color c = Theme.brand(phase + i / (double) word[row].length() * 0.6);
                spans.add(Span.styled(String.valueOf(word[row].charAt(i)), theme.on(c, Theme.CRUST).bold()));
            }
            paint.spans(x, y + row, word[row].length(), spans);
        }
        x += word[0].length() + 3;

        int rightWidth = Math.min(46, Math.max(0, area.right() - x - 2));
        int rightX = area.right() - rightWidth - 2;
        int textWidth = rightX - x - 2;
        if (textWidth > 12) {
            paint.spans(x, y, textWidth, Span.styled("API call chains", theme.on(Theme.TEXT, Theme.CRUST).bold()));
            paint.spans(x, y + 1, textWidth, Span.styled(
                    Paint.ellipsisStart(state.config().file().toAbsolutePath().toString(), textWidth), theme.on(Theme.SUBTEXT, Theme.CRUST)));
        }
        if (rightWidth >= 24) {
            environment(paint, rightX, y, rightWidth, state);
            if (state.running()) {
                progress(paint, rightX, y + 1, rightWidth, state, now);
            } else {
                token(paint, rightX, y + 1, rightWidth, state, now);
            }
        }
        tabs(paint, new Rect(area.x(), y + 2, area.width(), 2), state);
    }

    /** The Anorys faceted hexagon in five cells: three greens around the orange point. */
    private int mark(Paint paint, int x, int y) {
        if (!theme.glyphs().unicode()) {
            paint.text(x, y, "<#>", theme.on(Theme.FACET_LIGHT, Theme.CRUST).bold(), 3);
            return 3;
        }
        paint.spans(x, y, 5,
                Span.styled("▗", theme.on(Theme.FACET_LIGHT, Theme.CRUST)),
                Span.styled("▟", theme.on(Theme.FACET_LIGHT, Theme.CRUST)),
                Span.styled("▄", theme.on(Theme.SPARK, Theme.FACET_LIGHT)),
                Span.styled("▙", theme.on(Theme.FACET, Theme.CRUST)),
                Span.styled("▖", theme.on(Theme.FACET, Theme.CRUST)));
        paint.spans(x, y + 1, 5,
                Span.styled("▝", theme.on(Theme.FACET_DARK, Theme.CRUST)),
                Span.styled("▜", theme.on(Theme.FACET_DARK, Theme.CRUST)),
                Span.styled("▀", theme.on(Theme.SPARK, Theme.FACET)),
                Span.styled("▛", theme.on(Theme.FACET, Theme.CRUST)),
                Span.styled("▘", theme.on(Theme.FACET, Theme.CRUST)));
        return 5;
    }

    /** {@code  DEV  orders-dev...}: the environment, red when protected. */
    private void environment(Paint paint, int x, int y, int width, AppState state) {
        Environment env = state.env();
        Span badge = theme.badge(env.protectedEnv() ? Tone.ERROR : Tone.OK, env.name());
        String host = env.hosts();
        paint.right(x, y, width, Line.from(
                Span.styled(Paint.ellipsis(host, Math.max(0, width - badge.width() - 2)) + "  ", theme.on(Theme.SUBTEXT, Theme.CRUST)),
                badge));
    }

    /** {@code ✔ orders 4 min  ✘ document  ○ billing}: the token of each API. */
    private void token(Paint paint, int x, int y, int width, AppState state, Instant now) {
        List<Span> spans = new ArrayList<>();
        if (state.tokens().isEmpty()) {
            spans.add(Span.styled("no token", theme.on(Theme.OVERLAY, Theme.CRUST)));
        } else {
            spans.add(Span.styled("tokens ", theme.on(Theme.OVERLAY, Theme.CRUST)));
        }
        state.tokens().forEach((api, t) -> {
            if (spans.size() > 1) {
                spans.add(Span.styled("  ", theme.on(Theme.OVERLAY, Theme.CRUST)));
            }
            switch (t.phase()) {
                case FETCHING -> spans.add(Span.styled(theme.glyphs().spinner(state.tick()) + " " + api, theme.on(Theme.ACCENT, Theme.CRUST)));
                case VALID -> {
                    if (t.token() != null && t.token().valid(now)) {
                        spans.add(Span.styled(theme.glyphs().ok() + " " + api + " ", theme.on(Theme.GREEN, Theme.CRUST)));
                        spans.add(Span.styled(duration(Duration.between(now, t.token().expiresAt())), theme.on(Theme.SUBTEXT, Theme.CRUST)));
                    } else {
                        spans.add(Span.styled(theme.glyphs().idle() + " " + api + " expired", theme.on(Theme.OVERLAY, Theme.CRUST)));
                    }
                }
                case FAILED -> spans.add(Span.styled(theme.glyphs().error() + " " + api + " refused", theme.on(Theme.RED, Theme.CRUST)));
                case NONE -> spans.add(Span.styled(theme.glyphs().idle() + " " + api, theme.on(Theme.OVERLAY, Theme.CRUST)));
            }
        });
        paint.right(x, y, width, Line.from(spans));
    }

    /** While calls run: a spinner, the count and a bar in the brand gradient. */
    private void progress(Paint paint, int x, int y, int width, AppState state, Instant now) {
        long done = state.results().stream().filter(r -> r.status() == CallResult.Status.OK
                || r.status() == CallResult.Status.FAILED || r.status() == CallResult.Status.SKIPPED
                || r.status() == CallResult.Status.UNNEEDED).count()
                - (state.calls().size() - state.runTotal());
        done = Math.max(0, done);
        String label = theme.glyphs().spinner(state.tick()) + " " + (state.stopping() ? "stopping… " : "") + done + "/" + state.runTotal() + " ";
        paint.text(x, y, label, theme.on(Theme.ACCENT, Theme.CRUST), width);
        double phase = (state.tick() % 60) / 60.0;
        paint.bar(x + label.length(), y, width - label.length(), state.runTotal() == 0 ? 0 : (double) done / state.runTotal(), phase);
    }

    /** The sequences, as tabs: {@code 1 export  2 status}. */
    private void tabs(Paint paint, Rect area, AppState state) {
        paint.fill(area, Theme.BASE);
        int x = area.x() + 2;
        int y = area.y();
        String rule = theme.glyphs().unicode() ? "─" : "-";
        paint.text(area.x(), y + 1, rule.repeat(area.width()), theme.fg(Theme.SURFACE1), area.width());
        List<Sequence> sequences = state.config().sequences();
        for (int i = 0; i < sequences.size(); i++) {
            boolean active = i == state.sequenceIndex();
            String key = i < 9 ? String.valueOf(i + 1) : " ";
            String label = " " + sequences.get(i).name() + " ";
            int width = key.length() + label.length() + 1;
            if (x + width > area.right() - 2) {
                paint.text(x, y, "…", theme.muted(), 1);
                break;
            }
            paint.spans(x, y, width,
                    Span.styled(" " + key, active ? theme.fg(Theme.SPARK).bold() : theme.muted()),
                    Span.styled(label, active ? theme.fg(Theme.ACCENT).bold() : theme.fg(Theme.SUBTEXT)));
            if (active) {
                String thick = theme.glyphs().unicode() ? "━" : "=";
                List<Span> underline = new ArrayList<>();
                for (int j = 0; j < width; j++) {
                    underline.add(Span.styled(thick, theme.fg(Theme.brand(j / (double) width * 0.7))));
                }
                paint.spans(x, y + 1, width, underline);
            }
            x += width + 2;
        }
        String description = state.sequence().description();
        if (description != null && x < area.right() - 20) {
            paint.right(x, y, area.right() - x - 2, Line.from(Span.styled(Paint.ellipsis(description, area.right() - x - 2), theme.muted())));
        }
    }

    // ---------------------------------------------------------------- body

    private void body(Paint paint, Rect body, AppState state, Instant now) {
        boolean wide = body.width() >= WIDE;
        Rect left;
        Rect detail;
        if (wide) {
            int leftWidth = Math.max(56, body.width() * 11 / 20);
            left = new Rect(body.x(), body.y(), leftWidth, body.height());
            detail = new Rect(body.x() + leftWidth + 1, body.y(), body.width() - leftWidth - 1, body.height());
        } else {
            int top = Math.max(8, body.height() / 2);
            left = new Rect(body.x(), body.y(), body.width(), top);
            detail = new Rect(body.x(), body.y() + top, body.width(), body.height() - top);
        }
        Map<String, Object> shown = shownVariables(state);
        int varHeight = wide && !shown.isEmpty() ? Math.min(shown.size() + 2, Math.max(3, left.height() / 3)) : 0;
        Rect calls = new Rect(left.x(), left.y(), left.width(), left.height() - varHeight);
        calls(paint, calls, state);
        if (varHeight > 0) {
            variables(paint, new Rect(left.x(), calls.bottom(), left.width(), varHeight), state, shown);
        }
        detail(paint, detail, state);
    }

    private void calls(Paint paint, Rect area, AppState state) {
        List<PlannedCall> calls = state.calls();
        long ok = state.count(CallResult.Status.OK) + state.count(CallResult.Status.UNNEEDED);
        long failed = state.count(CallResult.Status.FAILED);
        String note = calls.size() + " step" + (calls.size() > 1 ? "s" : "")
                + (ok + failed > 0 ? " " + theme.glyphs().divider() + " " + ok + " ok" + (failed > 0 ? ", " + failed + " failed" : "") : "");
        Rect inner = paint.panel(area, "Steps", note, true);
        if (state.planError() != null) {
            paint.text(inner.x(), inner.y() + 1, theme.glyphs().error() + " ", theme.state(Tone.ERROR), 2);
            int y = inner.y() + 1;
            for (String line : Paint.wrap(state.planError(), inner.width() - 2)) {
                paint.text(inner.x() + 2, y++, line, theme.text(), inner.width() - 2);
            }
            return;
        }
        int methodWidth = 8;
        int statusWidth = 8;
        int timeWidth = 8;
        int nameWidth = Math.max(10, inner.width() - 3 - 4 - methodWidth - statusWidth - timeWidth - 1);
        int y = inner.y();
        paint.spans(inner.x() + 3, y, inner.width() - 3,
                Span.styled(Paint.pad("#", 4), theme.header()),
                Span.styled(Paint.pad("METHOD", methodWidth), theme.header()),
                Span.styled(Paint.pad("NAME", nameWidth), theme.header()),
                Span.styled(Paint.pad("RESULT", statusWidth), theme.header()),
                Span.styled(String.format(Locale.ROOT, "%" + timeWidth + "s", "TIME"), theme.header()));
        int rows = inner.height() - 1;
        if (rows <= 0) {
            return;
        }
        int offset = Math.max(0, Math.min(state.selected() - rows / 2, calls.size() - rows));
        for (int i = offset; i < calls.size() && i - offset < rows; i++) {
            PlannedCall call = calls.get(i);
            CallResult result = state.result(i);
            int row = y + 1 + i - offset;
            boolean selected = i == state.selected();
            if (selected) {
                paint.buffer().setStyle(new Rect(inner.x() - 1, row, inner.width() + 2, 1), theme.selection());
                paint.text(inner.x() - 1, row, theme.glyphs().marker(), theme.fg(Theme.SPARK), 1);
            }
            Tone tone = tone(result);
            String symbol = tone == Tone.RUNNING ? theme.glyphs().spinner(state.tick()) : theme.glyphs().symbol(tone);
            paint.text(inner.x() + 1, row, symbol, theme.state(tone), 2);
            paint.spans(inner.x() + 3, row, inner.width() - 3,
                    Span.styled(Paint.pad(String.valueOf(i + 1), 4), theme.muted()),
                    Span.styled(Paint.pad(call.call().method(), methodWidth), method(call.call().method())),
                    Span.styled(Paint.pad(Paint.ellipsis(call.name(), nameWidth - 1), nameWidth),
                            selected ? theme.fg(Theme.TEXT).bold() : result.status() == CallResult.Status.SKIPPED ? theme.muted() : theme.text()),
                    Span.styled(Paint.pad(outcome(call.call().step(), result), statusWidth),
                            theme.state(result.status() == CallResult.Status.RUNNING ? Tone.RUNNING
                                    : result.status() == CallResult.Status.UNNEEDED ? Tone.IDLE
                                    : result.httpStatus() == null && call.call().step().outcome(result) == null ? Tone.IDLE
                                    : result.ok() ? Tone.OK : Tone.ERROR)),
                    Span.styled(String.format(Locale.ROOT, "%" + timeWidth + "s", result.duration() == null ? "" : millis(result.duration())), theme.subtext()));
        }
        if (calls.isEmpty()) {
            paint.text(inner.x() + 2, y + 1, "No step: is the forEach list empty? (v for the variables)", theme.subtext(), inner.width() - 2);
        }
    }

    /** The RESULT column: the HTTP status, a poll's attempt, or what the step says of its outcome. */
    private static String outcome(Step step, CallResult result) {
        if (result.httpStatus() != null) {
            return String.valueOf(result.httpStatus());
        }
        if (result.status() == CallResult.Status.RUNNING && !result.notes().isEmpty()) {
            String note = result.notes().get(0);
            return note.startsWith("attempt ") ? "#" + note.substring(8).split(" ", 2)[0] : "";
        }
        if (result.status() == CallResult.Status.UNNEEDED) {
            return "unneeded";
        }
        String own = result.status() == CallResult.Status.OK || result.status() == CallResult.Status.FAILED ? step.outcome(result) : null;
        if (own != null) {
            return own;
        }
        return result.status() == CallResult.Status.FAILED ? "–" : "";
    }

    /** The variables of the run, without the built-in ones ({@code env}, {@code today}, {@code now}). */
    private static Map<String, Object> shownVariables(AppState state) {
        return Planner.userVariables(state.config(), state.env(), state.overrides());
    }

    private void variables(Paint paint, Rect area, AppState state, Map<String, Object> shown) {
        Rect inner = paint.panel(area, "Variables", "v edit", false);
        int keyWidth = Math.min(22, shown.keySet().stream().mapToInt(String::length).max().orElse(8) + 2);
        int y = inner.y();
        int rows = inner.height();
        int i = 0;
        for (Map.Entry<String, Object> e : shown.entrySet()) {
            if (i == rows - 1 && shown.size() > rows) {
                paint.text(inner.x(), y + i, "… " + (shown.size() - i) + " more (v)", theme.muted(), inner.width());
                break;
            }
            boolean edited = state.overrides().containsKey(e.getKey());
            paint.spans(inner.x(), y + i, inner.width(),
                    Span.styled(Paint.pad(e.getKey(), keyWidth), edited ? theme.fg(Theme.ACCENT) : theme.subtext()),
                    Span.styled(Paint.ellipsis(Template.format(e.getValue()), inner.width() - keyWidth - 2), theme.text()),
                    Span.styled(edited ? " " + theme.glyphs().changes() : "", theme.fg(Theme.ACCENT)));
            i++;
        }
    }

    // ---------------------------------------------------------------- detail

    private void detail(Paint paint, Rect area, AppState state) {
        List<Line> lines = new ArrayList<>();
        PlannedCall call = state.calls().isEmpty() ? null : state.calls().get(state.selected());
        int width = Math.max(10, area.width() - 6);
        if (call != null) {
            CallResult result = state.result(call.index());
            Request request = result.request();
            boolean preview = request == null;
            if (preview) {
                request = preview(state, call);
            }
            Step step = call.call().step();
            String what = result.status() == CallResult.Status.UNNEEDED ? "SQL QUERY (" + call.call().guard().keyword() + ")"
                    : step.requestTitle();
            lines.add(section(preview ? what + " (preview)" : what));
            if (request == null) {
                for (String line : step.summary().split("\n")) {
                    for (String w : Paint.wrap(line, width)) {
                        lines.add(Line.from(Span.styled(w, theme.subtext())));
                    }
                }
                lines.add(Line.from(Span.styled("Uses a value extracted by an earlier step: known when it runs.", theme.muted())));
            } else {
                addRequest(lines, request, width);
            }
            for (Step.Detail detail : step.details()) {
                lines.add(Line.from(Span.styled(detail.label() + " ", theme.muted()), Span.styled(detail.value(),
                        switch (detail.kind()) {
                            case API -> theme.fg(Theme.ACCENT);
                            case DATABASE -> theme.fg(Theme.VIOLET);
                            case TEXT -> theme.text();
                        })));
            }
            if (call.call().guard() != null && !(result.status() == CallResult.Status.UNNEEDED)) {
                var guard = call.call().guard();
                lines.add(Line.from(Span.styled(guard.keyword() + " ", theme.fg(Theme.AMBER)),
                        Span.styled(guard.skipWhenFound() ? "not made if this query finds a row:" : "made only if this query finds a row:", theme.muted())));
                for (String line : guard.sql().split("\n")) {
                    for (String w : Paint.wrap(line, width - 2)) {
                        lines.add(Line.from(Span.styled("  " + w, theme.subtext())));
                    }
                }
            }
            if (call.call().database() != null && (step.usesDatabase() || call.call().guard() != null)) {
                lines.add(Line.from(Span.styled("database ", theme.muted()), Span.styled(call.call().database(), theme.fg(Theme.VIOLET))));
            }
            if (!call.call().extract().isEmpty()) {
                lines.add(Line.from(Span.styled("extracts ", theme.muted()),
                        Span.styled(String.join(", ", call.call().extract().keySet()), theme.fg(Theme.BLUE))));
            }
            lines.add(Line.from(Span.raw("")));
            if (result.status() == CallResult.Status.PENDING) {
                lines.add(section("RESPONSE"));
                lines.add(Line.from(Span.styled("Not run yet: ", theme.muted()), Span.styled("enter", theme.fg(Theme.ACCENT)),
                        Span.styled(" runs this step alone.", theme.muted())));
            } else if (result.status() == CallResult.Status.RUNNING) {
                lines.add(section("RESPONSE"));
                lines.add(Line.from(Span.styled(theme.glyphs().spinner(state.tick()) + " " + step.waiting(), theme.fg(Theme.ACCENT))));
                for (String note : result.notes()) {
                    lines.add(Line.from(Span.styled("  " + note, theme.subtext())));
                }
            } else {
                addResponse(lines, step, result, width);
            }
        }
        int scroll = Math.max(0, Math.min(state.detailScroll(), lines.size() - 1));
        String note = lines.size() > area.height() - 2 ? "pgup/pgdn  " + (scroll + 1) + "/" + lines.size() : null;
        Rect inner = paint.panel(area, call == null ? "Detail" : "Detail  " + (call.index() + 1) + ". " + Paint.ellipsis(call.name(), Math.max(4, area.width() - 22)), note, false);
        for (int i = 0; i < inner.height() && scroll + i < lines.size(); i++) {
            paint.line(inner.x(), inner.y() + i, lines.get(scroll + i), inner.width());
        }
    }

    private Request preview(AppState state, PlannedCall call) {
        Map<String, Object> vars = new LinkedHashMap<>(state.variables());
        vars.putAll(call.loop());
        // Values extracted by the calls already made in this run, for this call's forEach values.
        for (int i = 0; i < call.index(); i++) {
            if (call.loop().entrySet().containsAll(state.calls().get(i).loop().entrySet())) {
                vars.putAll(state.result(i).extracted());
            }
        }
        try {
            return new Runner(null, null, state.env(), state.config().options()).request(call.call(), vars);
        } catch (UnknownVariableException | IllegalArgumentException e) {
            return null;
        }
    }

    private void addRequest(List<Line> lines, Request request, int width) {
        if (request.sql()) {
            lines.add(Line.from(Span.styled("SQL", method("SQL").bold()), Span.styled(" " + request.url(), theme.muted())));
            for (String line : new Database.Statement(request.body(), request.params()).shown().split("\n")) {
                for (String w : Paint.wrap(line, width)) {
                    lines.add(Line.from(Span.styled(w, theme.text())));
                }
            }
            return;
        }
        String url = request.url();
        int q = request.http() ? url.indexOf('?') : -1;
        String base = q < 0 ? url : url.substring(0, q);
        List<String> wrapped = Paint.wrap(request.method() + " " + base, width);
        for (int i = 0; i < wrapped.size(); i++) {
            String text = wrapped.get(i);
            if (i == 0 && text.startsWith(request.method())) {
                lines.add(Line.from(Span.styled(request.method(), method(request.method()).bold()),
                        Span.styled(text.substring(request.method().length()), theme.text())));
            } else {
                lines.add(Line.from(Span.styled(text, theme.text())));
            }
        }
        if (q >= 0) {
            for (String pair : url.substring(q + 1).split("&")) {
                int eq = pair.indexOf('=');
                String key = decode(eq < 0 ? pair : pair.substring(0, eq));
                String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
                lines.add(Line.from(Span.styled("  " + key + " ", theme.subtext()), Span.styled("= ", theme.muted()),
                        Span.styled(value, theme.fg(Theme.ACCENT))));
            }
        }
        request.headers().forEach((k, v) -> {
            if (!k.equalsIgnoreCase("Accept")) {
                lines.add(Line.from(Span.styled("  " + k + ": ", theme.muted()), Span.styled(v, theme.subtext())));
            }
        });
        if (request.body() != null) {
            for (String line : request.body().split("\n")) {
                for (String w : Paint.wrap(line, width)) {
                    lines.add(Line.from(Span.styled(w, theme.subtext())));
                }
            }
        }
    }

    private void addResponse(List<Line> lines, Step step, CallResult result, int width) {
        Tone tone = tone(result);
        List<Span> head = new ArrayList<>();
        head.add(Span.styled("RESPONSE  ", theme.header()));
        if (result.status() == CallResult.Status.SKIPPED) {
            head.add(theme.badge(Tone.IDLE, "not run"));
        } else if (result.status() == CallResult.Status.UNNEEDED) {
            head.add(theme.badge(Tone.IDLE, "not needed"));
        } else {
            head.add(theme.badge(tone, result.httpStatus() != null ? "HTTP " + result.httpStatus()
                    : step.outcome(result) != null ? step.outcome(result) : result.ok() ? "ok" : "error"));
            if (result.duration() != null) {
                head.add(Span.styled("  " + millis(result.duration()), theme.subtext()));
            }
        }
        lines.add(Line.from(head));
        if (result.error() != null) {
            for (String w : result.error().lines().flatMap(l -> Paint.wrap(l, width - 2).stream()).toList()) {
                lines.add(Line.from(Span.styled(theme.glyphs().symbol(tone) + " ", theme.state(tone)), Span.styled(w, theme.text())));
            }
        }
        for (String note : result.notes()) {
            for (String w : Paint.wrap(note, width - 2)) {
                lines.add(Line.from(Span.styled("  " + w, theme.subtext())));
            }
        }
        result.extracted().forEach((k, v) -> lines.add(Line.from(Span.styled("  " + k + " ", theme.subtext()),
                Span.styled("= ", theme.muted()), Span.styled(Template.format(v), theme.fg(Theme.BLUE)))));
        String body = result.responseBody();
        if (body == null || body.isEmpty()) {
            if (result.status() != CallResult.Status.SKIPPED) {
                lines.add(Line.from(Span.styled("(no content)", theme.muted())));
            }
            return;
        }
        for (String line : body.split("\n")) {
            for (String w : Paint.wrap(line, width)) {
                lines.add(json(w));
            }
        }
    }

    /** A line of indented JSON: keys in grey, strings in green, the rest in text color. */
    private Line json(String line) {
        int colon = line.indexOf("\" : ");
        if (line.stripLeading().startsWith("\"") && colon > 0) {
            String value = line.substring(colon + 4);
            return Line.from(Span.styled(line.substring(0, colon + 1), theme.subtext()), Span.styled(" : ", theme.muted()),
                    Span.styled(value, value.startsWith("\"") ? theme.fg(Theme.GREEN) : theme.text()));
        }
        return Line.from(Span.styled(line, theme.text()));
    }

    private Line section(String title) {
        return Line.from(Span.styled(title, theme.header()));
    }

    // ---------------------------------------------------------------- footer, toast, overlays

    private void toast(Paint paint, Rect area, AppState state, Instant now) {
        Toast toast = state.toast();
        if (toast == null || now.isAfter(toast.until())) {
            return;
        }
        paint.spans(area.x() + 2, area.y(), area.width() - 4,
                Span.styled(theme.glyphs().symbol(toast.tone()) + " ", theme.state(toast.tone())),
                Span.styled(toast.message(), theme.text()));
    }

    private List<Span> chips(AppState state, boolean all) {
        List<Span> spans = new ArrayList<>();
        spans.add(Span.styled(" ", theme.bg(Theme.CRUST)));
        if (state.running()) {
            spans.addAll(theme.keyChip("x", "stop"));
            spans.addAll(theme.keyChip(theme.glyphs().unicode() ? "↑↓" : "up/dn", "select"));
            spans.addAll(theme.keyChip("q", "quit"));
            return spans;
        }
        spans.addAll(theme.keyChip(theme.glyphs().unicode() ? "↑↓" : "up/dn", "select"));
        spans.addAll(theme.keyChip("r", "run all"));
        spans.addAll(theme.keyChip("enter", "this step"));
        if (all) {
            spans.addAll(theme.keyChip("R", "from here"));
        }
        spans.addAll(theme.keyChip("e", "env"));
        spans.addAll(theme.keyChip("v", "variables"));
        if (all) {
            spans.addAll(theme.keyChip("t", "token"));
        }
        spans.addAll(theme.keyChip("?", "help"));
        spans.addAll(theme.keyChip("q", "quit"));
        return spans;
    }

    private void footer(Paint paint, Rect area, AppState state) {
        paint.fill(area, Theme.CRUST);
        List<Span> spans = chips(state, true);
        if (Line.from(spans).width() > area.width()) {
            spans = chips(state, false);
        }
        paint.spans(area.x(), area.y(), area.width(), spans);
    }

    private void dialog(Paint paint, Rect screen, Dialog dialog) {
        int width = Math.min(screen.width() - 8, Math.max(56,
                dialog.lines().stream().mapToInt(l -> l.text().length() + 6).max().orElse(56)));
        int height = Math.min(screen.height() - 4, dialog.lines().size() + 5);
        Rect area = center(screen, width, height);
        Rect inner = modal(paint, area, dialog.title());
        int y = inner.y() + 1;
        for (DialogLine line : dialog.lines()) {
            if (y >= inner.bottom() - 1) {
                break;
            }
            paint.text(inner.x(), y++, Paint.ellipsis(line.text(), inner.width()), style(line), inner.width());
        }
        List<Span> keys = new ArrayList<>();
        if (dialog.confirmLabel() != null) {
            keys.addAll(theme.keyChip("enter", dialog.confirmLabel()));
            keys.addAll(theme.keyChip("esc", "cancel"));
        } else {
            keys.addAll(theme.keyChip("esc", "close"));
        }
        paint.spans(inner.x(), inner.bottom() - 1, inner.width(), keys);
    }

    private void input(Paint paint, Rect screen, Input input, long tick) {
        int width = Math.min(screen.width() - 8, Math.max(64,
                input.lines().stream().mapToInt(l -> l.text().length() + 6).max().orElse(64)));
        int height = Math.min(screen.height() - 4, input.lines().size() + 7);
        Rect area = center(screen, width, height);
        Rect inner = modal(paint, area, input.title());
        int y = inner.y() + 1;
        for (DialogLine line : input.lines()) {
            if (y >= inner.bottom() - 3) {
                break;
            }
            paint.text(inner.x(), y++, Paint.ellipsis(line.text(), inner.width()), style(line), inner.width());
        }
        y = inner.bottom() - 3;
        String label = input.label() + " ";
        String value = input.masked() ? "•".repeat(input.value().codePointCount(0, input.value().length())) : input.value();
        int room = inner.width() - label.length() - 2;
        if (value.length() > room) {
            value = "…" + value.substring(value.length() - room + 1);
        }
        boolean caret = (tick / 6) % 2 == 0;
        paint.spans(inner.x(), y, inner.width(),
                Span.styled(label, theme.subtext()),
                Span.styled(value, theme.fg(Theme.TEXT).bold()),
                Span.styled(caret ? (theme.glyphs().unicode() ? "▏" : "_") : " ", theme.fg(Theme.SPARK)));
        String underline = theme.glyphs().unicode() ? "─" : "-";
        paint.text(inner.x() + label.length(), y + 1, underline.repeat(Math.max(0, inner.width() - label.length())), theme.fg(Theme.ACCENT), inner.width());
        List<Span> keys = new ArrayList<>(theme.keyChip("enter", "ok"));
        keys.addAll(theme.keyChip("esc", "cancel"));
        keys.addAll(theme.keyChip("ctrl-u", "clear"));
        paint.spans(inner.x(), inner.bottom() - 1, inner.width(), keys);
    }

    private void menu(Paint paint, Rect screen, Menu menu) {
        List<Menu.Item> items = menu.items();
        int labelWidth = Math.max(12, items.stream().mapToInt(i -> i.label().length()).max().orElse(12) + 2);
        int noteWidth = Math.max(8, items.stream().mapToInt(i -> i.note() == null ? 0 : i.note().length()).max().orElse(8) + 2);
        int detailWidth = items.stream().mapToInt(i -> i.detail() == null ? 0 : i.detail().length()).max().orElse(0);
        int width = Math.min(screen.width() - 8, Math.max(56, labelWidth + noteWidth + detailWidth + 10));
        int rows = Math.max(1, Math.min(items.size(), screen.height() - 10));
        int height = rows + 5;
        Rect area = center(screen, width, height);
        Rect inner = modal(paint, area, menu.title());
        int y = inner.y() + 1;
        int offset = Math.max(0, menu.selected() - rows + 1);
        for (int i = offset; i < items.size() && i - offset < rows; i++) {
            Menu.Item item = items.get(i);
            int row = y + i - offset;
            boolean selected = i == menu.selected();
            if (selected) {
                paint.buffer().setStyle(new Rect(inner.x() - 1, row, inner.width() + 2, 1), theme.selection());
                paint.text(inner.x() - 1, row, theme.glyphs().marker(), theme.fg(Theme.SPARK), 1);
            }
            Tone tone = item.tone() == null ? Tone.IDLE : item.tone();
            paint.text(inner.x() + 1, row, theme.glyphs().symbol(tone), theme.state(tone), 2);
            paint.text(inner.x() + 3, row, Paint.pad(item.label(), labelWidth), selected ? theme.fg(Theme.TEXT).bold() : theme.text(), labelWidth);
            int room = inner.width() - 3 - labelWidth - noteWidth;
            if (item.detail() != null && room > 4) {
                paint.text(inner.x() + 3 + labelWidth, row, Paint.ellipsis(item.detail(), room - 1), theme.subtext(), room - 1);
            }
            if (item.note() != null) {
                paint.right(inner.x(), row, inner.width(), Line.from(Span.styled(item.note(), theme.state(tone))));
            }
        }
        List<Span> keys = new ArrayList<>(theme.keyChip("enter", menu.hint()));
        keys.addAll(theme.keyChip("esc", "close"));
        paint.spans(inner.x(), inner.bottom() - 1, inner.width(), keys);
    }

    private void help(Paint paint, Rect screen) {
        String[][] keys = {
                {"↑ ↓  j k", "select a step"},
                {"1-9  tab  ← →", "switch sequence"},
                {"r", "run the whole sequence"},
                {"enter", "run the selected step alone"},
                {"R", "run from the selected step on"},
                {"x", "stop after the current step"},
                {"e", "switch environment"},
                {"v", "see and edit the variables (session)"},
                {"t", "ask for new tokens now"},
                {"pgup pgdn  J K", "scroll the detail"},
                {"l", "path of the last log"},
                {"?", "show or hide this help"},
                {"q  ctrl-c", "quit"}};
        int width = Math.min(screen.width() - 8, 66);
        int height = keys.length + 4;
        Rect inner = modal(paint, center(screen, width, height), "Keys");
        int y = inner.y() + 1;
        for (String[] key : keys) {
            paint.spans(inner.x(), y++, inner.width(),
                    Span.styled(Paint.pad(key[0], 18), theme.fg(Theme.ACCENT).bold()),
                    Span.styled(key[1], theme.text()));
        }
    }

    private Rect modal(Paint paint, Rect area, String title) {
        Rect inner = paint.frame(area, Theme.MANTLE, Theme.ACCENT);
        paint.spans(area.x() + 2, area.y(), area.width() - 4,
                Span.styled(" " + theme.glyphs().bullet() + " ", theme.on(Theme.SPARK, Theme.MANTLE)),
                Span.styled(title + " ", theme.on(Theme.TEXT, Theme.MANTLE).bold()));
        return new Rect(inner.x() + 2, inner.y(), Math.max(0, inner.width() - 4), inner.height());
    }

    private static Rect center(Rect screen, int width, int height) {
        return new Rect(screen.x() + (screen.width() - width) / 2, screen.y() + (screen.height() - height) / 2, width, height);
    }

    // ---------------------------------------------------------------- small values

    private Style style(DialogLine line) {
        if (line.tone() == null) {
            return theme.text();
        }
        return line.tone() == Tone.IDLE ? theme.subtext() : theme.state(line.tone());
    }

    private static Tone tone(CallResult result) {
        return switch (result.status()) {
            case PENDING, SKIPPED, UNNEEDED -> Tone.IDLE;
            case RUNNING -> Tone.RUNNING;
            case OK -> Tone.OK;
            case FAILED -> Tone.ERROR;
        };
    }

    /** GET in blue, POST/PUT/PATCH in the accent, DELETE in amber: what the call does, at a glance. */
    private Style method(String method) {
        return switch (method) {
            case "GET", "HEAD", "OPTIONS" -> theme.fg(Theme.BLUE);
            case "DELETE" -> theme.fg(Theme.AMBER);
            case "SQL" -> theme.fg(Theme.VIOLET);
            default -> theme.fg(Theme.ACCENT);
        };
    }

    private static String host(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    private static String decode(String s) {
        return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    static String millis(Duration d) {
        long ms = d.toMillis();
        return ms < 1000 ? ms + " ms" : ms < 60_000 ? String.format(Locale.ROOT, "%.1f s", ms / 1000.0) : Runner.clock(d);
    }

    /** {@code 4 min 12 s}, {@code 38 s}. */
    public static String duration(Duration d) {
        long s = Math.max(0, d.toSeconds());
        if (s >= 3600) {
            return s / 3600 + " h " + (s % 3600) / 60 + " min";
        }
        if (s >= 60) {
            return s / 60 + " min " + s % 60 + " s";
        }
        return s + " s";
    }
}
