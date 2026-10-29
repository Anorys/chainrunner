package com.anorys.chainrunner;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A Keycloak-like token endpoint and an API on localhost. The API answers with the status set for a
 * query value ({@code status("ACC2", 500)}), 204 otherwise, and 401 to a token it did not issue.
 */
public final class FakeApi implements AutoCloseable {

    private final HttpServer server;
    public final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    public final List<String> tokenGrants = Collections.synchronizedList(new ArrayList<>());
    /** What each token request carried: its Authorization header and its client_id, "-" when absent. */
    public final List<String> tokenClients = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Integer> statuses = new HashMap<>();
    private final AtomicInteger issued = new AtomicInteger();
    private volatile String validToken;
    private volatile boolean rejectNextToken;

    /** Starts the tokens it issues with this, so that two fake APIs do not accept each other's tokens. */
    private final String prefix;

    public FakeApi() throws IOException {
        this("t");
    }

    public FakeApi(String prefix) throws IOException {
        this.prefix = prefix;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", this::token);
        server.createContext("/api", this::api);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public FakeApi status(String value, int status) {
        statuses.put(value, status);
        return this;
    }

    /** The next call is refused with 401, as when the token expired on the server side. */
    public void expireToken() {
        rejectNextToken = true;
    }

    private void token(HttpExchange ex) throws IOException {
        String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> params = new HashMap<>();
        for (String pair : form.split("&")) {
            String[] kv = pair.split("=", 2);
            params.put(kv[0], kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        tokenGrants.add(params.get("grant_type"));
        String basic = ex.getRequestHeaders().getFirst("Authorization");
        tokenClients.add((basic == null ? "-" : basic) + " " + params.getOrDefault("client_id", "-"));
        if ("password".equals(params.get("grant_type")) && !"secret".equals(params.get("password"))) {
            send(ex, 401, "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid user credentials\"}");
            return;
        }
        validToken = prefix + issued.incrementAndGet();
        send(ex, 200, "{\"access_token\":\"" + validToken + "\",\"expires_in\":300,\"refresh_expires_in\":1800,"
                + "\"refresh_token\":\"r" + issued.get() + "\",\"token_type\":\"Bearer\"}");
    }

    private void api(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String query = ex.getRequestURI().getRawQuery();
        calls.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + (query == null ? "" : "?" + query));
        if (rejectNextToken) {
            rejectNextToken = false;
            validToken = null;
        }
        if (auth == null || !auth.equals("Bearer " + validToken)) {
            send(ex, 401, "");
            return;
        }
        String path = ex.getRequestURI().getPath();
        if (path.startsWith("/api/documents/")) {
            String id = path.substring("/api/documents/".length());
            String content = java.util.Base64.getEncoder().encodeToString(document(id));
            send(ex, 200, "{\"id\":\"" + id + "\",\"fileName\":\"export-" + id + ".xml\",\"content\":\"" + content + "\"}");
            return;
        }
        if (path.startsWith("/api/files/")) {
            byte[] bytes = document(path.substring("/api/files/".length()));
            ex.getResponseHeaders().add("Content-Type", "application/xml");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
            return;
        }
        if (ex.getRequestURI().getPath().endsWith("/things")) {
            ex.getResponseHeaders().add("Location", "/api/things/42");
            send(ex, 201, "{\"id\":42,\"name\":\"x\"}");
            return;
        }
        int status = 204;
        for (Map.Entry<String, Integer> e : statuses.entrySet()) {
            if (query != null && query.contains(e.getKey())) {
                status = e.getValue();
            }
        }
        send(ex, status, status >= 400 ? "{\"code\":\"E" + status + "\",\"message\":\"boom\"}" : "");
    }

    /** The document the API serves for a docID. */
    public static byte[] document(String id) {
        return ("<?xml version=\"1.0\"?><Document id=\"" + id + "\">é</Document>").getBytes(StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
