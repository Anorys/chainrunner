package com.anorys.chainrunner.config;

import com.anorys.chainrunner.config.ChainRunnerConfig.Api;
import com.anorys.chainrunner.config.ChainRunnerConfig.Auth;
import com.anorys.chainrunner.config.ChainRunnerConfig.Call;
import com.anorys.chainrunner.config.ChainRunnerConfig.Database;
import com.anorys.chainrunner.config.ChainRunnerConfig.Environment;
import com.anorys.chainrunner.config.ChainRunnerConfig.GrantType;
import com.anorys.chainrunner.config.ChainRunnerConfig.Guard;
import com.anorys.chainrunner.config.ChainRunnerConfig.Options;
import com.anorys.chainrunner.config.ChainRunnerConfig.Order;
import com.anorys.chainrunner.config.ChainRunnerConfig.Sequence;
import com.anorys.chainrunner.step.Step;
import com.anorys.chainrunner.step.StepKind;
import com.anorys.chainrunner.step.StepKinds;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@code chainrunner.yaml} with Jackson's tree model and maps it by hand (no data binding, so the
 * native image needs no reflection metadata). {@code ${NAME}} and {@code ${NAME:-default}} are replaced
 * by environment variables, or by the {@code .env} file next to the configuration.
 */
public final class ConfigLoader {

    public static final String DEFAULT_FILE = "chainrunner.yaml";

