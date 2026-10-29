package com.anorys.chainrunner.update;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Remembers when the latest release was last looked up, and what it was, so that the lookup happens at
 * most once a day. Stored per user ({@code %LOCALAPPDATA%\chainrunner} on Windows, {@code $XDG_CACHE_HOME/chainrunner} or
 * {@code ~/.cache/chainrunner} elsewhere). Every failure to read or write is ignored: the check is a courtesy.
 */
public final class UpdateCache {

    private static final String FILE = "update-check.properties";

    private final Path file;

    public UpdateCache(Path file) {
        this.file = file;
    }

    public static UpdateCache forUser(Map<String, String> env) {
        return new UpdateCache(directory(env).resolve(FILE));
    }

    static Path directory(Map<String, String> env) {
        String localAppData = env.get("LOCALAPPDATA");
        if (SelfUpdater.isWindows() && localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, "chainrunner");
        }
        String xdg = env.get("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg, "chainrunner");
        }
        return Path.of(System.getProperty("user.home", ".")).resolve(".cache").resolve("chainrunner");
    }

    /** What the last lookup found: when it was attempted, and the latest version known (maybe from an older lookup). */
    public record State(Instant lastCheck, ReleaseVersion latest, String page) {

        static final State EMPTY = new State(Instant.EPOCH, null, null);

        public Optional<ReleaseVersion> latestVersion() {
            return Optional.ofNullable(latest);
        }
    }

    public State read() {
        if (!Files.isRegularFile(file)) {
            return State.EMPTY;
        }
        Properties properties = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(in);
            Instant lastCheck = Instant.ofEpochMilli(Long.parseLong(properties.getProperty("lastCheck", "0")));
            ReleaseVersion latest = ReleaseVersion.parse(properties.getProperty("latestVersion")).orElse(null);
            return new State(lastCheck, latest, properties.getProperty("latestPage"));
        } catch (IOException | RuntimeException e) {
            return State.EMPTY;
        }
    }

    public void write(State state) {
        Properties properties = new Properties();
        properties.setProperty("lastCheck", Long.toString(state.lastCheck().toEpochMilli()));
        if (state.latest() != null) {
            properties.setProperty("latestVersion", state.latest().toString());
        }
        if (state.page() != null) {
            properties.setProperty("latestPage", state.page());
        }
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(FILE + "." + ProcessHandle.current().pid() + ".tmp");
            try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                properties.store(out, "chainrunner: latest release, looked up at most once a day");
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            // Not worth bothering the user: the next run tries again.
        }
    }

    /** Records a successful lookup. */
    public void record(Release release, Instant now) {
        write(new State(now, release.version(), release.page() == null ? null : release.page().toString()));
    }
}
