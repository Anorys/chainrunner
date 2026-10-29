package com.anorys.chainrunner.update;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseVersionTest {

    private static ReleaseVersion v(String text) {
        return ReleaseVersion.parse(text).orElseThrow();
    }

    @Test
    void parsesVersionsAndTags() {
        assertThat(v("v0.2.10")).isEqualTo(new ReleaseVersion(0, 2, 10, null));
        assertThat(v("1.0.0-SNAPSHOT").qualifier()).isEqualTo("SNAPSHOT");
        assertThat(v("1.0.0-SNAPSHOT").isRelease()).isFalse();
        assertThat(ReleaseVersion.parse("unknown")).isEmpty();
        assertThat(ReleaseVersion.parse("1.2")).isEmpty();
        assertThat(ReleaseVersion.parse(null)).isEmpty();
    }

    @Test
    void comparesNumericallyWithQualifiersFirst() {
        assertThat(v("0.10.0").isNewerThan(v("0.9.9"))).isTrue();
        assertThat(v("0.1.1").isNewerThan(v("0.1.0"))).isTrue();
        assertThat(v("0.1.0").isNewerThan(v("0.1.0-SNAPSHOT"))).isTrue();
        assertThat(v("0.1.0").isNewerThan(v("0.1.0"))).isFalse();
        assertThat(v("v1.0.0").toString()).isEqualTo("1.0.0");
    }
}
