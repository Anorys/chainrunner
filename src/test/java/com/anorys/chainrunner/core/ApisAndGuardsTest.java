package com.anorys.chainrunner.core;

import com.anorys.chainrunner.FakeApi;
import com.anorys.chainrunner.config.ChainRunnerConfig;
import com.anorys.chainrunner.config.ChainRunnerConfig.Environment;
import com.anorys.chainrunner.config.ConfigException;
import com.anorys.chainrunner.config.ConfigLoader;
import com.anorys.chainrunner.core.CallResult.Request;
import com.anorys.chainrunner.core.CallResult.Status;
import com.anorys.chainrunner.core.Planner.PlannedCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Several APIs with their own tokens, several databases, and steps made only when a query says so. */
class ApisAndGuardsTest {

    private FakeApi main;
    private FakeApi documents;
    /** The values whose file the fake table already has. */
    private final Set<String> existing = new java.util.HashSet<>();
    private final List<String> queries = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void start() throws Exception {
        main = new FakeApi("m");
        documents = new FakeApi("d");
    }

    @AfterEach
    void stop() {
        main.close();
        documents.close();
    }

    /** The databases opened, by name, in order. */
    private final List<String> opened = Collections.synchronizedList(new ArrayList<>());

    private final Database.Opener fakeDb = (config, timeout) -> {
        opened.add(config.name());
        return new Database() {
            @Override
            public List<Map<String, Object>> query(String sql, List<Object> params) throws SQLException {
                queries.add(config.name() + ": " + sql + " " + params);
                return !params.isEmpty() && existing.contains(String.valueOf(params.get(0))) ? List.of(Map.of("id", 7L)) : List.of();
            }

            @Override
            public void close() {
            }
        };
    };

    private ChainRunnerConfig config(String steps) {
        String yaml = """
                environments:
                  dev:
                    # Values every API inherits, field by field, unless it sets its own.
                    auth: { tokenUrl: %1$s/token, clientId: main, username: jdoe, password: secret }
                    database: { url: "jdbc:postgresql://localhost/orders" }
                    databases:
                      orders: {}
                      archive: { url: "jdbc:postgresql://localhost/archive" }
                    apis:
                      orders:
                        baseUrl: %1$s/api
                      document:
                        baseUrl: %2$s/api
                        auth: { tokenUrl: %2$s/token, grantType: client_credentials, clientId: doc, clientSecret: s }
                        headers: { X-Service: document }
                      other-client:
                        baseUrl: %1$s/api
                        auth: { clientId: other }
                options: { logDir: "" }
                variables:
                  accounts: [ACC1, ACC2]
                sequences:
                  s:
                    forEach: { account: "{{accounts}}" }
                    steps:
                %3$s
                """.formatted(main.url(), documents.url(), steps);
        return new ConfigLoader(name -> null).parse(Path.of("chainrunner.yaml"), yaml);
    }

    private List<CallResult> run(ChainRunnerConfig config) throws Exception {
        Environment env = config.environment("dev").orElseThrow();
        HttpClient http = Http.client(Duration.ofSeconds(5), false);
        TokenClients tokens = new TokenClients(http, env, Duration.ofSeconds(5), Clock.systemUTC());
        Map<String, Object> vars = Planner.variables(config, env, Map.of());
        List<PlannedCall> calls = Planner.plan(config.sequence("s").orElseThrow(), vars);
        return new Runner(http, tokens, env, config.options(), fakeDb, java.util.function.Function.identity()).run(calls, vars, new Runner.Listener() {
            @Override
            public void started(PlannedCall call, Request request) {
            }

            @Override
            public void finished(PlannedCall call, CallResult result) {
            }
        }, () -> false);
    }

    @Test
    void eachApiGetsItsOwnToken() throws Exception {
        List<CallResult> results = run(config("""
                      - { name: "Export {{account}}", api: orders, method: POST, path: "/exports", query: { account: "{{account}}" } }
                      - { name: "Doc {{account}}", api: document, path: "/files/{{account}}" }
                """));

        assertThat(results).extracting(CallResult::status).containsOnly(Status.OK);
        assertThat(main.calls).containsExactly("POST /api/exports?account=ACC1", "POST /api/exports?account=ACC2");
        assertThat(documents.calls).containsExactly("GET /api/files/ACC1", "GET /api/files/ACC2");
        assertThat(main.tokenGrants).containsExactly("password");
        assertThat(documents.tokenGrants).containsExactly("client_credentials");
        assertThat(results.get(2).request().headers()).containsEntry("X-Service", "document");
    }

    @Test
    void anApiInheritsTheEnvironmentAuthFieldByFieldAndHasItsOwnToken() throws Exception {
        run(config("""
                      - { name: "A {{account}}", api: orders, path: "/a/{{account}}" }
                      - { name: "B {{account}}", api: other-client, path: "/b/{{account}}" }
                """));

        // other-client: its own client id; the token URL, user and password come from the environment.
        assertThat(main.tokenGrants).containsExactly("password", "password");
        assertThat(main.tokenClients).containsExactly("- main", "- other");
    }

