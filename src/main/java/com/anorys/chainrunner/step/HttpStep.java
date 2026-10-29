package com.anorys.chainrunner.step;

import com.anorys.chainrunner.config.ChainRunnerConfig.Api;
import com.anorys.chainrunner.config.ChainRunnerConfig.Call;
import com.anorys.chainrunner.config.ChainRunnerConfig.Environment;
import com.anorys.chainrunner.config.ChainRunnerConfig.Options;
import com.anorys.chainrunner.config.ConfigException;
import com.anorys.chainrunner.config.StepFields;
import com.anorys.chainrunner.core.CallResult;
import com.anorys.chainrunner.core.CallResult.Request;
import com.anorys.chainrunner.core.CallResult.Status;
import com.anorys.chainrunner.core.Runner;
import com.anorys.chainrunner.core.Template;
import com.anorys.chainrunner.core.Template.UnknownVariableException;
import com.anorys.chainrunner.core.TokenClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * An HTTP call to one of the environment's APIs ({@code api}), with that API's token. A 2xx goes on; a
 * 401 renews the token (refresh token, or a new login) and retries once; a 5xx stops the sequence (see
 * {@link #halts}). With {@code saveTo}, the response is written to a file. {@code extract} reads values of
 * the response: {@code header:Name}, or a JSON pointer.
 */
public record HttpStep(String method, String path, String url, Map<String, Object> query, Map<String, String> headers,
                       JsonNode body, List<Integer> expectStatus, boolean auth, Boolean stopOnServerError,
                       Boolean stopOnClientError, Save save, String api) implements Step {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    /**
     * Where a download goes: {@code saveTo} (relative to the configuration), the body as is ({@code raw})
     * or decoded from base64 at a JSON pointer of the response ({@code base64Pointer}).
     */
    public record Save(String saveTo, String base64Pointer, boolean overwrite) {
    }

    /** {@code type: http}, the default. */
    public static final class Kind implements StepKind {

        @Override
        public String type() {
            return "http";
        }

        @Override
        public Step parse(StepFields f) {
            String method = f.text("method", "GET").toUpperCase(Locale.ROOT);
            if (!METHODS.contains(method)) {
                throw f.error("method: '" + method + "' is not an HTTP method");
            }
            String path = f.text("path", null);
            String url = f.text("url", null);
            if ((path == null || path.isBlank()) && (url == null || url.isBlank())) {
                throw new ConfigException(f.where() + ": 'path' (relative to baseUrl) or 'url' is required");
            }
            List<Integer> expect = new ArrayList<>();
            JsonNode expectNode = f.node("expectStatus");
            if (expectNode != null && expectNode.isArray()) {
                expectNode.forEach(n -> expect.add(n.asInt()));
            } else if (expectNode != null && expectNode.isNumber()) {
                expect.add(expectNode.asInt());
            }
            Save save = null;
            String saveTo = f.text("saveTo", null);
            if (saveTo != null && !saveTo.isBlank()) {
                String saveAs = f.text("saveAs", "raw").strip();
                String pointer = null;
                if (saveAs.startsWith("base64:")) {
                    pointer = saveAs.substring(7).strip();
                    pointer = pointer.startsWith("/") ? pointer : "/" + pointer.replaceFirst("^\\$?\\.?", "").replace('.', '/');
                } else if (!saveAs.equals("raw")) {
                    throw f.error("saveAs: '" + saveAs + "' is unknown (raw or base64:/json/pointer)");
                }
                save = new Save(saveTo, pointer, f.bool("overwrite", false));
            }
            return new HttpStep(method, path, url, f.values("query"), f.strings("headers"), f.node("body"),
                    List.copyOf(expect), f.bool("auth", true), f.optionalBool("stopOnServerError"),
                    f.optionalBool("stopOnClientError"), save, f.text("api", null));
        }
    }

    // ---------------------------------------------------------------- description

    @Override
    public String defaultName() {
        return method + " " + (path != null ? path : url);
    }

    @Override
    public String label() {
        return method;
    }

    @Override
    public String summary() {
        return method + " " + (path != null ? path : url);
    }

    @Override
    public List<Detail> details() {
        List<Detail> details = new ArrayList<>();
        if (save != null) {
            details.add(new Detail("saved to", save.saveTo() + (save.base64Pointer() == null ? "" : "  (base64 " + save.base64Pointer() + ")")));
        }
        if (api != null) {
            details.add(new Detail("api", api, Detail.Kind.API));
        }
        return details;
    }

    @Override
    public boolean callsApi() {
        return true;
    }

    @Override
    public boolean sendsToken() {
        return auth;
    }

    /**
     * Whether a failed call stops the sequence: no response (network, token, variable) or a 401 that a new
     * token did not fix always does; a 5xx and other statuses as configured for the step, or in the options.
     */
    public boolean halts(Options options, Integer status) {
        if (status == null || status == 401) {
            return true;
        }
        if (status >= 500) {
            return stopOnServerError != null ? stopOnServerError : options.stopOnServerError();
        }
        return stopOnClientError != null ? stopOnClientError : options.stopOnClientError();
    }

    // ---------------------------------------------------------------- request

    /** The URL with its query, the headers and the body, every variable rendered. */
    @Override
    public Request request(StepContext context, Call call, Map<String, Object> vars) {
        Environment env = context.env();
        Api target = env.api(api);
        String full;
        if (url != null && !url.isBlank()) {
            full = Template.text(url, vars);
        } else {
            if (target.baseUrl() == null || target.baseUrl().isBlank()) {
                throw new IllegalArgumentException("empty baseUrl for environment " + env.name()
                        + ", API " + target.name());
            }
            String rendered = Template.text(path, vars);
            full = target.baseUrl().replaceAll("/+$", "") + "/" + rendered.replaceAll("^/+", "");
        }
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, Object> q : query.entrySet()) {
            Object value = q.getValue() instanceof String s ? Template.value(s, vars) : q.getValue();
            List<?> values = value instanceof List<?> list ? list : java.util.Collections.singletonList(value);
            for (Object v : values) {
                pairs.add(enc(q.getKey()) + "=" + enc(Template.format(v)));
            }
        }
        if (!pairs.isEmpty()) {
            full += (full.contains("?") ? "&" : "?") + String.join("&", pairs);
        }
        URI.create(full);

        Map<String, String> sent = new LinkedHashMap<>();
        sent.put("Accept", save != null && save.base64Pointer() == null ? "*/*" : "application/json");
        env.headers().forEach((k, v) -> sent.put(k, Template.text(v, vars)));
        target.headers().forEach((k, v) -> sent.put(k, Template.text(v, vars)));
        headers.forEach((k, v) -> sent.put(k, Template.text(v, vars)));

        String text = null;
        if (body != null) {
            if (body.isTextual()) {
                text = Template.text(body.asText(), vars);
                sent.putIfAbsent("Content-Type", "text/plain; charset=utf-8");
            } else {
                try {
                    text = JSON.writeValueAsString(Template.json(body, vars));
                } catch (JsonProcessingException e) {
                    throw new IllegalArgumentException("invalid JSON body: " + e.getOriginalMessage(), e);
                }
                sent.putIfAbsent("Content-Type", "application/json");
            }
        }
        return new Request(method, full, Map.copyOf(sent), text);
    }

    // ---------------------------------------------------------------- run

    @Override
    public CallResult run(StepContext context, Call call, Request request, Map<String, Object> vars) throws InterruptedException {
        Options options = context.options();
        long start = System.nanoTime();
        TokenClient client = context.tokens().of(context.env().api(api));
        try {
            HttpResponse<byte[]> response = send(context, request, auth ? client : null);
            if (response.statusCode() == 401 && auth && client.auth().enabled()) {
                client.invalidate();
                response = send(context, request, client);
                if (response.statusCode() == 401) {
                    return CallResult.failed(request, 401, Runner.since(start), context.display(text(response), null),
                            "401 even with a new token: missing permissions?", List.of(), true);
                }
            }
            Duration took = Runner.since(start);
            int status = response.statusCode();
            String text = text(response);
            boolean statusOk = expectStatus.isEmpty() ? status / 100 == 2 : expectStatus.contains(status);
            if (!statusOk) {
                return CallResult.failed(request, status, took, context.display(text, null), "status " + status
                        + (expectStatus.isEmpty() ? " (2xx expected)" : " (expected " + expectStatus + ")"), List.of(),
                        halts(options, status));
            }
            boolean binary = save != null && save.base64Pointer() == null;
            String shown = binary ? "(" + Runner.size(response.body().length) + " received)"
                    : context.display(text, save == null ? null : save.base64Pointer());
            Map<String, Object> extracted = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : call.extract().entrySet()) {
                Object value = extract(response.headers(), binary ? "" : text, e.getValue());
                if (value == null) {
                    return CallResult.failed(request, status, took, shown,
                            "extract " + e.getKey() + ": " + e.getValue() + " is not in the response", List.of(), halts(options, status));
                }
                extracted.put(e.getKey(), value);
            }
            List<String> notes = new ArrayList<>();
            if (save != null) {
                String error = save(options, vars, response.body(), text, notes);
                if (error != null) {
                    return CallResult.failed(request, status, took, shown, error, notes, false);
                }
            }
            return new CallResult(Status.OK, request, status, took, shown, null, Map.copyOf(extracted), List.copyOf(notes), false);
        } catch (IOException e) {
            String message = e instanceof TokenClient.TokenException ? "token refused: " + e.getMessage()
                    : e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return CallResult.failed(request, null, Runner.since(start), null, message, List.of(), true);
        }
    }

    /** Writes a download; returns the error, or null and a note with the file. */
    private String save(Options options, Map<String, Object> vars, byte[] raw, String text, List<String> notes) {
        Path target;
        try {
            target = options.baseDir().resolve(Template.text(save.saveTo(), vars)).normalize();
        } catch (UnknownVariableException | IllegalArgumentException e) {
            return "saveTo: " + e.getMessage();
        }
        byte[] bytes = raw;
        if (save.base64Pointer() != null) {
            JsonNode node;
            try {
                node = JSON.readTree(text).at(save.base64Pointer());
            } catch (IOException e) {
                return "the response is not JSON: cannot read " + save.base64Pointer();
            }
            if (node == null || node.isMissingNode() || node.isNull() || !node.isTextual()) {
                return save.base64Pointer() + " is not in the response (base64 text expected)";
            }
            try {
                bytes = Base64.getMimeDecoder().decode(node.asText());
            } catch (IllegalArgumentException e) {
                return save.base64Pointer() + " is not base64: " + e.getMessage();
            }
        }
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            if (save.overwrite()) {
                Files.write(target, bytes);
            } else {
                Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
        } catch (FileAlreadyExistsException e) {
            return "the file already exists: " + target + " (overwrite: true to replace it)";
        } catch (IOException e) {
            return "cannot write " + target + ": " + e.getMessage();
        }
        notes.add("file: " + target + " (" + Runner.size(bytes.length) + ")");
        return null;
    }

    /** Sends {@code request}, with the token of {@code client} unless it is null. */
    private static HttpResponse<byte[]> send(StepContext context, Request request, TokenClient client)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.url())).timeout(context.options().timeout());
        request.headers().forEach(builder::header);
        if (client != null) {
            client.header().forEach(builder::setHeader);
        }
        builder.method(request.method(), request.body() == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(request.body()));
        return context.http().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String text(HttpResponse<byte[]> response) {
        return response.body() == null ? "" : new String(response.body(), StandardCharsets.UTF_8);
    }

    /**
     * A value of the response: {@code header:Name}, or a JSON pointer ({@code /data/id}); {@code $.data.id}
     * and {@code data.id} are read as {@code /data/id}.
     */
    static Object extract(HttpHeaders headers, String body, String path) {
        if (path.regionMatches(true, 0, "header:", 0, 7)) {
            return headers.firstValue(path.substring(7).strip()).orElse(null);
        }
        return Json.value(body, path);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
