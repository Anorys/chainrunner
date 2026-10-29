package com.anorys.chainrunner.tui;

import dev.tamboui.layout.Position;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.AbstractBackend;
import dev.tamboui.terminal.Mode2027Support;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.Terminal.Signal;
import org.jline.utils.Curses;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;

/**
 * TamboUI's JLine 3 backend (MIT, TamboUI contributors), with its output buffered until each flush.
 * On Windows, JLine's console writer makes one console call per write: TamboUI writes every cell and
 * escape sequence on its own, so unbuffered, a frame takes thousands of calls, appears as a visible
 * sweep and holds the render thread, which also reads the keys. Buffered, a frame is one call.
 */
final class ConsoleBackend extends AbstractBackend {

    private static final String CSI = "\033[";
    /** Large enough for a full frame of a big terminal, styles included. */
    private static final int BUFFER = 1 << 18;

    private final Terminal terminal;
    private final Writer out;
    private final NonBlockingReader reader;
    private Attributes savedAttributes;
    private boolean inAlternateScreen;
    private boolean mouseEnabled;
    private boolean mode2027Enabled;
    /** A code point read ahead by {@link #peek(int)}; -2 when there is none. */
    private int peekedCodePoint = -2;

    ConsoleBackend(Terminal terminal) {
        this.terminal = terminal;
        this.out = new BufferedWriter(terminal.writer(), BUFFER);
        this.reader = terminal.reader();
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void clear() throws IOException {
        writeAndFlush(CSI + "2J" + CSI + "H");
    }

    @Override
    public Size size() {
        return new Size(terminal.getWidth(), terminal.getHeight());
    }

    // TamboUI hides and shows the cursor inside a frame, which it flushes as a whole afterwards.
    @Override
    public void showCursor() throws IOException {
        out.write(CSI + "?25h");
    }

    @Override
    public void hideCursor() throws IOException {
        out.write(CSI + "?25l");
    }

    @Override
    public Position getCursorPosition() {
        // JLine cannot query the cursor position.
        return Position.ORIGIN;
    }

    @Override
    public void enterAlternateScreen() throws IOException {
        capability(Capability.enter_ca_mode);
        inAlternateScreen = true;
    }

    @Override
    public void leaveAlternateScreen() throws IOException {
        capability(Capability.exit_ca_mode);
        inAlternateScreen = false;
    }

    @Override
    public void enableRawMode() throws IOException {
        savedAttributes = terminal.getAttributes();
        terminal.enterRawMode();
        // Ctrl+C reaches the dashboard as a key instead of a signal.
        Attributes attributes = terminal.getAttributes();
        attributes.setLocalFlag(Attributes.LocalFlag.ISIG, false);
        terminal.setAttributes(attributes);
        // Grapheme cluster mode: terminals that do not know it ignore it.
        Mode2027Support.enable(this);
        mode2027Enabled = true;
    }

    @Override
    public void disableRawMode() throws IOException {
        if (mode2027Enabled) {
            Mode2027Support.disable(this);
            mode2027Enabled = false;
        }
        flush();
        if (savedAttributes != null) {
            terminal.setAttributes(savedAttributes);
        }
    }

    @Override
    public void enableMouseCapture() throws IOException {
        enableMouseCapture(false);
    }

    @Override
    public void enableMouseCapture(boolean motion) throws IOException {
        writeAndFlush(CSI + "?1000h" + CSI + "?1002h" + (motion ? CSI + "?1003h" : "") + CSI + "?1015h" + CSI + "?1006h");
        mouseEnabled = true;
    }

    @Override
    public void disableMouseCapture() throws IOException {
        writeAndFlush(CSI + "?1006l" + CSI + "?1015l" + CSI + "?1003l" + CSI + "?1002l" + CSI + "?1000l");
        mouseEnabled = false;
    }

    @Override
    public void beginSynchronizedUpdate() throws IOException {
        out.write(MODE_2026_BSU);
    }

    @Override
    public void endSynchronizedUpdate() throws IOException {
        out.write(MODE_2026_ESU);
    }

    @Override
    public void scrollUp(int lines) throws IOException {
        writeAndFlush(CSI + lines + "S");
    }

    @Override
    public void scrollDown(int lines) throws IOException {
        writeAndFlush(CSI + lines + "T");
    }

    @Override
    public void insertLines(int n) throws IOException {
        if (n > 0) {
            out.write(CSI + n + "L");
        }
    }

    @Override
    public void deleteLines(int n) throws IOException {
        if (n > 0) {
            out.write(CSI + n + "M");
        }
    }

    @Override
    public void moveCursorUp(int n) throws IOException {
        if (n > 0) {
            out.write(CSI + n + "A");
        }
    }

    @Override
    public void moveCursorDown(int n) throws IOException {
        if (n > 0) {
            out.write(CSI + n + "B");
        }
    }

    @Override
    public void moveCursorRight(int n) throws IOException {
        if (n > 0) {
            out.write(CSI + n + "C");
        }
    }

    @Override
    public void moveCursorLeft(int n) throws IOException {
        if (n > 0) {
            out.write(CSI + n + "D");
        }
    }

    @Override
    public void eraseToEndOfLine() throws IOException {
        out.write(CSI + "K");
    }

    @Override
    public void carriageReturn() throws IOException {
        out.write('\r');
    }

    @Override
    public void writeRaw(byte[] data) throws IOException {
        // Straight to the terminal: what is buffered goes first.
        out.flush();
        terminal.output().write(data);
        terminal.output().flush();
    }

    @Override
    public void writeRaw(String data) throws IOException {
        out.write(data);
    }

    @Override
    public void writeRaw(CharSequence data) throws IOException {
        out.append(data);
    }

    @Override
    public void onResize(Runnable handler) {
        terminal.handle(Signal.WINCH, signal -> handler.run());
    }

    @Override
    public int read(int timeoutMs) throws IOException {
        if (peekedCodePoint != -2) {
            int codePoint = peekedCodePoint;
            peekedCodePoint = -2;
            return codePoint;
        }
        return readCodePoint(timeoutMs);
    }

    @Override
    public int peek(int timeoutMs) throws IOException {
        if (peekedCodePoint == -2) {
            peekedCodePoint = readCodePoint(timeoutMs);
        }
        return peekedCodePoint;
    }

    private int readCodePoint(int timeoutMs) throws IOException {
        int c = reader.read(timeoutMs);
        if (c < 0 || !Character.isHighSurrogate((char) c)) {
            return c;
        }
        int low = reader.read(50);
        if (low >= 0 && Character.isLowSurrogate((char) low)) {
            return Character.toCodePoint((char) c, (char) low);
        }
        return c;
    }

    /** Resets the terminal: style, mouse, alternate screen, cursor and raw mode. The terminal stays open. */
    @Override
    public void close() throws IOException {
        out.write(CSI + "0m");
        if (mouseEnabled) {
            disableMouseCapture();
        }
        if (inAlternateScreen) {
            leaveAlternateScreen();
        }
        showCursor();
        disableRawMode();
        flush();
    }

    private void capability(Capability capability) throws IOException {
        String sequence = terminal.getStringCapability(capability);
        if (sequence != null) {
            Curses.tputs(out, sequence);
        }
        out.flush();
    }

    private void writeAndFlush(String sequence) throws IOException {
        out.write(sequence);
        out.flush();
    }
}
