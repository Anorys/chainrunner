package com.anorys.chainrunner.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Asks git for the password it stores for a host ({@code git credential fill}), so that a private
 * repository's releases can be read with the credentials git already uses, such as those of the Git
 * Credential Manager on Windows. Never prompts: no terminal prompt, no GCM window.
 */
final class GitCredentials {

    private static final long TIMEOUT_SECONDS = 10;

    private GitCredentials() {
    }

    static Optional<String> password(String host) {
        ProcessBuilder builder = new ProcessBuilder("git", "-c", "credential.interactive=never", "credential", "fill")
                .redirectErrorStream(false)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GCM_INTERACTIVE", "never");
        try {
            Process process = builder.start();
            try (OutputStream in = process.getOutputStream()) {
                in.write(("protocol=https\nhost=" + host + "\n\n").getBytes(StandardCharsets.UTF_8));
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream out = process.getInputStream()) {
                out.transferTo(output);
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                return Optional.empty();
            }
            return passwordFrom(output.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    static Optional<String> passwordFrom(String output) {
        return output.lines()
                .filter(line -> line.startsWith("password="))
                .map(line -> line.substring("password=".length()))
                .filter(password -> !password.isBlank())
                .findFirst();
    }
}
