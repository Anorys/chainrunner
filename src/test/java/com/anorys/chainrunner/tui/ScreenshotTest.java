package com.anorys.chainrunner.tui;

import com.anorys.chainrunner.FakeApi;
import com.anorys.chainrunner.config.ChainRunnerConfig;
import com.anorys.chainrunner.config.ConfigLoader;
import com.anorys.chainrunner.core.Http;
import com.anorys.chainrunner.tui.theme.Theme;
import com.anorys.chainrunner.tui.view.MainView;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.export.ExportRequest;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the UI with keys, without a terminal, against a fake API, and renders each screen. With
 * {@code -Dchainrunner.screenshots=<dir>} it also writes them as SVG files.
 */
class ScreenshotTest {

    private FakeApi api;
    private App app;

    @BeforeEach
    void start() throws Exception {
        api = new FakeApi().status("ACC005", 500);
        String yaml = Files.readString(Path.of("chainrunner.example.yaml"))
                .replace("https://orders-dev.example.com", api.url())
                .replace("https://sso-dev.example.com/realms/example/protocol/openid-connect/token", api.url() + "/token")
                .replace("logDir: logs", "logDir: \"\"");
        ChainRunnerConfig config = new ConfigLoader(Map.of("DEV_USERNAME", "jdoe", "DEV_CLIENT_ID", "chainrunner")::get)
                .parse(Path.of("chainrunner.yaml"), yaml);
        app = new App(config, Theme.standard(), null, null,
                Map.of("accountIds", java.util.List.of("ACC001", "ACC002", "ACC003", "ACC004", "ACC005", "ACC006")),
                Http.client(Duration.ofSeconds(5), false), Clock.systemUTC(), true);
    }

    @AfterEach
    void stop() {
        api.close();
    }

    @Test
    void beforeTheRun() throws IOException {
        String text = shoot("ready", 150, 40);
        assertThat(text).contains("Generate ACC001", "DEV", "tokens", "orders", "REQUEST (preview)", "accountId = ACC001", "Variables");
    }

    @Test
    void aRunThatStopsOnAServerError() throws IOException {
        press(KeyEvent.ofChar('r'));
        assertThat(shoot("confirm", 150, 40)).contains("Run 6 steps?");
        press(KeyEvent.ofKey(KeyCode.ENTER));
        assertThat(shoot("password", 150, 40)).contains("Password", "jdoe");
        for (char c : "secret".toCharArray()) {
            press(KeyEvent.ofChar(c));
        }
        assertThat(shoot("password-typed", 150, 40)).contains("••••••").doesNotContain("secret");
        press(KeyEvent.ofKey(KeyCode.ENTER));

        assertThat(api.calls).hasSize(5);
        assertThat(app.state().count(com.anorys.chainrunner.core.CallResult.Status.SKIPPED)).isEqualTo(1);
        String text = shoot("done", 150, 40);
        assertThat(text).contains("4 ok, 1 failed", "HTTP 500", "tokens", "orders");
    }

    @Test
    void environmentsAndVariables() throws IOException {
        press(KeyEvent.ofChar('e'));
        assertThat(shoot("environments", 150, 40)).contains("Development", "Acceptance", "Production", "protected");
        press(KeyEvent.ofKey(KeyCode.DOWN));
        press(KeyEvent.ofKey(KeyCode.DOWN));
        press(KeyEvent.ofKey(KeyCode.ENTER));
        assertThat(app.state().env().name()).isEqualTo("prod");
        assertThat(app.state().variables()).containsEntry("notify", true);

        press(KeyEvent.ofChar('v'));
        press(KeyEvent.ofKey(KeyCode.ENTER));
        press(KeyEvent.ofChar('u', dev.tamboui.tui.event.KeyModifiers.CTRL));
        for (char c : "ACC9,ACC8".toCharArray()) {
            press(KeyEvent.ofChar(c));
        }
        shoot("edit-variable", 150, 40);
        press(KeyEvent.ofKey(KeyCode.ENTER));
        assertThat(app.state().calls()).hasSize(2);

        press(KeyEvent.ofChar('r'));
        assertThat(shoot("protected", 150, 40)).contains("type 'prod'");
    }

    @Test
    void smallestTerminal() throws IOException {
        assertThat(shoot("small", 80, 24)).contains("Generate ACC001", "chainrunner");
        assertThat(shoot(null, 60, 20)).contains("Make the terminal larger");
    }

    @Test
    void help() throws IOException {
        press(KeyEvent.ofChar('?'));
        assertThat(shoot("help", 150, 40)).contains("run the whole sequence");
    }

    private void press(KeyEvent key) {
        app.press(key);
    }

    private String shoot(String name, int width, int height) throws IOException {
        Buffer buffer = Buffer.empty(Rect.of(width, height));
        new MainView(Theme.standard()).render(Frame.forTesting(buffer), app.state(), Instant.now());
        String dir = System.getProperty("chainrunner.screenshots");
        if (dir != null && name != null) {
            Path out = Path.of(dir);
            Files.createDirectories(out);
            ExportRequest.export(buffer).svg().toFile(out.resolve(name + ".svg"));
        }
        StringBuilder text = new StringBuilder();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                text.append(buffer.get(x, y).symbol());
            }
            text.append('\n');
        }
        return text.toString();
    }
}