    @Test
    void aStepMustNameItsApiWhenThereAreSeveral() throws Exception {
        List<CallResult> results = run(config("""
                      - { name: "x", path: "/x" }
                """));

        assertThat(results.get(0).error()).isEqualTo("set 'api:' on the step: dev has several APIs (orders, document, other-client)");
    }

    @Test
    void anUnknownApiIsReported() throws Exception {
        List<CallResult> results = run(config("""
                      - { name: "x", api: billing, path: "/x" }
                """));

        assertThat(results.get(0).error()).isEqualTo("API 'billing' is not defined for environment dev (apis: orders, document, other-client)");
    }

    @Test
    void skipIfLeavesOutTheStepWhenTheQueryFindsARow() throws Exception {
        existing.add("ACC1");

        List<CallResult> results = run(config("""
                      - name: "Export {{account}}"
                        api: orders
                        method: POST
                        path: /exports
                        query: { account: "{{account}}" }
                        database: orders
                        skipIf: select id from export_file where account_id = '{{account}}'
                      - { name: "Doc {{account}}", api: document, path: "/files/{{account}}" }
                """));

        assertThat(results).extracting(CallResult::status).containsExactly(Status.UNNEEDED, Status.OK, Status.OK, Status.OK);
        assertThat(results.get(0).error()).isEqualTo("not needed: skipIf found a row");
        assertThat(results.get(0).request().sql()).isTrue();
        assertThat(main.calls).containsExactly("POST /api/exports?account=ACC2");
        // Not a failure: the later steps for ACC1 are made.
        assertThat(documents.calls).containsExactly("GET /api/files/ACC1", "GET /api/files/ACC2");
        assertThat(queries.get(0)).isEqualTo("orders: select id from export_file where account_id = ? [ACC1]");
        // One connection per database, for the whole run.
        assertThat(opened).containsExactly("orders");
    }

    @Test
    void onlyIfMakesTheStepOnlyWhenTheQueryFindsARow() throws Exception {
        existing.add("ACC2");

        List<CallResult> results = run(config("""
                      - name: "Doc {{account}}"
                        api: document
                        path: "/files/{{account}}"
                        onlyIf: { sql: "select 1 from export_file where account_id = {{account}}", database: archive }
                """));

        assertThat(results).extracting(CallResult::status).containsExactly(Status.UNNEEDED, Status.OK);
        assertThat(results.get(0).error()).isEqualTo("not needed: onlyIf found no row");
        assertThat(documents.calls).containsExactly("GET /api/files/ACC2");
        assertThat(queries).allMatch(q -> q.startsWith("archive: "));
    }

    @Test
    void aStepMustNameItsDatabaseWhenThereAreSeveral() throws Exception {
        List<CallResult> results = run(config("""
                      - { name: "x {{account}}", api: orders, path: "/x", skipIf: "select 1" }
                      - { name: "y {{account}}", type: poll, database: billing, sql: "select 1" }
                """));

        assertThat(results.get(0).error()).isEqualTo("skipIf: set 'database:' on the step: dev has several databases (orders, archive)");
        assertThat(results.get(1).status()).isEqualTo(Status.SKIPPED);
        List<CallResult> poll = run(config("""
                      - { name: "y {{account}}", type: poll, database: billing, sql: "select 1" }
                """));
        assertThat(poll.get(0).error()).isEqualTo("database 'billing' is not defined for environment dev (databases: orders, archive)");
        assertThat(main.calls).isEmpty();
    }

    @Test
    void checksTheConfiguration() {
        assertThatThrownBy(() -> config("      - { path: /x, skipIf: \"\" }\n")).isInstanceOf(ConfigException.class).hasMessageContaining("skipIf");
        assertThatThrownBy(() -> config("      - { path: /x, skipIf: a, onlyIf: b }\n")).hasMessageContaining("not both");
        assertThatThrownBy(() -> parse("""
                environments:
                  dev: { baseUrl: http://x }
                sequences: { s: [ { path: /a } ] }
                """)).hasMessageContaining("environments.dev.baseUrl: there is no main API");
        assertThatThrownBy(() -> parse("""
                environments:
                  dev: { apis: { doc: { auth: { grantType: none } } } }
                sequences: { s: [ { path: /a } ] }
                """)).hasMessageContaining("apis.doc.baseUrl");
        assertThatThrownBy(() -> parse("""
                environments:
                  dev: { auth: { clientId: x }, apis: { doc: { baseUrl: http://y } } }
                sequences: { s: [ { path: /a } ] }
                """)).hasMessageContaining("environments.dev.apis.doc.auth.tokenUrl is required");
        // Nothing is required in the environment's auth, and an API without any auth sends no token.
        ChainRunnerConfig config = parse("""
                environments:
                  dev: { apis: { open: { baseUrl: http://y } } }
                sequences: { s: [ { path: /a } ] }
                """);
        assertThat(config.environment("dev").orElseThrow().api(null).auth().enabled()).isFalse();
    }

    private static ChainRunnerConfig parse(String yaml) {
        return new ConfigLoader(name -> null).parse(Path.of("x.yaml"), yaml);
    }
}
