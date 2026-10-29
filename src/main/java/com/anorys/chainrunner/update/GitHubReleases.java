package com.anorys.chainrunner.update;

import com.anorys.chainrunner.core.Tls;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Reads releases from the GitHub REST API (or a mirror of it, with {@code CHAINRUNNER_UPDATE_URL}). Parsed with
 * Jackson's tree model, so nothing needs registering for the native image.
 *
 * <p>Authentication, needed for a private repository: {@code CHAINRUNNER_GITHUB_TOKEN}, {@code GH_TOKEN} or
 * {@code GITHUB_TOKEN} when set; otherwise, when GitHub answers 404 or 401, the password git stores for
 * {@code github.com} ({@code git credential fill}, never prompting). The token is only sent to the API host:
 * asset downloads are redirected to a storage host, and the redirects are followed without it.
 *
 * <p>Proxies: the JDK's {@code https.proxyHost}/{@code https.proxyPort} system properties are honoured
 * (pass them as {@code -D} options, also to the native executable); otherwise {@code HTTPS_PROXY} is used
 * when set. An internal certificate authority is added with {@code -Djavax.net.ssl.trustStore=...}.
 */
public final class GitHubReleases implements ReleaseSource {

    public static final String DEFAULT_API = "https://api.github.com/repos/anorys/chainrunner/releases";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(10);
    private static final int MAX_REDIRECTS = 5;

    private final URI api;
    private final String userAgent;
    private final Duration timeout;
    private final HttpClient http;
    private final Supplier<Optional<String>> fallbackToken;
    private String token;
    private boolean fallbackTried;

    /**
     * @param api           the releases endpoint, without a trailing slash
     * @param token         a GitHub token, or null
     * @param fallbackToken where to find a token when the API answers 404 or 401 without one
     * @param timeout       the connection and request timeout of API calls
     */
    public GitHubReleases(URI api, String currentVersion, String token, Supplier<Optional<String>> fallbackToken,
                          Duration timeout) {
        this.api = api;
        this.userAgent = "chainrunner/" + currentVersion;
        this.token = token == null || token.isBlank() ? null : token.strip();
        this.fallbackToken = fallbackToken;
        this.timeout = timeout;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .sslContext(Tls.context());
        proxyFromEnvironment(System.getenv()).ifPresent(builder::proxy);
        this.http = builder.build();
    }

    /** The source configured by the environment: {@code CHAINRUNNER_UPDATE_URL} and the tokens. */
    public static GitHubReleases fromEnvironment(Map<String, String> env, String currentVersion, Duration timeout) {
        String url = env.getOrDefault("CHAINRUNNER_UPDATE_URL", "");
        URI api = URI.create(url.isBlank() ? DEFAULT_API : url.strip().replaceAll("/+$", ""));
        String token = Stream.of("CHAINRUNNER_GITHUB_TOKEN", "GH_TOKEN", "GITHUB_TOKEN")
                .map(env::get)
                .filter(value -> value != null && !value.isBlank())
                .findFirst().orElse(null);
        String gitHost = "api.github.com".equals(api.getHost()) ? "github.com" : api.getHost();
        return new GitHubReleases(api, currentVersion, token, () -> GitCredentials.password(gitHost), timeout);
    }

    @Override
    public Release latest() throws IOException {
        return parse(getJson(URI.create(api + "/latest")));
    }

    @Override
    public Release release(ReleaseVersion version) throws IOException {
        return parse(getJson(URI.create(api + "/tags/v" + version)));
    }

    @Override
    public void download(Release.Asset asset, Path target) throws IOException {
        HttpResponse<Path> response = send(asset.url(), DOWNLOAD_TIMEOUT, "application/octet-stream",
                HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(target);
            throw new IOException("HTTP " + response.statusCode() + " downloading " + asset.url());
        }
    }

    @Override
    public String read(Release.Asset asset) throws IOException {
        HttpResponse<byte[]> response = send(asset.url(), timeout, "application/octet-stream",
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " downloading " + asset.url());
        }
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    /** Maps a GitHub release object ({@code tag_name}, {@code html_url}, {@code assets[]}). */
    static Release parse(JsonNode release) throws IOException {
        String tag = release.path("tag_name").asText("");
        ReleaseVersion version = ReleaseVersion.parse(tag)
                .orElseThrow(() -> new IOException("the release tag '" + tag + "' is not a version"));
        Map<String, Release.Asset> assets = new HashMap<>();
        for (JsonNode asset : release.path("assets")) {
            String name = asset.path("name").asText("");
            // The API address works for private repositories too; the browser address needs a web session.
            String url = asset.path("url").asText(asset.path("browser_download_url").asText(""));
            if (!name.isEmpty() && !url.isEmpty()) {
                assets.put(name, new Release.Asset(name, URI.create(url), asset.path("size").asLong(-1)));
            }
        }
        String page = release.path("html_url").asText("");
        return new Release(version, page.isEmpty() ? null : URI.create(page), assets);
    }

