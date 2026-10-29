package com.anorys.chainrunner.tui.theme;

/** The symbols of the UI, in Unicode or, for old consoles, in ASCII ({@code CHAINRUNNER_ASCII=1}). */
public record Glyphs(String ok, String warn, String error, String blocked, String info, String idle,
                     String ahead, String behind, String changes, String marker, String bullet, String divider,
                     String[] spinner, boolean unicode) {

    public static final Glyphs UNICODE = new Glyphs("✔", "▲", "✘", "⊘", "●", "◌", "↑", "↓", "✎", "▌", "◆", "·",
            new String[]{"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"}, true);

    public static final Glyphs ASCII = new Glyphs("+", "!", "x", "#", "i", ".", "^", "v", "*", ">", "*", "-",
            new String[]{"|", "/", "-", "\\"}, false);

    public String symbol(Tone tone) {
        return switch (tone) {
            case OK -> ok;
            case WARN -> warn;
            case ERROR -> error;
            case BLOCKED -> blocked;
            case INFO -> info;
            case RUNNING -> spinner[0];
            case IDLE -> idle;
        };
    }

    public String spinner(long tick) {
        return spinner[(int) (tick % spinner.length)];
    }
}
