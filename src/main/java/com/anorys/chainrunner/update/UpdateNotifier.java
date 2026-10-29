package com.anorys.chainrunner.update;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Tells the user, after a command, that a newer chainrunner is released, the way pip does. The latest release is
 * looked up at most once a day, in a background thread started before the command, with a short timeout;
 * a failed lookup is retried the next day and never reported. Other runs use the cached answer.
 *
 * <p>Off when {@code CHAINRUNNER_NO_UPDATE_CHECK} is set, in CI ({@code CI} is set), without a terminal (scripts,
 * pipes), for development builds, and with {@code --no-update-check}.
 */
public final class UpdateNotifier {

    /** How often the latest release is looked up. */
    public static final Duration INTERVAL = Duration.ofHours(24);
    /** Connection and request timeout of the lookup. */
    public static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(3);
    /** How long the end of a command may wait for a lookup still running. */
    static final Duration MAX_WAIT = Duration.ofSeconds(2);

    private final ReleaseVersion current;
    private final UpdateCache cache;
    private final Clock clock;
    private final UpdateCache.State cached;
    private final Thread lookup;
    private volatile Release found;

    private UpdateNotifier(ReleaseVersion current, UpdateCache cache, Clock clock, UpdateCache.State cached,
                           Supplier<ReleaseSource> source) {
        this.current = current;
        this.cache = cache;
        this.clock = clock;
        this.cached = cached;
        if (Duration.between(cached.lastCheck(), clock.instant()).compareTo(INTERVAL) < 0) {
            this.lookup = null;
            return;
        }
        // Recorded first, so that a failing lookup (offline, proxy) is not retried before tomorrow.
        cache.write(new UpdateCache.State(clock.instant(), cached.latest(), cached.page()));
        this.lookup = new Thread(() -> {
            try {
                Release release = source.get().latest();
                found = release;
                cache.record(release, clock.instant());
            } catch (Exception e) {
                // Offline, blocked by a proxy, rate-limited: try again tomorrow, silently.
            }
        }, "chainrunner-update-check");
        lookup.setDaemon(true);
        lookup.start();
    }

    /**
     * Starts the check when it applies, or returns empty.
     *
     * @param interactive whether the user sees the output in a terminal
     */
    public static Optional<UpdateNotifier> start(String currentVersion, boolean interactive, Map<String, String> env,
                                                 UpdateCache cache, Clock clock,
                                                 Function<String, ReleaseSource> source) {
        if (!interactive || isSet(env.get("CHAINRUNNER_NO_UPDATE_CHECK")) || isSet(env.get("CI"))) {
            return Optional.empty();
        }
        Optional<ReleaseVersion> current = ReleaseVersion.parse(currentVersion).filter(ReleaseVersion::isRelease);
        return current.map(version -> new UpdateNotifier(version, cache, clock, cache.read(),
                () -> source.apply(currentVersion)));
    }

    /** Waits briefly for a running lookup, then prints the notice if a newer release is known. */
    public void finish(PrintStream err, Function<String, String> highlight) {
        if (lookup != null) {
            try {
                lookup.join(MAX_WAIT.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        notice(highlight).ifPresent(text -> {
            err.println();
            err.println(text);
            err.flush();
        });
    }

    /** The notice, {@code highlight} applied to its key parts (colors), or empty when up to date. */
    public Optional<String> notice(Function<String, String> highlight) {
        Release release = found;
        ReleaseVersion latest = release != null ? release.version() : cached.latest();
        if (latest == null || !latest.isNewerThan(current)) {
            return Optional.empty();
        }
        return Optional.of("A new release of chainrunner is available: " + current + " -> " + highlight.apply(latest.toString())
                + String.format("%n") + "To update, run: " + highlight.apply("chainrunner self-update"));
    }

    boolean lookingUp() {
        return lookup != null;
    }

    static boolean isSet(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return !normalized.equals("0") && !normalized.equals("false") && !normalized.equals("no");
    }
}