    private JsonNode getJson(URI uri) throws IOException {
        HttpResponse<byte[]> response = send(uri, timeout, "application/vnd.github+json",
                HttpResponse.BodyHandlers.ofByteArray());
        int status = response.statusCode();
        if (status == 404 || status == 401) {
            throw new IOException((status == 404 ? "no such release, or no access to it: " + uri
                    : "GitHub refused the credentials (HTTP 401)")
                    + "; for a private repository, set CHAINRUNNER_GITHUB_TOKEN or sign in to github.com with git");
        }
        if (status == 403 || status == 429) {
            throw new IOException("GitHub refused the request (HTTP " + status
                    + "), probably its rate limit; try again later or set CHAINRUNNER_GITHUB_TOKEN");
        }
        if (status != 200) {
            throw new IOException("HTTP " + status + " from " + uri);
        }
        return JSON.readTree(response.body());
    }

    /**
     * Sends a GET, retrying once with the fallback token after a 404 or 401 from the API, and following
     * redirects by hand so that the token never leaves the API host.
     */
    private <T> HttpResponse<T> send(URI uri, Duration requestTimeout, String accept,
                                     HttpResponse.BodyHandler<T> handler) throws IOException {
        HttpResponse<T> response = follow(uri, requestTimeout, accept, handler);
        int status = response.statusCode();
        if ((status == 404 || status == 401) && onApiHost(uri) && useFallbackToken()) {
            response = follow(uri, requestTimeout, accept, handler);
        }
        return response;
    }

    private synchronized boolean useFallbackToken() {
        if (fallbackTried) {
            return false;
        }
        fallbackTried = true;
        Optional<String> found = fallbackToken.get().filter(value -> !value.equals(token));
        found.ifPresent(value -> token = value);
        return found.isPresent();
    }

    private <T> HttpResponse<T> follow(URI uri, Duration requestTimeout, String accept,
                                       HttpResponse.BodyHandler<T> handler) throws IOException {
        URI current = uri;
        for (int redirects = 0; ; redirects++) {
            HttpRequest.Builder request = HttpRequest.newBuilder(current)
                    .timeout(requestTimeout)
                    .header("Accept", accept)
                    .header("User-Agent", userAgent)
                    .GET();
            String bearer = currentToken();
            if (bearer != null && onApiHost(current)) {
                request.header("Authorization", "Bearer " + bearer);
            }
            HttpResponse<T> response;
            try {
                response = http.send(request.build(), handler);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            int status = response.statusCode();
            Optional<String> location = response.headers().firstValue("Location");
            if (status < 300 || status >= 400 || location.isEmpty() || redirects >= MAX_REDIRECTS) {
                return response;
            }
            current = current.resolve(location.get());
            if ("https".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(current.getScheme())) {
                throw new IOException("refusing to follow a redirect away from https: " + current);
            }
        }
    }

    private synchronized String currentToken() {
        return token;
    }

    private boolean onApiHost(URI uri) {
        return api.getHost().equalsIgnoreCase(uri.getHost());
    }

    /**
     * {@code HTTPS_PROXY} (or {@code https_proxy}), unless the JDK proxy properties are set. Hosts listed
     * in {@code NO_PROXY} (exact names, {@code .suffix} or {@code *}) and the loopback addresses are
     * reached directly.
     */
    static Optional<ProxySelector> proxyFromEnvironment(Map<String, String> env) {
        if (System.getProperty("https.proxyHost") != null) {
            return Optional.empty();
        }
        String value = env.getOrDefault("HTTPS_PROXY", env.getOrDefault("https_proxy", ""));
        if (value.isBlank()) {
            return Optional.empty();
        }
        try {
            URI proxy = URI.create(value.contains("://") ? value.strip() : "http://" + value.strip());
            if (proxy.getHost() == null) {
                return Optional.empty();
            }
            int port = proxy.getPort() != -1 ? proxy.getPort() : "https".equals(proxy.getScheme()) ? 443 : 80;
            Proxy through = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxy.getHost(), port));
            List<String> direct = Stream.of(env.getOrDefault("NO_PROXY", env.getOrDefault("no_proxy", "")).split(","))
                    .map(String::strip).filter(s -> !s.isEmpty()).map(s -> s.toLowerCase(Locale.ROOT)).toList();
            return Optional.of(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    return List.of(bypass(uri.getHost(), direct) ? Proxy.NO_PROXY : through);
                }

                @Override
                public void connectFailed(URI uri, SocketAddress address, IOException e) {
                    // Nothing to remember: the request fails and says why.
                }
            });
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Whether {@code host} is reached without the proxy: a loopback address or a {@code NO_PROXY} entry. */
    static boolean bypass(String host, List<String> noProxy) {
        if (host == null) {
            return true;
        }
        String h = host.toLowerCase(Locale.ROOT).replaceAll("^\\[|]$", "");
        if (h.equals("localhost") || h.startsWith("127.") || h.equals("::1")) {
            return true;
        }
        for (String entry : noProxy) {
            String suffix = entry.startsWith("*.") ? entry.substring(1) : entry;
            if (entry.equals("*") || h.equals(suffix.replaceFirst("^\\.", "")) || suffix.startsWith(".") && h.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