    private static final Pattern ENV = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}");
    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*(ms|s|m|min|h)?");

    private final Function<String, String> env;

    public ConfigLoader(Function<String, String> env) {
        this.env = env;
    }

    /** Reads the system environment, then the {@code .env} file next to {@code file}. */
    public static ChainRunnerConfig load(Path file) {
        Map<String, String> dotEnv = readDotEnv(file.toAbsolutePath().getParent().resolve(".env"));
        return new ConfigLoader(name -> {
            String value = System.getenv(name);
            return value != null ? value : dotEnv.get(name);
        }).read(file);
    }

    public ChainRunnerConfig read(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new ConfigException("Configuration file not found: " + file.toAbsolutePath());
        }
        try {
            return parse(file, Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ConfigException("Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    public ChainRunnerConfig parse(Path file, String yaml) {
        JsonNode root;
        try {
            root = new ObjectMapper(new YAMLFactory()).readTree(yaml);
        } catch (JsonProcessingException e) {
            throw new ConfigException(file.getFileName() + " is not valid YAML: " + e.getOriginalMessage()
                    + (e.getLocation() == null ? "" : " (line " + e.getLocation().getLineNr() + ")"), e);
        }
        if (root == null || !root.isObject()) {
            throw new ConfigException(file.getFileName() + " is empty or not a YAML object");
        }
        root = interpolate(root);

        Map<String, Environment> environments = new LinkedHashMap<>();
        JsonNode envs = root.path("environments");
        if (!envs.isObject() || envs.isEmpty()) {
            throw new ConfigException("'environments' must define at least one environment (dev, acc, prod...)");
        }
        envs.fields().forEachRemaining(e -> environments.put(e.getKey(), environment(e.getKey(), e.getValue())));

        String defaultEnv = text(root, "defaultEnvironment", environments.keySet().iterator().next());
        if (!environments.containsKey(defaultEnv)) {
            throw new ConfigException("defaultEnvironment '" + defaultEnv + "' is not in 'environments' "
                    + environments.keySet());
        }

        List<Sequence> sequences = new ArrayList<>();
        JsonNode seqs = root.path("sequences");
        if (seqs.isObject()) {
            seqs.fields().forEachRemaining(e -> sequences.add(sequence(e.getKey(), e.getValue())));
        } else if (!seqs.isMissingNode()) {
            throw new ConfigException("'sequences' must be an object: sequence name -> { steps: [...] }");
        }
        if (sequences.isEmpty()) {
            throw new ConfigException("'sequences' must define at least one sequence");
        }

        return new ChainRunnerConfig(file, defaultEnv, environments, options(root.path("options"), file),
                values(root.path("variables"), "variables"), sequences);
    }

    // ---------------------------------------------------------------- sections

    private Environment environment(String name, JsonNode node) {
        String where = "environments." + name;
        requireObject(node, where);
        if (node.has("baseUrl")) {
            throw new ConfigException(where + ".baseUrl: there is no main API; declare each API under "
                    + where + ".apis.<name>.baseUrl (and pick it on the step with 'api: <name>')");
        }
        JsonNode defaults = node.path("auth");
        if (!defaults.isMissingNode() && !defaults.isNull()) {
            requireObject(defaults, where + ".auth");
        }
        Map<String, Database> databases = databases(node, where);
        Map<String, Api> apis = new LinkedHashMap<>();
        JsonNode apisNode = node.path("apis");
        if (!apisNode.isMissingNode() && !apisNode.isNull()) {
            requireObject(apisNode, where + ".apis");
            apisNode.fields().forEachRemaining(e -> {
                String apiWhere = where + ".apis." + e.getKey();
                JsonNode a = e.getValue();
                requireObject(a, apiWhere);
                String apiUrl = text(a, "baseUrl", "");
                if (apiUrl.isBlank()) {
                    throw new ConfigException(apiWhere + ".baseUrl is required");
                }
                JsonNode own = a.path("auth");
                if (!own.isMissingNode() && !own.isNull()) {
                    requireObject(own, apiWhere + ".auth");
                }
                // The API's auth, each field missing there taken from the environment's auth.
                ObjectNode merged = JsonNodeFactory.instance.objectNode();
                if (defaults.isObject()) {
                    merged.setAll((ObjectNode) defaults);
                }
                if (own.isObject()) {
                    merged.setAll((ObjectNode) own);
                }
                Auth auth = merged.isEmpty() ? Auth.none() : auth(merged, apiWhere + ".auth");
                apis.put(e.getKey(), new Api(e.getKey(), apiUrl, auth, strings(a.path("headers"), apiWhere + ".headers")));
            });
        }
        return new Environment(name, text(node, "label", null),
                strings(node.path("headers"), where + ".headers"), values(node.path("variables"), where + ".variables"),
                databases, node.path("protected").asBoolean(false), java.util.Collections.unmodifiableMap(apis));
    }

    /**
     * {@code databases.<name>}, each field missing there taken from the environment's {@code database}:
     * the defaults alone define no database.
     */
    private Map<String, Database> databases(JsonNode node, String where) {
        JsonNode defaults = node.path("database");
        if (!defaults.isMissingNode() && !defaults.isNull()) {
            requireObject(defaults, where + ".database");
        }
        JsonNode named = node.path("databases");
        if (named.isMissingNode() || named.isNull()) {
            if (defaults.has("url")) {
                throw new ConfigException(where + ".database only holds values that databases inherit; declare each database under "
                        + where + ".databases.<name>.url (and pick it on the step with 'database: <name>')");
            }
            return Map.of();
        }
        requireObject(named, where + ".databases");
        Map<String, Database> databases = new LinkedHashMap<>();
        named.fields().forEachRemaining(e -> {
            String dbWhere = where + ".databases." + e.getKey();
            JsonNode own = e.getValue() == null || e.getValue().isNull() ? JsonNodeFactory.instance.objectNode() : e.getValue();
            requireObject(own, dbWhere);
            ObjectNode merged = JsonNodeFactory.instance.objectNode();
            if (defaults.isObject()) {
                merged.setAll((ObjectNode) defaults);
            }
            merged.setAll((ObjectNode) own);
            String url = text(merged, "url", "");
            if (!url.startsWith("jdbc:postgresql:")) {
                throw new ConfigException(dbWhere + ".url must start with jdbc:postgresql:// (in the database or in the environment's database)"
                        + (url.isBlank() ? "" : ", got '" + url + "'"));
            }
            databases.put(e.getKey(), new Database(e.getKey(), url, text(merged, "username", null), text(merged, "password", null)));
        });
        return java.util.Collections.unmodifiableMap(databases);
    }

    private Auth auth(JsonNode node, String where) {
        requireObject(node, where);
        String type = text(node, "grantType", "password").toUpperCase(Locale.ROOT).replace('-', '_');
        GrantType grant;
        try {
            grant = GrantType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new ConfigException(where + ".grantType: '" + type.toLowerCase(Locale.ROOT)
                    + "' is unknown (password, client_credentials or none)");
        }
        String tokenUrl = text(node, "tokenUrl", null);
        if (grant != GrantType.NONE && (tokenUrl == null || tokenUrl.isBlank())) {
            throw new ConfigException(where + ".tokenUrl is required (in the API or in the environment's auth),"
                    + " or grantType: none for an API without a token");
        }
        // Credentials are checked when a token is asked for: an environment that is not used may lack them.
        return new Auth(grant, tokenUrl, text(node, "clientId", null), text(node, "clientSecret", null),
                text(node, "username", null),
                text(node, "password", null), text(node, "scope", null),
                text(node, "header", "Authorization"), node.has("prefix") ? node.get("prefix").asText() : "Bearer ",
                clientAuth(node, where));
    }

    /** {@code clientAuth: body} (default, client_id and client_secret in the form) or {@code basic}. */
    private static boolean clientAuth(JsonNode node, String where) {
        String value = text(node, "clientAuth", "body").strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "body", "post", "form" -> false;
            case "basic" -> true;
            default -> throw new ConfigException(where + ".clientAuth: '" + value + "' is unknown (body or basic)");
        };
    }

    private Options options(JsonNode node, Path file) {
        Path dir = file.toAbsolutePath().getParent();
        String logDir = text(node, "logDir", "logs");
        return new Options(
                Duration.ofSeconds(node.path("timeoutSeconds").asLong(120)),
                node.path("stopOnServerError").asBoolean(true),
                node.path("stopOnClientError").asBoolean(false),
                Duration.ofMillis(node.path("delayMillis").asLong(0)),
                node.path("insecureTls").asBoolean(false),
                logDir.isBlank() ? null : dir.resolve(logDir),
                node.path("maxBodyChars").asInt(20_000),
                dir);
    }

    private Sequence sequence(String name, JsonNode node) {
        String where = "sequences." + name;
        String key = node.isObject() && node.has("steps") ? "steps" : "calls";
        JsonNode callsNode = node.isArray() ? node : node.path(key);
        if (!callsNode.isArray() || callsNode.isEmpty()) {
            throw new ConfigException(where + "." + key + " must be a non-empty list of steps");
        }
        List<Call> calls = new ArrayList<>();
        for (int i = 0; i < callsNode.size(); i++) {
            calls.add(call(callsNode.get(i), where + "." + key + "[" + i + "]"));
        }
        if (!node.isObject()) {
            return new Sequence(name, null, Map.of(), Order.PHASES, calls);
        }
        Order order = choice(node, "order", Order.PHASES, where);
        return new Sequence(name, text(node, "description", null), values(node.path("forEach"), where + ".forEach"),
                order, calls);
    }

    /**
     * The fields every step shares here; the others are read by the step's kind ({@code type:}, http when
     * left out), as listed in {@link StepKinds}.
     */
    private Call call(JsonNode node, String where) {
        requireObject(node, where);
        String type = text(node, "type", StepKinds.DEFAULT);
        StepKind kind = StepKinds.find(type.isBlank() ? StepKinds.DEFAULT : type).orElseThrow(() -> new ConfigException(
                where + ".type: '" + type + "' is unknown (" + String.join(", ", StepKinds.types()) + ")"));
        Step step = kind.parse(new StepFields(node, where));
        return new Call(text(node, "name", step.defaultName()), step, values(node.path("forEach"), where + ".forEach"),
                strings(node.path("extract"), where + ".extract"), node.path("enabled").asBoolean(true),
                guard(node, where), text(node, "database", null));
    }

    /** {@code skipIf} or {@code onlyIf}: a query, written alone or as {@code { sql: ..., database: ... }}. */
    private static Guard guard(JsonNode node, String where) {
        if (node.has("skipIf") && node.has("onlyIf")) {
            throw new ConfigException(where + ": skipIf or onlyIf, not both");
        }
        String key = node.has("skipIf") ? "skipIf" : node.has("onlyIf") ? "onlyIf" : null;
        if (key == null) {
            return null;
        }
        JsonNode g = node.get(key);
        String sql = g.isObject() ? text(g, "sql", "") : g.isValueNode() ? g.asText() : "";
        if (sql.isBlank()) {
            throw new ConfigException(where + "." + key + ": a SQL query is expected (" + key + ": select ...)");
        }
        return new Guard(sql.strip(), key.equals("skipIf"), g.isObject() ? text(g, "database", null) : null);
    }

    /** {@code 10s}, {@code 2m}, {@code 1h}, {@code 500ms}, or a number of seconds. */
    static Duration duration(JsonNode node, String field, Duration fallback, String where) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (value.isNumber()) {
            return Duration.ofMillis(Math.round(value.asDouble() * 1000));
        }
        Matcher m = DURATION.matcher(value.asText().strip().toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            throw new ConfigException(where + "." + field + ": '" + value.asText() + "' is not a duration (10s, 2m, 1h, 500ms)");
        }
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2) == null ? "s" : m.group(2)) {
            case "ms" -> Duration.ofMillis(n);
            case "m", "min" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            default -> Duration.ofSeconds(n);
        };
    }

    /** An enum value written in lower case ({@code client_credentials}, {@code next}). */
    static <E extends Enum<E>> E choice(JsonNode node, String field, E fallback, String where) {
        String value = text(node, field, null);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        for (E e : fallback.getDeclaringClass().getEnumConstants()) {
            if (e.name().equalsIgnoreCase(value.strip().replace('-', '_'))) {
                return e;
            }
        }
        List<String> names = new ArrayList<>();
        for (E e : fallback.getDeclaringClass().getEnumConstants()) {
            names.add(e.name().toLowerCase(Locale.ROOT));
        }
        throw new ConfigException(where + "." + field + ": '" + value + "' is unknown (" + String.join(", ", names) + ")");
    }

    // ---------------------------------------------------------------- values

    /** Replaces {@code ${NAME}} in every string of the tree. */
    JsonNode interpolate(JsonNode node) {
        if (node.isTextual()) {
            return TextNode.valueOf(substitute(node.asText()));
        }
        if (node.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(e -> copy.set(e.getKey(), interpolate(e.getValue())));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            node.forEach(n -> copy.add(interpolate(n)));
            return copy;
        }
        return node;
    }

    String substitute(String text) {
        Matcher m = ENV.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = env.apply(m.group(1));
            if (value == null || value.isEmpty() && m.group(2) != null) {
                value = m.group(2) != null ? m.group(2) : "";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** A map of scalars or lists of scalars: strings, booleans and numbers keep their type. */
    static Map<String, Object> values(JsonNode node, String where) {
        if (node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        requireObject(node, where);
        Map<String, Object> map = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            JsonNode value = e.getValue();
            if (value.isArray()) {
                List<Object> list = new ArrayList<>();
                value.forEach(v -> list.add(scalar(v, where + "." + e.getKey())));
                map.put(e.getKey(), List.copyOf(list));
            } else {
                map.put(e.getKey(), scalar(value, where + "." + e.getKey()));
            }
        });
        return map;
    }

    private static Object scalar(JsonNode node, String where) {
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isNull()) {
            return "";
        }
        if (node.isValueNode()) {
            return node.asText();
        }
        throw new ConfigException(where + " must be a value or a list of values");
    }

    static Map<String, String> strings(JsonNode node, String where) {
        if (node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        requireObject(node, where);
        Map<String, String> map = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!e.getValue().isValueNode()) {
                throw new ConfigException(where + "." + e.getKey() + " must be a value");
            }
            map.put(e.getKey(), e.getValue().asText());
        }
        return map;
    }

    static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }

    static void requireObject(JsonNode node, String where) {
        if (!node.isObject()) {
            throw new ConfigException(where + " must be a YAML object (key: value)");
        }
    }

    /** {@code NAME=value} lines; {@code #} comments and surrounding quotes are ignored. */
    static Map<String, String> readDotEnv(Path file) {
        Map<String, String> values = new HashMap<>();
        if (!Files.isRegularFile(file)) {
            return values;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                int eq = trimmed.indexOf('=');
                if (trimmed.isEmpty() || trimmed.startsWith("#") || eq <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, eq).strip();
                if (key.startsWith("export ")) {
                    key = key.substring(7).strip();
                }
                String value = trimmed.substring(eq + 1).strip();
                if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                        || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(key, value);
            }
        } catch (IOException e) {
            throw new ConfigException("Cannot read " + file + ": " + e.getMessage(), e);
        }
        return values;
    }
}
