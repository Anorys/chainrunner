package com.anorys.chainrunner.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * Replaces the running native executable with the one of a release: download next to it, check its
 * SHA-256 against the release's {@code SHA256SUMS}, then swap the files.
 *
 * <p>Windows cannot overwrite or delete a running executable, but it can rename it. So the running file is
 * renamed to {@code chainrunner.exe.old}, the new one takes its name, and the old one is deleted by the next run
 * ({@link #cleanUp}). If the second step fails, the first is undone.
 */
public final class SelfUpdater {

    static final String OLD_SUFFIX = ".old";
    
    private SelfUpdater() {
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** The name of this platform's executable in a release, or empty when releases have none for it. */
    public static Optional<String> assetName(ReleaseVersion version, String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = osArch.toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        if (os.startsWith("windows") && x64) {
            return Optional.of("chainrunner-" + version + "-windows-x64.exe");
        }
        if (os.startsWith("linux") && x64) {
            return Optional.of("chainrunner-" + version + "-linux-x64");
        }
        if (os.startsWith("mac") && arm64) {
            return Optional.of("chainrunner-" + version + "-macos-arm64");
        }
        return Optional.empty();
    }

    /** The running native executable, or empty when chainrunner runs on a JVM (from the jar, or an IDE). */
    public static Optional<Path> currentExecutable() {
        if (System.getProperty("org.graalvm.nativeimage.imagecode") == null) {
            return Optional.empty();
        }
        return ProcessHandle.current().info().command().map(command -> Path.of(command).toAbsolutePath());
    }

    /** Deletes what a previous update left next to the executable. Silent: it may still be running. */
    public static void cleanUp() {
        currentExecutable().ifPresent(exe -> {
            try {
                Files.deleteIfExists(sibling(exe, OLD_SUFFIX));
            } catch (IOException | RuntimeException e) {
                // Still locked: the next run tries again.
            }
        });
    }

    /** The SHA-256 that a {@code sha256sum} output gives for {@code fileName}. */
    public static Optional<String> checksumFor(String sha256sums, String fileName) {
        for (String line : sha256sums.split("\\R")) {
            String[] parts = line.strip().split("\\s+", 2);
            if (parts.length == 2 && parts[1].replaceFirst("^\\*", "").equals(fileName)) {
                return Optional.of(parts[0].toLowerCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }

    public static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Downloads the release's executable next to {@code exe} and verifies its checksum.
     *
     * @return the downloaded file, ready for {@link #replace}
     */
    public static Path download(ReleaseSource source, Release release, String assetName, Path exe) {
        Release.Asset asset = release.asset(assetName).orElseThrow(() -> new UpdateException(
                "release " + release.version() + " has no " + assetName + "; download chainrunner by hand from "
                        + release.page()));
        Release.Asset sums = release.asset(Release.CHECKSUMS).orElseThrow(() -> new UpdateException(
                "release " + release.version() + " has no " + Release.CHECKSUMS
                        + ", so the download cannot be verified; download chainrunner by hand from " + release.page()));
        Path target = downloadTarget(exe);
        try {
            String expected = checksumFor(source.read(sums), assetName).orElseThrow(() -> new UpdateException(
                    Release.CHECKSUMS + " of release " + release.version() + " has no line for " + assetName));
            source.download(asset, target);
            String actual = sha256(target);
            if (!actual.equals(expected)) {
                Files.deleteIfExists(target);
                throw new UpdateException("the downloaded " + assetName + " is corrupted (SHA-256 " + actual
                        + ", expected " + expected + "); nothing was changed, try again");
            }
            if (!isWindows()) {
                Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
            return target;
        } catch (AccessDeniedException e) {
            throw new UpdateException("cannot write to " + exe.getParent() + ": " + e.getMessage()
                    + "; run the update from an account that can, or download chainrunner by hand from " + release.page(), e);
        } catch (IOException e) {
            deleteQuietly(target);
            throw new UpdateException("cannot download " + assetName + ": " + e.getMessage(), e);
        }
    }

    /** Puts {@code downloaded} in place of {@code exe}, leaving the old executable as {@code <exe>.old}. */
    public static void replace(Path exe, Path downloaded) {
        Path old = sibling(exe, OLD_SUFFIX);
        try {
            Files.deleteIfExists(old);
            Files.move(exe, old, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(downloaded);
            throw new UpdateException("cannot replace " + exe + ": " + e.getMessage()
                    + (e instanceof AccessDeniedException ? " (access denied)" : ""), e);
        }
        try {
            Files.move(downloaded, exe);
        } catch (IOException e) {
            try {
                Files.move(old, exe);
            } catch (IOException restore) {
                e.addSuppressed(restore);
                throw new UpdateException("cannot replace " + exe + " (" + e.getMessage()
                        + "), and cannot restore it either: rename " + old + " back to " + exe.getFileName(), e);
            }
            deleteQuietly(downloaded);
            throw new UpdateException("cannot replace " + exe + ": " + e.getMessage() + "; nothing was changed", e);
        }
        // Works where the running file can be deleted (Linux); on Windows the next run does it.
        deleteQuietly(old);
    }

    /** {@code chainrunner.update.exe} next to {@code chainrunner.exe}: Windows only starts it with its {@code .exe} extension. */
    static Path downloadTarget(Path exe) {
        String name = exe.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(".exe")
                ? exe.resolveSibling(name.substring(0, name.length() - 4) + ".update.exe")
                : exe.resolveSibling(name + ".update");
    }

    static Path sibling(Path exe, String suffix) {
        return exe.resolveSibling(exe.getFileName() + suffix);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // Left behind; harmless.
        }
    }
}
