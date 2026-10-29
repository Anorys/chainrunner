package com.anorys.chainrunner.core;

import com.anorys.chainrunner.config.ChainRunnerConfig.Auth;
import com.anorys.chainrunner.config.ChainRunnerConfig.GrantType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Gets an OAuth2 access token from a token endpoint (Keycloak's {@code .../protocol/openid-connect/token}),
 * keeps it until shortly before it expires, then refreshes it with the refresh token or logs in again.
 */
public final class TokenClient {

    /** A token is renewed this long before it expires, so that it does not expire during a call. */
    private static final Duration MARGIN = Duration.ofSeconds(30);

    private final HttpClient http;
    private final Duration timeout;
    private final Clock clock;
    private volatile Auth auth;
    private volatile Token token;

    public TokenClient(HttpClient http, Auth auth, Duration timeout, Clock clock) {
        this.http = http;
        this.auth = auth;
        this.timeout = timeout;
        this.clock = clock;
    }

    public Auth auth() {
        return auth;
    }

    /** The password typed by the user, when the configuration has none. */
    public void password(String password) {
        auth = auth.withPassword(password);
        token = null;
    }

    public Token current() {
        return token;
    }

    public void invalidate() {
        Token t = token;
        if (t != null) {
            token = new Token(null, t.refreshToken(), Instant.EPOCH, t.refreshExpiresAt(), t.obtainedAt());
        }
    }

    public void forget() {
        token = null;
    }

    /** The header to add to each call, or none when the environment has no authentication. */
    public Map<String, String> header() throws IOException, InterruptedException {
        if (!auth.enabled()) {
            return Map.of();
        }
        return Map.of(auth.header(), (auth.prefix() == null ? "" : auth.prefix()) + accessToken());
    }

    public synchronized String accessToken() throws IOException, InterruptedException {
        Token t = token;
        Instant now = clock.instant();
        if (t != null && t.accessToken() != null && now.isBefore(t.expiresAt().minus(MARGIN))) {
            return t.accessToken();
        }
        if (t != null && t.refreshToken() != null && now.isBefore(t.refreshExpiresAt().minus(MARGIN))) {
            try {
                Map<String, String> form = new LinkedHashMap<>();
                form.put("grant_type", "refresh_token");
                form.put("refresh_token", t.refreshToken());
                token = request(form);
                return token.accessToken();
            } catch (TokenException e) {
                // The refresh token was revoked or the session ended: log in again.
            }
        }
        token = request(login());
        return token.accessToken();
    }

    private Map<String, String> login() throws TokenException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", auth.grantType().wire());
        if (auth.grantType() == GrantType.PASSWORD) {
            if (blank(auth.username())) {
                throw new TokenException("empty username: set it in the configuration or in .env");
            }
            if (blank(auth.password())) {
                throw new TokenException("missing password for " + auth.username());
            }
            form.put("username", auth.username());
            form.put("password", auth.password());
        } else if (blank(auth.clientId())) {
            throw new TokenException("empty clientId: it is required for client_credentials");
        }
        if (!blank(auth.scope())) {
            form.put("scope", auth.scope());
        }
        return form;
    }

    private Token request(Map<String, String> form) throws IOException, InterruptedException {
        boolean basic = auth.basicClientAuth() && !blank(auth.clientId());
        if (!blank(auth.clientId()) && !basic) {
            form.put("client_id", auth.clientId());
            if (!blank(auth.clientSecret())) {
                form.put("client_secret", auth.clientSecret());
            }
        }
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(auth.tokenUrl()))
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (basic) {
            String pair = enc(auth.clientId()) + ":" + enc(auth.clientSecret() == null ? "" : auth.clientSecret());
            builder.header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
        }
        HttpRequest request = builder.build();
        Instant sent = clock.instant();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json;
        try {
            json = new ObjectMapper().readTree(response.body());
        } catch (IOException e) {
            json = null;
        }
        if (response.statusCode() != 200 || json == null || !json.hasNonNull("access_token")) {
            String reason = json == null ? truncate(response.body())
                    : json.path("error_description").asText(json.path("error").asText(truncate(response.body())));
            throw new TokenException("HTTP " + response.statusCode() + (reason.isBlank() ? "" : ": " + reason)
                    + "\n" + sent(form, basic) + "\nresponse: " + truncate(response.body()));
        }
        long expiresIn = json.path("expires_in").asLong(300);
        long refreshIn = json.path("refresh_expires_in").asLong(expiresIn);
        String refresh = json.hasNonNull("refresh_token") ? json.get("refresh_token").asText() : null;
        return new Token(json.get("access_token").asText(), refresh, sent.plusSeconds(expiresIn),
                refreshIn <= 0 ? sent.plus(Duration.ofDays(3650)) : sent.plusSeconds(refreshIn), sent);
    }

    /**
     * What was sent, the secrets masked, to find a wrong or empty value: the token URL, the form, and the
     * client id and secret as they were read from the configuration and .env.
     */
    private String sent(Map<String, String> form, boolean basic) {
        StringBuilder out = new StringBuilder("sent: POST ").append(auth.tokenUrl());
        out.append("\n  grant_type = ").append(form.get("grant_type"));
        out.append("\n  client_id = ").append(blank(auth.clientId()) ? "(empty)" : "'" + auth.clientId() + "'" + spaces(auth.clientId()))
                .append(basic ? "  (Basic header, clientAuth: basic)" : "  (in the form)");
        out.append("\n  client_secret = ").append(secret(auth.clientSecret(), 3));
        if (form.containsKey("username")) {
            out.append("\n  username = '").append(auth.username()).append("'").append(spaces(auth.username()));
            out.append("\n  password = ").append(secret(auth.password(), 0));
        }
        if (form.containsKey("refresh_token")) {
            out.append("\n  refresh_token = (the previous one)");
        }
        if (form.containsKey("scope")) {
            out.append("\n  scope = ").append(form.get("scope"));
        }
        return out.toString();
    }

    /** {@code (vide)}, or its length and its first {@code shown} characters. */
    private static String secret(String value, int shown) {
        if (value == null || value.isEmpty()) {
            return "(empty: variable missing from .env?)";
        }
        String start = shown > 0 && value.length() > shown * 3 ? "'" + value.substring(0, shown) + "…', " : "";
        return start + value.length() + " characters" + spaces(value);
    }

    private static String spaces(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean lead = Character.isWhitespace(value.charAt(0));
        boolean trail = Character.isWhitespace(value.charAt(value.length() - 1));
        return lead || trail ? "  ⚠ space " + (lead && trail ? "at the start and the end" : lead ? "at the start" : "at the end") : "";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String truncate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    /** A token and when it expires; never written anywhere. */
    public record Token(String accessToken, String refreshToken, Instant expiresAt, Instant refreshExpiresAt,
                        Instant obtainedAt) {

        public boolean valid(Instant now) {
            return accessToken != null && now.isBefore(expiresAt);
        }
    }

    /** The token endpoint refused, or the credentials are incomplete. */
    public static final class TokenException extends IOException {

        public TokenException(String message) {
            super(message);
        }
    }
}
