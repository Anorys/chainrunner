package com.anorys.chainrunner.config;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The whole configuration, read from {@code chainrunner.yaml}: the environments (where to call and how to
 * get a token), the global variables and the sequences of calls. Environment variables are already
 * substituted; {@code {{variables}}} are left for {@link com.anorys.chainrunner.core.Template}.
 */
public record ChainRunnerConfig(Path file, String defaultEnvironment, Map<String, Environment> environments,
                             Options options, Map<String, Object> variables, List<Sequence> sequences) {

    public Optional<Environment> environment(String name) {
        return Optional.ofNullable(environments.get(name));
    }

    public Optional<Sequence> sequence(String name) {
        return sequences.stream().filter(s -> s.name().equals(name)).findFirst();
    }

    /**
     * Where to call: the APIs ({@code apis}, each with its base URL and its own token), the databases the
     * {@code poll} steps and guards query ({@code databases}), headers and variables shared by the APIs,
     * and whether to ask first. There is no main API or database: the environment's {@code auth} and
     * {@code database} only hold values its APIs and databases inherit.
     */
    public record Environment(String name, String label, Map<String, String> headers, Map<String, Object> variables,
                              Map<String, Database> databases, boolean protectedEnv, Map<String, Api> apis) {

        public String title() {
            return label == null || label.isBlank() ? name : label;
        }

        /** The API a step calls; a step may leave out {@code api} only when the environment has a single one. */
        public Api api(String apiName) {
            if (apiName == null || apiName.isBlank()) {
                if (apis.size() == 1) {
                    return apis.values().iterator().next();
                }
                throw new IllegalArgumentException(apis.isEmpty()
                        ? "environment " + name + " has no API (environments." + name + ".apis)"
                        : "set 'api:' on the step: " + name + " has several APIs (" + String.join(", ", apis.keySet()) + ")");
            }
            Api api = apis.get(apiName);
            if (api == null) {
                throw new IllegalArgumentException("API '" + apiName + "' is not defined for environment " + name
                        + (apis.isEmpty() ? "" : " (apis: " + String.join(", ", apis.keySet()) + ")"));
            }
            return api;
        }

        /**
         * The database a step queries; a step may leave out {@code database} only when the environment has
         * a single one.
         */
        public Database database(String databaseName) {
            if (databaseName == null || databaseName.isBlank()) {
                if (databases.size() == 1) {
                    return databases.values().iterator().next();
                }
                throw new IllegalArgumentException(databases.isEmpty()
                        ? "environment " + name + " has no database (environments." + name + ".databases)"
                        : "set 'database:' on the step: " + name + " has several databases ("
                        + String.join(", ", databases.keySet()) + ")");
            }
            Database database = databases.get(databaseName);
            if (database == null) {
                throw new IllegalArgumentException("database '" + databaseName + "' is not defined for environment " + name
                        + (databases.isEmpty() ? "" : " (databases: " + String.join(", ", databases.keySet()) + ")"));
            }
            return database;
        }

        /** {@code host1, host2}: where the environment's APIs are, for the screen. */
        public String hosts() {
            java.util.Set<String> hosts = new java.util.LinkedHashSet<>();
            for (Api api : apis.values()) {
                try {
                    String host = java.net.URI.create(api.baseUrl()).getHost();
                    hosts.add(host == null ? api.baseUrl() : host);
                } catch (IllegalArgumentException e) {
                    hosts.add(api.baseUrl());
                }
            }
            return String.join(", ", hosts);
        }
    }

    /**
     * An API of an environment: its base URL, its token settings (its own {@code auth} merged over the
     * environment's, field by field) and its headers. Each API has its own token.
     */
    public record Api(String name, String baseUrl, Auth auth, Map<String, String> headers) {
    }

    public enum GrantType {
        PASSWORD, CLIENT_CREDENTIALS, NONE;

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * How to get the token (OAuth2 token endpoint, Keycloak or other) and how to send it. An empty
     * password is asked for at the first call. {@code basicClientAuth}: the client id and secret go in
     * an {@code Authorization: Basic} header ({@code clientAuth: basic}) rather than in the form.
     */
    public record Auth(GrantType grantType, String tokenUrl, String clientId, String clientSecret,
                       String username, String password, String scope, String header, String prefix,
                       boolean basicClientAuth) {

        public Auth(GrantType grantType, String tokenUrl, String clientId, String clientSecret,
                    String username, String password, String scope, String header, String prefix) {
            this(grantType, tokenUrl, clientId, clientSecret, username, password, scope, header, prefix, false);
        }

        public static Auth none() {
            return new Auth(GrantType.NONE, null, null, null, null, null, null, "Authorization", "Bearer ");
        }

        public boolean enabled() {
            return grantType != GrantType.NONE;
        }

        public boolean needsPassword() {
            return grantType == GrantType.PASSWORD && (password == null || password.isEmpty());
        }

        public Auth withPassword(String newPassword) {
            return new Auth(grantType, tokenUrl, clientId, clientSecret, username, newPassword, scope, header, prefix,
                    basicClientAuth);
        }
    }

    /**
     * A database of an environment: a JDBC connection (PostgreSQL), its own settings merged over the
     * environment's {@code database}, field by field. An empty password is asked for at the first query.
     */
    public record Database(String name, String url, String username, String password) {

        public boolean needsPassword() {
            return username != null && !username.isBlank() && (password == null || password.isEmpty());
        }

        public Database withPassword(String newPassword) {
            return new Database(name, url, username, newPassword);
        }
    }

    /**
     * How the calls are made. Chaining rules: a 2xx goes on, a 401 renews the token and retries once,
     * a 5xx stops the sequence ({@code stopOnServerError}), another 4xx stops it only with
     * {@code stopOnClientError}; a network error or a refused token always stops it.
     */
    public record Options(Duration timeout, boolean stopOnServerError, boolean stopOnClientError, Duration delay,
                          boolean insecureTls, Path logDir, int maxBodyChars, Path baseDir) {
    }

    /**
     * How the steps of a sequence with a {@code forEach} are ordered: every step for all the values
     * before the next step ({@code PHASES}), or all the steps for one value before the next value
     * ({@code EACH}).
     */
    public enum Order {
        PHASES, EACH
    }

    /** A named list of steps, run one after the other, optionally for each value of its {@code forEach}. */
    public record Sequence(String name, String description, Map<String, Object> forEach, Order order, List<Call> calls) {
    }

    public enum StepType {
        /** An HTTP call; with {@code saveTo}, a download. */
        HTTP,
        /** A SQL query run again and again until it returns a row. */
        POLL
    }

    /**
     * One step as written in the file: its fields may hold {@code {{variables}}}, and {@code forEach}
     * turns it into one step per value (or per combination of values). {@code stopOnServerError} and
     * {@code stopOnClientError} override the options when set. {@code api} names the API an HTTP step
     * calls, {@code database} the database a poll step and the guard query.
     */
    public record Call(String name, StepType type, String method, String path, String url, Map<String, Object> query,
                       Map<String, String> headers, JsonNode body, Map<String, Object> forEach,
                       List<Integer> expectStatus, Map<String, String> extract, boolean auth, boolean enabled,
                       Boolean stopOnServerError, Boolean stopOnClientError, Poll poll, Save save, String api,
                       Guard guard, String database) {

        public boolean isPoll() {
            return type == StepType.POLL;
        }
    }

    /**
     * A query run before a step: with {@code skipIf}, the step is not needed when it returns a row; with
     * {@code onlyIf}, when it returns none. {@code database}: where it runs, when it is not the step's.
     */
    public record Guard(String sql, boolean skipWhenFound, String database) {

        public Guard(String sql, boolean skipWhenFound) {
            this(sql, skipWhenFound, null);
        }

        public String keyword() {
            return skipWhenFound ? "skipIf" : "onlyIf";
        }
    }

    public enum OnTimeout {
        /** The step fails, the other steps of the same value are skipped, the sequence goes on. */
        NEXT,
        /** The sequence stops. */
        STOP
    }

    public enum OnManyRows {
        FIRST, FAIL
    }

    /** A {@code poll} step: its query, how often it runs and for how long. */
    public record Poll(String sql, Duration every, Duration timeout, OnTimeout onTimeout, OnManyRows onManyRows) {
    }

    /**
     * Where a download goes: {@code saveTo} (relative to the configuration), the body as is ({@code raw})
     * or decoded from base64 at a JSON pointer of the response ({@code base64Pointer}).
     */
    public record Save(String saveTo, String base64Pointer, boolean overwrite) {
    }
}
