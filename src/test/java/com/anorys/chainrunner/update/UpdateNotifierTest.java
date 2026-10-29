package com.anorys.chainrunner.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateNotifierTest {

    @TempDir
    Path tmp;

    private final Instant now = Instant.parse("2026-10-27T12:00:00Z");
    private final FakeReleases releases = new FakeReleases().add("0.2.0", Map.of());

    private UpdateCache cache() {
        return new UpdateCache(tmp.resolve("cache").resolve("update-check.properties"));
    }

    private Optional<UpdateNotifier> start(String version, boolean interactive, Map<String, String> env, Instant at) {
        return UpdateNotifier.start(version, interactive, env, cache(), Clock.fixed(at, ZoneOffset.UTC), v -> releases);
    }

    private static String finish(UpdateNotifier notifier) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        notifier.finish(new PrintStream(err, true, StandardCharsets.UTF_8), Function.identity());
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void looksUpTheLatestReleaseAndPrintsANotice() {
        UpdateNotifier notifier = start("0.1.0", true, Map.of(), now).orElseThrow();

        assertThat(finish(notifier)).contains("A new release of chainrunner is available:", "0.1.0", "0.2.0", "chainrunner self-update");
        assertThat(releases.lookups).isEqualTo(1);
        assertThat(cache().read().latest()).hasToString("0.2.0");
        assertThat(cache().read().lastCheck()).isEqualTo(now);
    }

    @Test
    void usesTheCacheForADay() {
        finish(start("0.1.0", true, Map.of(), now).orElseThrow());

        UpdateNotifier later = start("0.1.0", true, Map.of(), now.plus(Duration.ofHours(23))).orElseThrow();
        assertThat(later.lookingUp()).isFalse();
        assertThat(finish(later)).contains("0.2.0");
        assertThat(releases.lookups).isEqualTo(1);

        UpdateNotifier nextDay = start("0.1.0", true, Map.of(), now.plus(Duration.ofHours(25))).orElseThrow();
        assertThat(nextDay.lookingUp()).isTrue();
        finish(nextDay);
        assertThat(releases.lookups).isEqualTo(2);
    }

    @Test
    void saysNothingWhenUpToDate() {
        assertThat(finish(start("0.2.0", true, Map.of(), now).orElseThrow())).isEmpty();
    }

    @Test
    void aFailedLookupIsSilentAndNotRetriedTheSameDay() {
        FakeReleases offline = new FakeReleases();
        UpdateNotifier notifier = UpdateNotifier.start("0.1.0", true, Map.of(), cache(),
                Clock.fixed(now, ZoneOffset.UTC), v -> offline).orElseThrow();

        assertThat(finish(notifier)).isEmpty();
        assertThat(offline.lookups).isEqualTo(1);
        assertThat(start("0.1.0", true, Map.of(), now.plusSeconds(60)).orElseThrow().lookingUp()).isFalse();
    }

    @Test
    void isOffForScriptsCiDevelopmentBuildsAndOnRequest() {
        assertThat(start("0.1.0", false, Map.of(), now)).isEmpty();
        assertThat(start("0.1.0", true, Map.of("CI", "true"), now)).isEmpty();
        assertThat(start("0.1.0", true, Map.of("CHAINRUNNER_NO_UPDATE_CHECK", "1"), now)).isEmpty();
        assertThat(start("0.2.0-SNAPSHOT", true, Map.of(), now)).isEmpty();
        assertThat(start("unknown", true, Map.of(), now)).isEmpty();
        assertThat(releases.lookups).isZero();
        finish(start("0.1.0", true, Map.of("CHAINRUNNER_NO_UPDATE_CHECK", "0"), now).orElseThrow());
    }
}
