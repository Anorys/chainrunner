package com.anorys.chainrunner.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubReleasesTest {

    @TempDir
    Path tmp;

    private HttpServer server;
    /** "path authorization" of every request received. */
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void parsesAGitHubRelease() throws IOException {
        String json = """
                {"tag_name": "v0.2.0", "html_url": "https://github.com/o/r/releases/tag/v0.2.0",
                 "assets": [{"name": "SHA256SUMS", "size": 120,
                             "url": "https://api.github.com/repos/o/r/releases/assets/7",
                             "browser_download_url": "https://github.com/o/r/releases/download/v0.2.0/SHA256SUMS"}]}
                """;
        Release release = GitHubReleases.parse(new ObjectMapper().readTree(json));

        assertThat(release.version()).hasToString("0.2.0");
        assertThat(release.page()).hasToString("https://github.com/o/r/releases/tag/v0.2.0");
        assertThat(release.asset("SHA256SUMS").orElseThrow().url())
                .hasToString("https://api.github.com/repos/o/r/releases/assets/7");
    }

    @Test
    void retriesWithGitCredentialsAndNeverSendsTheTokenToTheStorageHost() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String path = exchange.getRequestURI().getPath();
            requests.add(path + " " + auth);
            if (auth == null && path.startsWith("/repos")) {
                respond(exchange, 404, "{}");
            } else if (path.equals("/repos/o/r/releases/latest")) {
                respond(exchange, 200, """
                        {"tag_name": "v0.3.0", "assets": [{"name": "SHA256SUMS", "size": 4,
                         "url": "http://127.0.0.1:%d/repos/o/r/releases/assets/1"}]}""".formatted(port));
            } else if (path.equals("/repos/o/r/releases/assets/1")) {
                exchange.getResponseHeaders().add("Location", "http://localhost:" + port + "/storage/sums");
                respond(exchange, 302, "");
            } else if (path.equals("/storage/sums")) {
                respond(exchange, 200, "sums");
            } else {
                respond(exchange, 500, "");
            }
        });
        server.start();
        GitHubReleases releases = new GitHubReleases(URI.create("http://127.0.0.1:" + port + "/repos/o/r/releases"),
                "0.1.0", null, () -> Optional.of("from-git"), Duration.ofSeconds(5));

        Release latest = releases.latest();
        assertThat(latest.version()).hasToString("0.3.0");
        assertThat(releases.read(latest.asset("SHA256SUMS").orElseThrow())).isEqualTo("sums");
        assertThat(requests).containsExactly(
                "/repos/o/r/releases/latest null",
                "/repos/o/r/releases/latest Bearer from-git",
                "/repos/o/r/releases/assets/1 Bearer from-git",
                "/storage/sums null");
    }

    @Test
    void explainsAMissingAccess() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respond(exchange, 404, "{}"));
        server.start();
        GitHubReleases releases = new GitHubReleases(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/repos/o/r/releases"),
                "0.1.0", null, Optional::empty, Duration.ofSeconds(5));

        assertThatThrownBy(releases::latest).hasMessageContaining("CHAINRUNNER_GITHUB_TOKEN");
    }

    @Test
    void readsTheProxyFromTheEnvironment() {
        String saved = System.getProperty("https.proxyHost");
        System.clearProperty("https.proxyHost");
        try {
            assertThat(GitHubReleases.proxyFromEnvironment(Map.of("HTTPS_PROXY", "http://proxy.corp:3128"))).isPresent();
            assertThat(GitHubReleases.proxyFromEnvironment(Map.of("https_proxy", "proxy.corp:8080"))).isPresent();
            assertThat(GitHubReleases.proxyFromEnvironment(Map.of())).isEmpty();
        } finally {
            if (saved != null) {
                System.setProperty("https.proxyHost", saved);
            }
        }
    }

    @Test
    void reachesLoopbackAndNoProxyHostsDirectly() {
        List<String> noProxy = List.of(".corp.example", "mirror.local");
        assertThat(GitHubReleases.bypass("127.0.0.1", noProxy)).isTrue();
        assertThat(GitHubReleases.bypass("localhost", noProxy)).isTrue();
        assertThat(GitHubReleases.bypass("git.corp.example", noProxy)).isTrue();
        assertThat(GitHubReleases.bypass("mirror.local", noProxy)).isTrue();
        assertThat(GitHubReleases.bypass("api.github.com", noProxy)).isFalse();
        assertThat(GitHubReleases.bypass("api.github.com", List.of("*"))).isTrue();
    }

    @Test
    void readsTheGitCredentialOutput() {
        assertThat(GitCredentials.passwordFrom("protocol=https\nhost=github.com\nusername=me\npassword=gho_x\n"))
                .contains("gho_x");
        assertThat(GitCredentials.passwordFrom("protocol=https\n")).isEmpty();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
