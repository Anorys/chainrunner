package com.anorys.chainrunner.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SelfUpdaterTest {

    @TempDir
    Path tmp;

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static FakeReleases release(String version, String asset, byte[] content, String sums) {
        return new FakeReleases().add(version, Map.of(asset, content,
                Release.CHECKSUMS, sums.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void namesThePlatformExecutable() {
        ReleaseVersion v = ReleaseVersion.parse("0.2.0").orElseThrow();
        assertThat(SelfUpdater.assetName(v, "Windows 11", "amd64")).contains("chainrunner-0.2.0-windows-x64.exe");
        assertThat(SelfUpdater.assetName(v, "Linux", "amd64")).contains("chainrunner-0.2.0-linux-x64");
        assertThat(SelfUpdater.assetName(v, "Mac OS X", "aarch64")).contains("chainrunner-0.2.0-macos-arm64");
        assertThat(SelfUpdater.assetName(v, "Mac OS X", "x86_64")).isEmpty();
        assertThat(SelfUpdater.assetName(v, "Linux", "aarch64")).isEmpty();
    }

    @Test
    void readsSha256sums() {
        String sums = "abc123  chainrunner-0.2.0-linux-x64\nDEF456 *chainrunner-0.2.0-windows-x64.exe\n";
        assertThat(SelfUpdater.checksumFor(sums, "chainrunner-0.2.0-windows-x64.exe")).contains("def456");
        assertThat(SelfUpdater.checksumFor(sums, "chainrunner-0.2.0-linux-x64")).contains("abc123");
        assertThat(SelfUpdater.checksumFor(sums, "chainrunner-0.2.0")).isEmpty();
    }

    @Test
    void downloadsVerifiesAndReplaces() throws Exception {
        Path exe = Files.writeString(tmp.resolve("chainrunner.exe"), "old");
        byte[] content = "new".getBytes(StandardCharsets.UTF_8);
        String asset = "chainrunner-0.2.0-windows-x64.exe";
        FakeReleases source = release("0.2.0", asset, content, sha256(content) + "  " + asset + "\n");
        Release latest = source.latest();

        Path downloaded = SelfUpdater.download(source, latest, asset, exe);
        assertThat(downloaded.getFileName()).hasToString("chainrunner.update.exe");
        assertThat(exe).hasContent("old");

        SelfUpdater.replace(exe, downloaded);
        assertThat(exe).hasContent("new");
        assertThat(downloaded).doesNotExist();
        // The running executable is renamed, then deleted when the platform allows it.
        assertThat(tmp.resolve("chainrunner.exe.old")).doesNotExist();
    }

    @Test
    void refusesACorruptedDownload() throws Exception {
        Path exe = Files.writeString(tmp.resolve("chainrunner"), "old");
        String asset = "chainrunner-0.2.0-linux-x64";
        FakeReleases source = release("0.2.0", asset, "tampered".getBytes(StandardCharsets.UTF_8),
                sha256("genuine".getBytes(StandardCharsets.UTF_8)) + "  " + asset + "\n");

        assertThatThrownBy(() -> SelfUpdater.download(source, source.latest(), asset, exe))
                .isInstanceOf(UpdateException.class).hasMessageContaining("corrupted");
        assertThat(tmp.resolve("chainrunner.update")).doesNotExist();
        assertThat(exe).hasContent("old");
    }

    @Test
    void refusesAReleaseWithoutChecksums() throws IOException {
        Path exe = Files.writeString(tmp.resolve("chainrunner"), "old");
        FakeReleases source = new FakeReleases().add("0.2.0", Map.of("chainrunner-0.2.0-linux-x64", new byte[]{1}));

        assertThatThrownBy(() -> SelfUpdater.download(source, source.latest(), "chainrunner-0.2.0-linux-x64", exe))
                .isInstanceOf(UpdateException.class).hasMessageContaining("SHA256SUMS");
    }

    @Test
    void restoresTheExecutableWhenTheNewOneCannotTakeItsPlace() throws IOException {
        Path exe = Files.writeString(tmp.resolve("chainrunner"), "old");
        Path missing = tmp.resolve("chainrunner.update");

        assertThatThrownBy(() -> SelfUpdater.replace(exe, missing))
                .isInstanceOf(UpdateException.class).hasMessageContaining("nothing was changed");
        assertThat(exe).hasContent("old");
        assertThat(tmp.resolve("chainrunner.old")).doesNotExist();
    }
}
