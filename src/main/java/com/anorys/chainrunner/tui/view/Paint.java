package com.anorys.chainrunner.tui.view;

import com.anorys.chainrunner.tui.theme.Theme;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;

import java.util.ArrayList;
import java.util.List;

/** Drawing helpers shared by the views: text that never overflows its area, panels, gradient bars. */
final class Paint {

    private final Buffer buffer;
    private final Theme theme;

    Paint(Buffer buffer, Theme theme) {
        this.buffer = buffer;
        this.theme = theme;
    }

    Theme theme() {
        return theme;
    }

    Buffer buffer() {
        return buffer;
    }

    void fill(Rect area, Color background) {
        if (!area.isEmpty()) {
            // A fresh cell: a modal drawn over text must not inherit its modifiers.
            Cell blank = new Cell(" ", theme.bg(background));
            for (int y = area.top(); y < area.bottom(); y++) {
                for (int x = area.left(); x < area.right(); x++) {
                    buffer.set(x, y, blank);
                }
            }
        }
    }

    /** Draws {@code line} at (x, y), cut at {@code maxWidth} columns; returns the columns used. */
    int line(int x, int y, Line line, int maxWidth) {
        if (maxWidth <= 0 || y < buffer.area().top() || y >= buffer.area().bottom()) {
            return 0;
        }
        return buffer.setLine(x, y, line, maxWidth);
    }

    int spans(int x, int y, int maxWidth, Span... spans) {
        return line(x, y, Line.from(spans), maxWidth);
    }

    int spans(int x, int y, int maxWidth, List<Span> spans) {
        return line(x, y, Line.from(spans), maxWidth);
    }

    int text(int x, int y, String text, Style style, int maxWidth) {
        return line(x, y, Line.from(Span.styled(text, style)), maxWidth);
    }

    /** Right-aligned within [x, x + width). */
    void right(int x, int y, int width, Line line) {
        int w = Math.min(line.width(), width);
        line(x + width - w, y, line, w);
    }

    /** A rounded panel with a "◆ title" and an optional right-hand note; returns the inner area. */
    Rect panel(Rect area, String title, String note, boolean focused) {
        Rect inner = frame(area, Theme.MANTLE, focused ? Theme.ACCENT : Theme.SURFACE1);
        // Titles are drawn after the border: a Block would paint them in the border's color.
        line(area.x() + 2, area.y(), Line.from(
                Span.styled(" " + theme.glyphs().bullet() + " ", theme.on(focused ? Theme.SPARK : Theme.ACCENT, Theme.MANTLE)),
                Span.styled(title + " ", theme.on(Theme.TEXT, Theme.MANTLE).bold())), area.width() - 4);
        if (note != null) {
            Line noteLine = Line.from(Span.styled(" " + note + " ", theme.on(Theme.OVERLAY, Theme.MANTLE)));
            right(area.x() + 2, area.bottom() - 1, area.width() - 4, noteLine);
        }
        return new Rect(inner.x() + 1, inner.y(), Math.max(0, inner.width() - 2), inner.height());
    }

    /** A filled area with a rounded border; returns the area inside the border. */
    Rect frame(Rect area, Color background, Color border) {
        Block block = Block.builder()
                .borders(Borders.ALL)
                .borderType(theme.glyphs().unicode() ? BorderType.ROUNDED : BorderType.PLAIN)
                .borderStyle(theme.on(border, background))
                .style(theme.on(Theme.TEXT, background))
                .build();
        fill(area, background);
        block.render(area, buffer);
        return block.inner(area);
    }

    /** A thick bar filled along the brand gradient, the rest in a grey track. */
    void bar(int x, int y, int width, double ratio, double phase) {
        bar(x, y, width, ratio, phase, null);
    }

    void bar(int x, int y, int width, double ratio, double phase, Color solid) {
        if (width <= 0) {
            return;
        }
        double clamped = Math.max(0, Math.min(1, ratio));
        double cells = clamped * width;
        int full = (int) Math.floor(cells);
        String filled = theme.glyphs().unicode() ? "━" : "=";
        String track = theme.glyphs().unicode() ? "━" : "-";
        String half = theme.glyphs().unicode() ? "╸" : "-";
        for (int i = 0; i < width; i++) {
            Color color = solid != null ? solid : Theme.brand(phase + (double) i / Math.max(1, width) * 0.5);
            if (i < full) {
                buffer.setString(x + i, y, filled, theme.fg(color));
            } else if (i == full && cells - full >= 0.5) {
                buffer.setString(x + i, y, half, theme.fg(color));
            } else {
                buffer.setString(x + i, y, track, theme.fg(Theme.SURFACE1));
            }
        }
    }

    static String pad(String s, int width) {
        if (s.length() >= width) {
            return s.substring(0, Math.max(0, width));
        }
        return s + " ".repeat(width - s.length());
    }

    /** Splits {@code s} into lines of at most {@code width} columns, at spaces when it can. */
    static List<String> wrap(String s, int width) {
        List<String> lines = new ArrayList<>();
        String rest = s;
        while (width > 0 && rest.length() > width) {
            int cut = rest.lastIndexOf(' ', width);
            if (cut <= width / 3) {
                cut = width;
            }
            lines.add(rest.substring(0, cut).stripTrailing());
            rest = "  " + rest.substring(cut).strip();
        }
        lines.add(rest);
        return lines;
    }

    static String ellipsis(String s, int width) {
        if (width <= 0) {
            return "";
        }
        if (s.length() <= width) {
            return s;
        }
        return width == 1 ? "…" : s.substring(0, width - 1) + "…";
    }

    /** The tail of a path, keeping its end readable: "…\projects\anorys". */
    static String ellipsisStart(String s, int width) {
        if (width <= 0) {
            return "";
        }
        if (s.length() <= width) {
            return s;
        }
        return width == 1 ? "…" : "…" + s.substring(s.length() - width + 1);
    }
}
