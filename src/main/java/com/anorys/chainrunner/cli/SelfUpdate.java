package com.anorys.chainrunner.cli;

import com.anorys.chainrunner.Main;
import com.anorys.chainrunner.update.GitHubReleases;
import com.anorys.chainrunner.update.Release;
import com.anorys.chainrunner.update.ReleaseSource;
import com.anorys.chainrunner.update.ReleaseVersion;
import com.anorys.chainrunner.update.SelfUpdater;
import com.anorys.chainrunner.update.UpdateCache;
import com.anorys.chainrunner.update.UpdateException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@code chainrunner self-update}: replaces this executable with the latest release from GitHub (or the
 * one given with {@code --to}), after checking its SHA-256 and that it starts. Only for the native
 * executable, not the jar.
 */
public final class SelfUpdate {

    private final PrintStream out;
    private final String currentText;
    /** Replaced in tests. */
    ReleaseSource source;
    Path executable;
    UpdateCache cache;

    public SelfUpdate(PrintStream out, String currentVersion) {
        this.out = out;
        this.currentText = currentVersion;
    }

    public static final String HELP = """
            Usage: chainrunner self-update [--check] [--to X.Y.Z] [--force]

            Replaces this chainrunner executable with the latest release from GitHub (or the one
            given with --to), after checking its SHA-256. Only for the native executable, not the jar.

                  --check      only say whether a newer release exists
                  --to X.Y.Z   install this version (also an older one)
                  --force      install even when this version is already the latest

            Behind a proxy, set HTTPS_PROXY. CHAINRUNNER_UPDATE_URL points to a mirror of the GitHub
            releases API; CHAINRUNNER_GITHUB_TOKEN (or GH_TOKEN) is sent to it when set.
            """;

    /** Runs the command with its arguments (after {@code self-update}); returns the exit code. */
    public int run(String[] args, PrintStream err) {
        boolean check = false;
        boolean force = false;
        String to = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--check" -> check = true;
                case "--force" -> force = true;
                case "--to" -> {
                    if (i + 1 >= args.length) {
                        err.println("chainrunner: --to expects a version");
                        return Main.USAGE;
                    }
                    to = args[++i];
                }
                case "-h", "--help" -> {
                    out.print(HELP);
                    return Main.OK;
                }
                default -> {
                    if (args[i].startsWith("--to=")) {
                        to = args[i].substring(5);
                    } else {
                        err.println("chainrunner: unknown option '" + args[i] + "' for self-update");
                        return Main.USAGE;
                    }
                }
            }
        }
        try {
            return update(check, force, to);
        } catch (UpdateException e) {
            err.println("chainrunner: " + e.getMessage());
            return Main.CALLS_FAILED;
        }
    }

    int update(boolean check, boolean force, String to) {
        Optional<ReleaseVersion> current = ReleaseVersion.parse(currentText);
        ReleaseSource releases = source != null ? source
                : GitHubReleases.fromEnvironment(System.getenv(), currentText, Duration.ofSeconds(20));

        Release release = lookUp(releases, to);
        if (to == null) {
            (cache != null ? cache : UpdateCache.forUser(System.getenv())).record(release, Instant.now());
        }
        boolean newer = current.map(version -> release.version().isNewerThan(version)).orElse(true);
        out.println("Installed: chainrunner " + currentText);
        out.println((to == null ? "Latest:    chainrunner " : "Requested: chainrunner ") + release.version()
                + (release.page() == null ? "" : "  " + release.page()));
        if (check) {
            out.println(newer ? "A newer release is available: run 'chainrunner self-update'." : "chainrunner is up to date.");
            return Main.OK;
        }
        boolean same = current.map(version -> version.compareTo(release.version()) == 0).orElse(false);
        if ((to == null && !newer && !force) || (same && !force)) {
            out.println("chainrunner is up to date.");
            return Main.OK;
        }

        Path exe = executable != null ? executable : SelfUpdater.currentExecutable().orElseThrow(() -> new UpdateException(
                "chainrunner is running on a Java runtime, not as the native executable, so it cannot replace itself; "
                        + "download the release from " + release.page()));
        String assetName = SelfUpdater.assetName(release.version(), System.getProperty("os.name", ""),
                System.getProperty("os.arch", "")).orElseThrow(() -> new UpdateException(
                "releases have no executable for " + System.getProperty("os.name") + " "
                        + System.getProperty("os.arch") + "; build chainrunner from source"));

        Release.Asset asset = release.asset(assetName).orElse(null);
        out.println("Downloading " + assetName + (asset != null && asset.size() > 0
                ? String.format(Locale.ROOT, " (%.1f MB)", asset.size() / 1_048_576.0) : "") + "...");
        Path downloaded = SelfUpdater.download(releases, release, assetName, exe);
        out.println("SHA-256 verified.");
        if (executable == null) {
            checkRuns(downloaded, release.version());
        }
        SelfUpdater.replace(exe, downloaded);
        out.println("chainrunner " + release.version() + " installed in " + exe);
        return Main.OK;
    }

    private static Release lookUp(ReleaseSource releases, String to) {
        try {
            if (to == null) {
                return releases.latest();
            }
            ReleaseVersion version = ReleaseVersion.parse(to).filter(ReleaseVersion::isRelease)
                    .orElseThrow(() -> new UpdateException("'" + to + "' is not a release version (X.Y.Z)"));
            return releases.release(version);
        } catch (IOException e) {
            throw new UpdateException("cannot look up the chainrunner releases: " + e.getMessage()
                    + "; behind a proxy, set HTTPS_PROXY", e);
        }
    }

    /** Runs the new executable once before it replaces this one, so that a broken download is never installed. */
    private static void checkRuns(Path downloaded, ReleaseVersion expected) {
        String output;
        int exit;
        boolean timedOut = false;
        try {
            Process process = new ProcessBuilder(downloaded.toString(), "--version").redirectErrorStream(true).start();
            process.getOutputStream().close();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                in.transferTo(bytes);
            }
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                timedOut = true;
            }
            output = bytes.toString(StandardCharsets.UTF_8);
            exit = timedOut ? -1 : process.exitValue();
        } catch (IOException e) {
            output = e.getMessage();
            exit = -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            output = "interrupted";
            exit = -1;
        }
        if (exit != 0 || !output.contains("chainrunner " + expected)) {
            try {
                Files.deleteIfExists(downloaded);
            } catch (IOException e) {
                // Left behind; harmless.
            }
            throw new UpdateException("the downloaded chainrunner " + expected + " does not start ("
                    + (timedOut ? "timed out" : "exit code " + exit) + "): " + output.strip() + "; nothing was changed");
        }
    }
}
