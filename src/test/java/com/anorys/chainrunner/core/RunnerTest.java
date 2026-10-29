package com.anorys.chainrunner.core;

import com.anorys.chainrunner.FakeApi;
import com.anorys.chainrunner.config.ChainRunnerConfig;
import com.anorys.chainrunner.config.ChainRunnerConfig.Environment;
import com.anorys.chainrunner.config.ConfigLoader;
import com.anorys.chainrunner.core.CallResult.Request;
import com.anorys.chainrunner.core.CallResult.Status;
import com.anorys.chainrunner.core.Planner.PlannedCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerTest {

    private FakeApi api;

    @BeforeEach
    void start() throws Exception {
        api = new FakeApi();
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private ChainRunnerConfig config(String password, String extra) {
        String yaml = """
                environments:
                  dev:
                    apis:
                      orders: { baseUrl: "%1$s/api" }
                    auth:
                      tokenUrl: %1$s/token
                      clientId: chainrunner
                      username: jdoe
                      password: "%2$s"
                options:
                  logDir: ""
                variables:
                  accountIds: [ACC1, ACC2, ACC3]
                  feedback: false
                sequences:
                  exports:
                    calls:
                      - name: "Export {{accountId}}"
                        method: POST
                        path: /orders/exports
                        forEach:
                          accountId: "{{accountIds}}"
                        query:
                          accountId: "{{accountId}}"
                          feedbackToServices: "{{feedback}}"
                %3$s
                """.formatted(api.url(), password, extra);
        return new ConfigLoader(name -> null).parse(Path.of("chainrunner.yaml"), yaml);
    }

    private List<CallResult> run(ChainRunnerConfig config, String sequence, Map<String, Object> overrides) throws Exception {
        Environment env = config.environment("dev").orElseThrow();
        HttpClient http = Http.client(Duration.ofSeconds(5), false);
        TokenClients tokens = new TokenClients(http, env, Duration.ofSeconds(5), Clock.systemUTC());
        Map<String, Object> vars = Planner.variables(config, env, overrides);
        List<PlannedCall> calls = Planner.plan(config.sequence(sequence).orElseThrow(), vars);
        return new Runner(http, tokens, env, config.options()).run(calls, vars, new Runner.Listener() {
            @Override
            public void started(PlannedCall call, Request request) {
            }

            @Override
            public void finished(PlannedCall call, CallResult result) {
            }
        }, () -> false);
    }

    @Test
    void chainsTheCallsOnTwoHundreds() throws Exception {
        List<CallResult> results = run(config("secret", ""), "exports", Map.of());

        assertThat(results).extracting(CallResult::status).containsExactly(Status.OK, Status.OK, Status.OK);
        assertThat(api.calls).containsExactly(
                "POST /api/orders/exports?accountId=ACC1&feedbackToServices=false",
                "POST /api/orders/exports?accountId=ACC2&feedbackToServices=false",
                "POST /api/orders/exports?accountId=ACC3&feedbackToServices=false");
        assertThat(api.tokenGrants).containsExactly("password");
    }

    @Test
    void stopsOnAServerError() throws Exception {
        api.status("ACC2", 500);

        List<CallResult> results = run(config("secret", ""), "exports", Map.of());

        assertThat(results).extracting(CallResult::status).containsExactly(Status.OK, Status.FAILED, Status.SKIPPED);
        assertThat(results.get(1).httpStatus()).isEqualTo(500);
        assertThat(results.get(1).responseBody()).contains("E500");
        assertThat(api.calls).hasSize(2);
    }

    @Test
    void goesOnAfterANotFound() throws Exception {
        api.status("ACC2", 404);

        List<CallResult> results = run(config("secret", ""), "exports", Map.of());

        assertThat(results).extracting(CallResult::status).containsExactly(Status.OK, Status.FAILED, Status.OK);
    }

    @Test
    void refreshesTheTokenAndRetriesOnA401() throws Exception {
        ChainRunnerConfig config = config("secret", "");
        api.expireToken();

        List<CallResult> results = run(config, "exports", Map.of());

        assertThat(results).extracting(CallResult::status).containsExactly(Status.OK, Status.OK, Status.OK);
        assertThat(api.tokenGrants).containsExactly("password", "refresh_token");
        assertThat(api.calls).hasSize(4);
    }

    @Test
    void stopsWhenTheTokenIsRefused() throws Exception {
        List<CallResult> results = run(config("wrong", ""), "exports", Map.of());

        assertThat(results).extracting(CallResult::status).containsExactly(Status.FAILED, Status.SKIPPED, Status.SKIPPED);
        assertThat(results.get(0).error()).contains("token refused", "Invalid user credentials",
                "sent: POST " + api.url() + "/token", "grant_type = password", "client_id = 'chainrunner'  (in the form)",
                "client_secret = (empty", "username = 'jdoe'", "password = 5 characters").doesNotContain("wrong");
        assertThat(api.calls).isEmpty();
    }

    @Test
    void usesTheValuesOfAnEarlierResponse() throws Exception {
        String chain = """
                  chain:
                    calls:
                      - name: create
                        method: POST
                        path: /things
                        body: { name: "{{accountIds}}", active: "{{feedback}}" }
                        extract:
                          thingId: $.id
                          where: header:Location
                      - name: "read {{thingId}}"
                        method: GET
                        path: /things/{{thingId}}/x
                """;
        List<CallResult> results = run(config("secret", chain), "chain", Map.of());

        assertThat(results.get(0).extracted()).containsEntry("thingId", 42L).containsEntry("where", "/api/things/42");
        assertThat(results.get(0).request().body()).isEqualTo("{\"name\":[\"ACC1\",\"ACC2\",\"ACC3\"],\"active\":false}");
        assertThat(api.calls).containsExactly("POST /api/things", "GET /api/things/42/x");
    }

    @Test
    void takesAListFromTheCommandLine() throws Exception {
        List<CallResult> results = run(config("secret", ""), "exports", Map.of("accountIds", "ACC7, ACC8"));

        assertThat(results).hasSize(2);
        assertThat(api.calls.get(1)).contains("accountId=ACC8");
    }

    @Test
    void sendsTheClientInABasicHeaderWhenAsked() throws Exception {
        ChainRunnerConfig config = new ConfigLoader(name -> null).parse(Path.of("chainrunner.yaml"), """
                environments:
                  dev:
                    apis:
                      orders: { baseUrl: "%1$s/api" }
                    auth:
                      tokenUrl: %1$s/token
                      clientId: chainrunner
                      clientSecret: " s3cret-value "
                      clientAuth: basic
                      username: jdoe
                      password: secret
                options: { logDir: "" }
                sequences:
                  s: [ { path: /x } ]
                """.formatted(api.url()));

        run(config, "s", Map.of());

        String expected = java.util.Base64.getEncoder().encodeToString("chainrunner:+s3cret-value+".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(api.tokenClients).containsExactly("Basic " + expected + " -");
    }
}
