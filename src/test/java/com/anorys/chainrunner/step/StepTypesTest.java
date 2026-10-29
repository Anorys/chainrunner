package com.anorys.chainrunner.step;

import com.anorys.chainrunner.config.ChainRunnerConfig;
import com.anorys.chainrunner.config.ChainRunnerConfig.Environment;
import com.anorys.chainrunner.config.ConfigLoader;
import com.anorys.chainrunner.core.CallResult;
import com.anorys.chainrunner.core.CallResult.Request;
import com.anorys.chainrunner.core.CallResult.Status;
import com.anorys.chainrunner.core.Database;
import com.anorys.chainrunner.core.Http;
import com.anorys.chainrunner.core.Planner;
import com.anorys.chainrunner.core.Planner.PlannedCall;
import com.anorys.chainrunner.core.Runner;
import com.anorys.chainrunner.core.TokenClients;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The sql, file and command steps, run by the runner as a sequence would. */
class StepTypesTest {

    @TempDir
    Path dir;

    private final List<String> queries = new ArrayList<>();

    /** A table {@code export_file(id, account_id)} with three rows; {@code where false} finds none. */
    private final Database.Opener fakeDb = (config, timeout) -> new Database() {
        @Override
        public List<Map<String, Object>> query(String sql, List<Object> params) {
            queries.add(sql + " " + params);
            if (sql.contains("where false")) {
                return List.of();
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (long id = 1; id <= 3; id++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", id);
                row.put("account_id", "ACC" + id);
                rows.add(row);
            }
            return rows;
        }

        @Override
        public void close() {
        }
    };

    private ChainRunnerConfig config(String steps) {
        String yaml = """
                environments:
                  dev:
                    databases:
                      orders: { url: "jdbc:postgresql://localhost:5432/orders" }
                options:
                  logDir: ""
                  timeoutSeconds: 10
                variables:
                  who: world
                sequences:
                  s:
                    steps:
                """ + steps.indent(6);
        return new ConfigLoader(name -> null).parse(dir.resolve("chainrunner.yaml"), yaml);
    }

    private List<CallResult> run(String steps) throws Exception {
        ChainRunnerConfig config = config(steps);
        Environment env = config.environment("dev").orElseThrow();
        HttpClient http = Http.client(Duration.ofSeconds(5), false);
        TokenClients tokens = new TokenClients(http, env, Duration.ofSeconds(5), Clock.systemUTC());
        Map<String, Object> vars = Planner.variables(config, env, Map.of());
        List<PlannedCall> calls = Planner.plan(config.sequence("s").orElseThrow(), vars);
        return new Runner(http, tokens, env, config.options(), fakeDb, Function.identity()).run(calls, vars, new Runner.Listener() {
            @Override
            public void started(PlannedCall call, Request request) {
            }

            @Override
            public void finished(PlannedCall call, CallResult result) {
            }
        }, () -> false);
    }

    // ---------------------------------------------------------------- sql

    @Test
    void sqlReadsTheFirstRowOrEveryRow() throws Exception {
        List<CallResult> results = run("""
                - type: sql
                  sql: select id, account_id from export_file where account_id like {{who}}
                  extract:
                    firstId: id
                    accounts: ACCOUNT_ID[]
                - name: "Use {{firstId}}"
                  type: sql
                  sql: select id from export_file where id in ({{accounts}})
                """);
        assertThat(results).extracting(CallResult::status).containsExactly(Status.OK, Status.OK);
        assertThat(results.get(0).extracted()).containsEntry("firstId", 1L).containsEntry("accounts", List.of("ACC1", "ACC2", "ACC3"));
        assertThat(results.get(0).notes()).containsExactly("3 rows");
        assertThat(queries.get(0)).endsWith("like ? [world]");
        assertThat(queries.get(1)).endsWith("in (?, ?, ?) [ACC1, ACC2, ACC3]");
        assertThat(new SqlStep("x", false).outcome(results.get(0))).isEqualTo("3 rows");
    }

    @Test
    void sqlFailsWithoutARowUnlessAllowed() throws Exception {
        List<CallResult> results = run("""
                - { type: sql, sql: "select id from export_file where false", extract: { id: id } }
                - { type: sql, sql: "select id from export_file where false", allowEmpty: true, extract: { ids: "id[]" } }
                - { type: sql, sql: "select nope from export_file", extract: { x: nope } }
                """);
        assertThat(results.get(0).status()).isEqualTo(Status.FAILED);
        assertThat(results.get(0).error()).startsWith("no row");
        assertThat(results.get(0).halt()).isFalse();
        assertThat(results.get(1).status()).isEqualTo(Status.OK);
        assertThat(results.get(1).extracted()).containsEntry("ids", List.of());
        assertThat(results.get(2).error()).isEqualTo("extract x: column 'nope' is not there (columns: id, account_id)");
    }

    // ---------------------------------------------------------------- file

    @Test
    void fileReadsJsonCsvAndText() throws Exception {
        Files.createDirectories(dir.resolve("data"));
        Files.writeString(dir.resolve("data/order.json"), "{\"order\": {\"id\": 42, \"lines\": [1, 2]}, \"ok\": true}");
        Files.writeString(dir.resolve("data/orders.csv"), "﻿id;label;amount\r\n7;\"Chairs; oak\";12.5\r\n8;\"Say \"\"hi\"\"\";\r\n\r\n");
        Files.writeString(dir.resolve("data/world.txt"), "ACC1\n\n  ACC2  \nACC3\n");
        List<CallResult> results = run("""
                - type: file
                  path: data/order.json
                  extract: { orderId: /order/id, lines: $.order.lines, ok: ok }
                - type: file
                  path: data/orders.csv
                  extract: { first: id, ids: "id[]", label: label, amounts: "AMOUNT[]" }
                - type: file
                  path: "data/{{who}}.txt"
                  extract: { accounts: lines, all: content }
                - type: file
                  path: data/order.json
                  format: text
                  extract: { raw: content }
                """);
        assertThat(results).extracting(CallResult::status).containsOnly(Status.OK);
        assertThat(results.get(0).extracted()).containsEntry("orderId", 42L).containsEntry("lines", "[1,2]").containsEntry("ok", true);
        assertThat(results.get(1).extracted()).containsEntry("first", "7").containsEntry("ids", List.of("7", "8"))
                .containsEntry("label", "Chairs; oak").containsEntry("amounts", List.of("12.5"));
        assertThat(results.get(1).notes().get(0)).endsWith("csv, 2 rows");
        assertThat(results.get(2).request().url()).isEqualTo(dir.resolve("data/world.txt").toString());
        assertThat(results.get(2).extracted()).containsEntry("accounts", List.of("ACC1", "ACC2", "ACC3"))
                .containsEntry("all", "ACC1\n\n  ACC2  \nACC3");
        assertThat(results.get(3).extracted().get("raw")).asString().startsWith("{\"order\"");
        assertThat(FileStep.csv("a,b\n\"x, y\",\"q\"\"r\"", (char) 0)).containsExactly(Map.of("a", "x, y", "b", "q\"r"));
    }

    @Test
    void fileFailuresDoNotStopTheSequence() throws Exception {
        Files.writeString(dir.resolve("a.json"), "{\"a\": 1}");
        List<CallResult> results = run("""
                - { type: file, path: missing.csv }
                - { type: file, path: a.json, extract: { b: /b } }
                - { type: file, path: a.json, format: text, extract: { b: words } }
                """);
        assertThat(results).extracting(CallResult::status).containsOnly(Status.FAILED);
        assertThat(results).extracting(CallResult::halt).containsOnly(false);
        assertThat(results.get(0).error()).startsWith("no such file: ").endsWith("missing.csv");
        assertThat(results.get(1).error()).isEqualTo("extract b: /b is not in the file");
        assertThat(results.get(2).error()).contains("'words' is unknown for a text file (content or lines)");
    }

    // ---------------------------------------------------------------- command

    @Test
    void commandRunsThroughTheShellAndChecksTheExitCode() throws Exception {
        List<CallResult> results = run("""
                - name: greet
                  type: command
                  run: "echo hello {{who}}"
                  extract: { greeting: stdout, code: exitCode }
                - { type: command, run: "exit 3", expectExit: [0, 3] }
                - { type: command, run: "exit 4" }
                - { type: command, run: "exit 5", stopOnError: true }
                - { type: command, run: "echo never" }
                """);
        assertThat(results.get(0).status()).isEqualTo(Status.OK);
        assertThat(results.get(0).extracted()).containsEntry("greeting", "hello world").containsEntry("code", 0L);
        assertThat(results.get(0).request().method()).isEqualTo("RUN");
        assertThat(results.get(0).request().url()).contains("echo hello world");
        assertThat(results.get(1).status()).isEqualTo(Status.OK);
        assertThat(results.get(1).notes()).containsExactly("exit code 3");
        assertThat(results.get(2).status()).isEqualTo(Status.FAILED);
        assertThat(results.get(2).error()).isEqualTo("exit code 4 (0 expected)");
        assertThat(results.get(2).halt()).isFalse();
        assertThat(results.get(3).halt()).isTrue();
        assertThat(results.get(4).status()).isEqualTo(Status.SKIPPED);
        assertThat(new CommandStep(null, "x", null, Map.of(), null, null, List.of(0), false).outcome(results.get(2))).isEqualTo("exit 4");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void commandGetsItsInputAndEnvironmentAndGivesJson() throws Exception {
        Files.createDirectories(dir.resolve("work"));
        List<CallResult> results = run("""
                - type: command
                  run: [cat]
                  input: { name: "{{who}}", ids: [1, 2] }
                  extract: { name: /name, second: "/ids/1" }
                - type: command
                  run: [sh, -c, 'echo "$GREETING from $(basename "$PWD")" >&2; echo done']
                  dir: work
                  env: { GREETING: "hi {{who}}" }
                  extract: { err: stderr, out: stdout }
                - { type: command, run: [sleep, "5"], timeout: 300ms }
                - { type: command, run: [no-such-program-here] }
                """);
        assertThat(results.get(0).status()).isEqualTo(Status.OK);
        assertThat(results.get(0).request().body()).isEqualTo("{\"name\":\"world\",\"ids\":[1,2]}");
        assertThat(results.get(0).extracted()).containsEntry("name", "world").containsEntry("second", 2L);
        assertThat(results.get(1).extracted()).containsEntry("err", "hi world from work").containsEntry("out", "done");
        assertThat(results.get(1).responseBody()).isEqualTo("done\n--- stderr ---\nhi world from work");
        assertThat(results.get(2).status()).isEqualTo(Status.FAILED);
        assertThat(results.get(2).error()).isEqualTo("still running after 0:00: stopped");
        assertThat(results.get(2).duration()).isLessThan(Duration.ofSeconds(3));
        assertThat(results.get(3).error()).startsWith("cannot run no-such-program-here");
    }

    // ---------------------------------------------------------------- configuration

    @Test
    void checksTheFieldsOfEachType() {
        assertThatThrownBy(() -> config("- { type: sql }")).hasMessageContaining("sql is required for a sql step");
        assertThatThrownBy(() -> config("- { type: file }")).hasMessageContaining("path is required for a file step");
        assertThatThrownBy(() -> config("- { type: file, path: x, format: xml }")).hasMessageContaining("format: 'xml' is unknown (auto, json, csv, text)");
        assertThatThrownBy(() -> config("- { type: file, path: x, delimiter: '::' }")).hasMessageContaining("one character expected");
        assertThatThrownBy(() -> config("- { type: command }")).hasMessageContaining("run is required for a command step");
        assertThatThrownBy(() -> config("- { type: ftp }")).hasMessageContaining("'ftp' is unknown (http, poll, sql, file, command)");

        ChainRunnerConfig config = config("""
                - { type: command, run: [./notify.sh, "{{who}}"], timeout: 2m, expectExit: 1 }
                - { type: file, path: in.csv, delimiter: ";" }
                """);
        CommandStep command = (CommandStep) config.sequences().get(0).calls().get(0).step();
        assertThat(command.args()).containsExactly("./notify.sh", "{{who}}");
        assertThat(command.expectExit()).containsExactly(1);
        assertThat(config.sequences().get(0).calls().get(0).name()).isEqualTo("./notify.sh");
        assertThat(command.details()).extracting(Step.Detail::label).containsExactly("at most", "exit codes");
        FileStep file = (FileStep) config.sequences().get(0).calls().get(1).step();
        assertThat(file.delimiter()).isEqualTo(';');
        assertThat(config.sequences().get(0).calls().get(1).method()).isEqualTo("FILE");
    }
}
