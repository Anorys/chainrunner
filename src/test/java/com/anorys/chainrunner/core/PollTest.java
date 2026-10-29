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
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The v2 sequence: export calls, then a poll per account until the file exists, then the download. */
class PollTest {

    @TempDir
    Path dir;

    private FakeApi api;
    /** OA number → how many queries return nothing before its row appears; absent: never. */
    private final Map<String, Integer> appearsAfter = new HashMap<>();
    private final Map<String, Integer> queried = new HashMap<>();
    private final List<List<Object>> params = Collections.synchronizedList(new ArrayList<>());
    private int opened;
    private boolean closed;

    @BeforeEach
    void start() throws Exception {
        api = new FakeApi();
    }

    @AfterEach
    void stop() {
        api.close();
    }

    /** A table {@code export_file(account_id, doc_id, created)} that fills up as the queries come. */
    private final Database.Opener fakeDb = (config, timeout) -> {
        opened++;
        return new Database() {
            @Override
            public List<Map<String, Object>> query(String sql, List<Object> p) throws SQLException {
                if (sql.contains("missing_table")) {
                    throw new SQLException("relation \"missing_table\" does not exist", "42P01");
                }
                params.add(p);
                String account = String.valueOf(p.get(0));
                int n = queried.merge(account, 1, Integer::sum);
                Integer after = appearsAfter.get(account);
                if (after == null || n <= after) {
                    return List.of();
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("docid", "D-" + account);
                row.put("created", "2026-10-28 10:00:00");
                return List.of(row);
            }

            @Override
            public void close() {
                closed = true;
            }
        };
    };

    private ChainRunnerConfig config(String sequenceOptions, String pollOptions, String download) {
        String yaml = """
                environments:
                  dev:
                    apis:
                      orders: { baseUrl: "%1$s/api" }
                    auth:
                      tokenUrl: %1$s/token
                      clientId: chainrunner
                      username: jdoe
                      password: secret
                    databases:
                      orders:
                        url: jdbc:postgresql://localhost:5432/orders
                        username: reader
                        password: x
                options:
                  logDir: ""
                variables:
                  accountIds: [ACC1, ACC2, ACC3]
                sequences:
                  exports:
                    forEach:
                      accountId: "{{accountIds}}"
                    %2$s
                    steps:
                      - name: "Export {{accountId}}"
                        method: POST
                        path: /orders/exports
                        query: { accountId: "{{accountId}}" }
                      - name: "Wait {{accountId}}"
                        type: poll
                        sql: |
                          -- the file of the day
                          select doc_id as docId, created from export_file
                          where account_id = {{accountId}} and created::date = {{today}}
                        every: 10ms
                        %3$s
                        extract:
                          docId: docid
                %4$s
                """.formatted(api.url(), sequenceOptions, pollOptions, download);
        return new ConfigLoader(name -> null).parse(dir.resolve("chainrunner.yaml"), yaml);
    }

    private static final String DOWNLOAD = """
                  - name: "Document {{accountId}}"
                    method: GET
                    path: /documents/{{docId}}
                    saveTo: "out/{{accountId}}-{{docId}}.xml"
                    saveAs: base64:/content
                    extract:
                      fileName: /fileName
            """;

    private final List<String> progress = Collections.synchronizedList(new ArrayList<>());

    private List<CallResult> run(ChainRunnerConfig config) throws Exception {
        Environment env = config.environment("dev").orElseThrow();
        HttpClient http = Http.client(Duration.ofSeconds(5), false);
        TokenClients tokens = new TokenClients(http, env, Duration.ofSeconds(5), Clock.systemUTC());
        Map<String, Object> vars = Planner.variables(config, env, Map.of());
        List<PlannedCall> calls = Planner.plan(config.sequence("exports").orElseThrow(), vars);
        return new Runner(http, tokens, env, config.options(), fakeDb, java.util.function.Function.identity()).run(calls, vars, new Runner.Listener() {
            @Override
            public void started(PlannedCall call, Request request) {
            }

            @Override
            public void progress(PlannedCall call, CallResult running) {
                progress.add(call.name() + " " + running.notes());
            }

            @Override
            public void finished(PlannedCall call, CallResult result) {
            }
        }, () -> false);
    }

    @Test
    void waitsForEachFileThenDownloadsIt() throws Exception {
        appearsAfter.put("ACC1", 2);
        appearsAfter.put("ACC2", 0);
        appearsAfter.put("ACC3", 1);

        List<CallResult> results = run(config("", "timeout: 5s", DOWNLOAD));

        assertThat(results).extracting(CallResult::status).containsOnly(Status.OK).hasSize(9);
        // Phases: the three export calls, the three polls, the three downloads.
        assertThat(api.calls.subList(0, 3)).allMatch(c -> c.startsWith("POST "));
        assertThat(api.calls.subList(3, 6)).containsExactly(
                "GET /api/documents/D-ACC1", "GET /api/documents/D-ACC2", "GET /api/documents/D-ACC3");
        CallResult poll = results.get(3);
        assertThat(poll.extracted()).containsEntry("docId", "D-ACC1");
        assertThat(poll.notes().get(0)).startsWith("found at attempt 3");
        assertThat(poll.request().sql()).isTrue();
        assertThat(poll.request().body()).contains("account_id = ? and created::date = ?").doesNotContain("{{");
        assertThat(params.get(0)).containsExactly("ACC1", LocalDate.now().toString());
        assertThat(progress).anyMatch(p -> p.startsWith("Wait ACC1 [attempt 2"));
        assertThat(Files.readAllBytes(dir.resolve("out/ACC2-D-ACC2.xml"))).isEqualTo(FakeApi.document("D-ACC2"));
        assertThat(results.get(7).notes().get(0)).startsWith("file: ").endsWith("ACC2-D-ACC2.xml (56 B)");
        assertThat(results.get(7).responseBody()).contains("<base64, ").doesNotContain(java.util.Base64.getEncoder().encodeToString(FakeApi.document("D-ACC2")));
        assertThat(results.get(8).extracted()).containsEntry("fileName", "export-D-ACC3.xml");
        assertThat(opened).isEqualTo(1);
        assertThat(closed).isTrue();
    }

    @Test
    void aFileThatNeverComesSkipsOnlyItsDownload() throws Exception {
        appearsAfter.put("ACC1", 0);
        appearsAfter.put("ACC3", 0);

        List<CallResult> results = run(config("", "timeout: 80ms", DOWNLOAD));

        assertThat(results).extracting(CallResult::status).containsExactly(
                Status.OK, Status.OK, Status.OK,
                Status.OK, Status.FAILED, Status.OK,
                Status.OK, Status.SKIPPED, Status.OK);
        assertThat(results.get(4).error()).startsWith("no row after");
        assertThat(results.get(4).halt()).isFalse();
        assertThat(results.get(7).error()).isEqualTo("not run: 'Wait ACC2' failed");
        assertThat(dir.resolve("out/ACC3-D-ACC3.xml")).exists();
    }

    @Test
    void stopsTheSequenceWhenAskedTo() throws Exception {
        appearsAfter.put("ACC1", 0);

        List<CallResult> results = run(config("", "timeout: 50ms\n        onTimeout: stop", DOWNLOAD));

        assertThat(results).extracting(CallResult::status).containsExactly(
                Status.OK, Status.OK, Status.OK,
                Status.OK, Status.FAILED, Status.SKIPPED,
                Status.SKIPPED, Status.SKIPPED, Status.SKIPPED);
        assertThat(results.get(5).error()).contains("step 5 stopped the sequence");
    }

    @Test
    void oneValueAtATimeWithOrderEach() throws Exception {
        appearsAfter.put("ACC1", 0);
        appearsAfter.put("ACC2", 0);
        appearsAfter.put("ACC3", 0);

        List<CallResult> results = run(config("order: each", "timeout: 1s", DOWNLOAD));

        assertThat(results).extracting(CallResult::status).containsOnly(Status.OK);
        assertThat(api.calls).hasSize(6);
        assertThat(api.calls.get(0)).startsWith("POST ").endsWith("accountId=ACC1");
        assertThat(api.calls.get(1)).isEqualTo("GET /api/documents/D-ACC1");
        assertThat(api.calls.get(2)).startsWith("POST ").endsWith("accountId=ACC2");
    }

    @Test
    void savesARawDownloadAndKeepsAnExistingFile() throws Exception {
        appearsAfter.put("ACC1", 0);
        appearsAfter.put("ACC2", 0);
        appearsAfter.put("ACC3", 0);
        Files.createDirectories(dir.resolve("raw"));
        Files.writeString(dir.resolve("raw/D-ACC2.xml"), "keep me");
        String raw = """
                  - name: "File {{accountId}}"
                    method: GET
                    path: /files/{{docId}}
                    saveTo: "raw/{{docId}}.xml"
            """;

        List<CallResult> results = run(config("", "timeout: 1s", raw));

        assertThat(results.subList(6, 9)).extracting(CallResult::status).containsExactly(Status.OK, Status.FAILED, Status.OK);
        assertThat(results.get(7).error()).startsWith("the file already exists");
        assertThat(results.get(7).halt()).isFalse();
        assertThat(Files.readString(dir.resolve("raw/D-ACC2.xml"))).isEqualTo("keep me");
        assertThat(Files.readAllBytes(dir.resolve("raw/D-ACC1.xml"))).isEqualTo(FakeApi.document("D-ACC1"));
        assertThat(results.get(6).responseBody()).isEqualTo("(56 B received)");
    }

    @Test
    void aQueryErrorStopsTheSequence() throws Exception {
        ChainRunnerConfig broken = new ConfigLoader(name -> null).parse(dir.resolve("chainrunner.yaml"), """
                environments:
                  dev:
                    apis:
                      orders: { baseUrl: "%1$s/api" }
                    auth: { grantType: none }
                    databases: { main: { url: "jdbc:postgresql://localhost/x" } }
                sequences:
                  exports:
                    steps:
                      - name: poll
                        type: poll
                        sql: select doc_id from missing_table where account_id = {{account}}
                      - name: after
                        path: /documents/1
                variables:
                  account: ACC1
                """.formatted(api.url()));

        List<CallResult> results = run(broken);

        assertThat(results).extracting(CallResult::status).containsExactly(Status.FAILED, Status.SKIPPED);
        assertThat(results.get(0).error()).isEqualTo("query refused: relation \"missing_table\" does not exist [42P01]");
    }
}
