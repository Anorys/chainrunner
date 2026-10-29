package com.anorys.chainrunner.tui.theme;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;

import java.util.ArrayList;
import java.util.List;

/**
 * The Anorys palette and the styles built from it (see docs/src/content/docs/development/tui-style-guide.md).
 * Views take every color from here, so that
 * {@code NO_COLOR} and the ASCII fallback apply everywhere.
 */
public final class Theme {

    // Neutrals, from the dark mode of the Anorys "Atlas dense" chart: grey structures.
    public static final Color CRUST = Color.hex("#171919");
    public static final Color MANTLE = Color.hex("#1B1D1D");
    public static final Color BASE = Color.hex("#202323");
    public static final Color SURFACE0 = Color.hex("#163631");
    public static final Color SURFACE1 = Color.hex("#303535");
    public static final Color OVERLAY = Color.hex("#6E7875");
    public static final Color SUBTEXT = Color.hex("#A2ABA8");
    public static final Color TEXT = Color.hex("#E3E7E6");

    // The three facets of the Anorys logo, dark to light, and the accent: green acts.
    public static final Color FACET_DARK = Color.hex("#08473F");
    public static final Color FACET = Color.hex("#0E7A6B");
    public static final Color FACET_LIGHT = Color.hex("#17A58F");
    public static final Color ACCENT = Color.hex("#4FC1B1");
    public static final Color ON_ACCENT = Color.hex("#06201C");
    /** The orange point of the logo: what is happening now. Used sparingly. */
    public static final Color SPARK = Color.hex("#FF7A1A");

    // States.
    public static final Color GREEN = Color.hex("#5FD39A");
    public static final Color AMBER = Color.hex("#F0A766");
    public static final Color RED = Color.hex("#F07178");
    public static final Color VIOLET = Color.hex("#BFA0F2");
    public static final Color BLUE = Color.hex("#86B3F2");

    private static final Color[] BRAND = {FACET, FACET_LIGHT, ACCENT, FACET_LIGHT};

    private final boolean color;
    private final Glyphs glyphs;

    public Theme(boolean color, Glyphs glyphs) {
        this.color = color;
        this.glyphs = glyphs;
    }

    /** From the environment: {@code NO_COLOR} turns colors off, {@code CHAINRUNNER_ASCII} the Unicode symbols. */
    public static Theme fromEnvironment() {
        String ascii = System.getenv("CHAINRUNNER_ASCII");
        return new Theme(System.getenv("NO_COLOR") == null,
                ascii != null && !ascii.isEmpty() && !ascii.equals("0") ? Glyphs.ASCII : Glyphs.UNICODE);
    }

    public static Theme standard() {
        return new Theme(true, Glyphs.UNICODE);
    }

    public Glyphs glyphs() {
        return glyphs;
    }

    public boolean colored() {
        return color;
    }

    public Style fg(Color foreground) {
        return color ? Style.create().fg(foreground) : Style.create();
    }

    public Style on(Color foreground, Color background) {
        return color ? Style.create().fg(foreground).bg(background) : Style.create();
    }

    public Style bg(Color background) {
        return color ? Style.create().bg(background) : Style.create();
    }

    public Style text() {
        return fg(TEXT);
    }

    public Style subtext() {
        return fg(SUBTEXT);
    }

    public Style muted() {
        return fg(OVERLAY);
    }

    public Style header() {
        return fg(OVERLAY).bold();
    }

    /** The selected row: a lighter surface, so the row keeps its own colors. */
    public Style selection() {
        return color ? Style.create().bg(SURFACE0) : Style.create().reversed();
    }

    public Color stateColor(Tone tone) {
        return switch (tone) {
            case OK -> GREEN;
            case WARN -> AMBER;
            case ERROR -> RED;
            case BLOCKED -> VIOLET;
            case INFO -> BLUE;
            case RUNNING -> ACCENT;
            case IDLE -> OVERLAY;
        };
    }

    public Style state(Tone tone) {
        return fg(stateColor(tone));
    }

    /** {@code ✔ ok}: the symbol and the word of a state, in its color. */
    public Span stateSpan(Tone tone, String word) {
        return Span.styled(glyphs.symbol(tone) + " " + word, state(tone));
    }

    /** {@code  BLOCKED }: an upper-case word on the state color. */
    public Span badge(Tone tone, String word) {
        String label = " " + word.toUpperCase(java.util.Locale.ROOT) + " ";
        return color ? Span.styled(label, Style.create().fg(CRUST).bg(stateColor(tone)).bold())
                : Span.styled("[" + word.toUpperCase(java.util.Locale.ROOT) + "]", Style.create().bold());
    }

    /** A footer key chip: the key on a surface, then what it does. */
    public List<Span> keyChip(String key, String action) {
        return List.of(
                Span.styled(" " + key + " ", color ? Style.create().fg(ACCENT).bg(SURFACE1).bold() : Style.create().reversed()),
                Span.styled(" " + action + "  ", color ? Style.create().fg(SUBTEXT).bg(CRUST) : Style.create()));
    }

    /** Text colored character by character along the brand gradient, shifted by {@code phase} (0 to 1). */
    public Line gradient(String text, double phase, boolean bold) {
        List<Span> spans = new ArrayList<>();
        int n = Math.max(1, text.length() - 1);
        for (int i = 0; i < text.length(); i++) {
            Style style = color ? Style.create().fg(brand(((double) i / n + phase) % 1.0)) : Style.create();
            spans.add(Span.styled(String.valueOf(text.charAt(i)), bold ? style.bold() : style));
        }
        return Line.from(spans);
    }

    /**
     * The brand gradient at {@code t} (0 to 1): the logo's greens, light and back, so that a shifting
     * phase loops without a jump.
     */
    public static Color brand(double t) {
        double x = ((t % 1.0) + 1.0) % 1.0 * BRAND.length;
        int i = (int) Math.floor(x);
        return mix(BRAND[i % BRAND.length], BRAND[(i + 1) % BRAND.length], x - i);
    }

    public static Color mix(Color a, Color b, double t) {
        Color.Rgb x = a.toRgb();
        Color.Rgb y = b.toRgb();
        return Color.rgb(
                (int) Math.round(x.r() + (y.r() - x.r()) * t),
                (int) Math.round(x.g() + (y.g() - x.g()) * t),
                (int) Math.round(x.b() + (y.b() - x.b()) * t));
    }
}
