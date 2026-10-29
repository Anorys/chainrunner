package com.anorys.chainrunner.tui;

import org.jline.nativ.CLibrary;
import org.jline.nativ.Kernel32;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.spi.TerminalProvider;
import org.jline.utils.OSUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The terminal the dashboard draws on, opened through JLine's native library (JNI). The native image
 * depends on it on Windows: without it JLine falls back to a "dumb" terminal that cannot draw the
 * dashboard, and says so with a logged warning. Here that case is an error with its causes instead.
 */
public final class SystemTerminal {

    private SystemTerminal() {
    }

    /** Opens the system terminal, never a dumb one. */
    public static Terminal open() throws TerminalUnavailableException {
        try {
            return TerminalBuilder.builder().system(true).dumb(false).build();
        } catch (IllegalStateException | IOException e) {
            List<String> causes = new ArrayList<>();
            for (Throwable suppressed : e.getSuppressed()) {
                causes.add(String.valueOf(suppressed.getMessage()));
            }
            if (e.getCause() != null) {
                causes.add(String.valueOf(e.getCause().getMessage()));
            }
            throw new TerminalUnavailableException("cannot open the terminal"
                    + (causes.isEmpty() ? " (the input or the output is not a console)" : ": " + String.join("; ", causes)), e);
        }
    }

    /**
     * Loads JLine's native library and asks it whether the standard output is a console, so that a build
     * missing the native support fails here rather than with a dumb terminal. Used by {@code chainrunner
     * --check-terminal} to check a native build, where there is no console to open.
     */
    public static String check() throws IOException {
        TerminalProvider provider = TerminalProvider.load(TerminalBuilder.PROP_PROVIDER_JNI);
        boolean console;
        if (OSUtils.IS_WINDOWS) {
            int[] mode = new int[1];
            console = Kernel32.GetConsoleMode(Kernel32.GetStdHandle(Kernel32.STD_OUTPUT_HANDLE), mode) != 0;
        } else {
            console = CLibrary.isatty(1) == 1;
        }
        return provider.name() + " terminal support loaded; standard output is " + (console ? "" : "not ") + "a console";
    }

    /** The terminal could not be opened: its message says why. */
    public static final class TerminalUnavailableException extends IOException {

        public TerminalUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
