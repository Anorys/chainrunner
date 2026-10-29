package com.anorys.chainrunner.config;

import com.anorys.chainrunner.config.ChainRunnerConfig.GrantType;
import com.anorys.chainrunner.step.HttpStep;
import com.anorys.chainrunner.step.PollStep;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {

    private final ConfigLoader loader = new ConfigLoader(Map.of("DEV_USER", "jdoe", "EMPTY", "")::get);

    @Test
    void readsTheExampleShippedWithTheTool() {
        ChainRunnerConfig config = new ConfigLoader(name -> null).read(Path.of("chainrunner.example.yaml"));

        assertThat(config.defaultEnvironment()).isEqualTo("dev");
        assertThat(config.environments()).containsOnlyKeys("dev", "acc", "prod");
        assertThat(config.environment("prod").orElseThrow().protectedEnv()).isTrue();
        assertThat(config.environment("dev").orElseThrow().api("orders").auth().tokenUrl())
                .isEqualTo("https://sso-dev.example.com/realms/example/protocol/openid-connect/token");
        assertThat(config.variables().get("accountIds")).isEqualTo(List.of("ACC001", "ACC002", "ACC003"));
        assertThat(((HttpStep) config.sequence("generate").orElseThrow().calls().get(0).step()).expectStatus()).containsExactly(200, 204);
        assertThat(config.options().stopOnServerError()).isTrue();
        assertThat(config.options().stopOnClientError()).isFalse();
    }

    @Test
    void substitutesEnvironmentVariablesWithDefaults() {
        assertThat(loader.substitute("${DEV_USER}")).isEqualTo("jdoe");
        assertThat(loader.substitute("${MISSING:-fallback}")).isEqualTo("fallback");
        assertThat(loader.substitute("${EMPTY:-fallback}")).isEqualTo("fallback");
        assertThat(loader.substitute("${MISSING}")).isEmpty();
        assertThat(loader.substitute("a {{kept}} b")).isEqualTo("a {{kept}} b");
    }

    @Test
    void readsTheDotEnvFile(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(".env"), "# comment\nA=1\nexport B=\"two words\"\nC='x=y'\n");

        assertThat(ConfigLoader.readDotEnv(dir.resolve(".env"))).containsEntry("A", "1")
                .containsEntry("B", "two words").containsEntry("C", "x=y");
    }

    @Test
    void explainsWhatIsWrong() {
        assertThatThrownBy(() -> parse("environments: {}\nsequences: {}"))
                .hasMessageContaining("at least one environment");
        assertThatThrownBy(() -> parse("""
                environments:
                  dev: { apis: { a: { baseUrl: http://x } }, auth: { grantType: magic, tokenUrl: http://t } }
                sequences: { s: { calls: [ { path: /a } ] } }
                """)).hasMessageContaining("grantType");
        assertThatThrownBy(() -> parse("""
                environments:
                  dev: { apis: { a: { baseUrl: http://x } } }
                sequences: { s: { calls: [ { method: FETCH, path: /a } ] } }
                """)).hasMessageContaining("sequences.s.calls[0].method");
        assertThatThrownBy(() -> parse("""
                defaultEnvironment: prod
                environments:
                  dev: { apis: { a: { baseUrl: http://x } } }
                sequences: { s: [ { path: /a } ] }
                """)).hasMessageContaining("defaultEnvironment 'prod'");
    }

    @Test
    void anEnvironmentWithoutAuthSendsNoToken() {
        ChainRunnerConfig config = parse("""
                environments:
                  local: { apis: { a: { baseUrl: http://localhost:8080 } } }
                sequences: { s: [ { path: /a } ] }
                """);

        assertThat(config.environment("local").orElseThrow().api(null).auth().grantType()).isEqualTo(GrantType.NONE);
    }

    @Test
    void readsThePollAndDownloadSteps() {
        ChainRunnerConfig config = new ConfigLoader(name -> null).read(Path.of("chainrunner.example.yaml"));

        ChainRunnerConfig.Sequence full = config.sequence("generate-and-download").orElseThrow();
        assertThat(full.forEach()).containsEntry("accountId", "{{accountIds}}");
        assertThat(full.order()).isEqualTo(ChainRunnerConfig.Order.PHASES);
        ChainRunnerConfig.Call poll = full.calls().get(1);
        assertThat(poll.step()).isInstanceOf(PollStep.class);
        PollStep pollStep = (PollStep) poll.step();
        assertThat(pollStep.sql()).startsWith("select doc_id").contains("account_id = {{accountId}}", "{{today | date(\"MM\")}}");
        assertThat(poll.database()).isEqualTo("orders");
        assertThat(pollStep.every()).isEqualTo(java.time.Duration.ofSeconds(10));
        assertThat(pollStep.timeout()).isEqualTo(java.time.Duration.ofMinutes(15));
        assertThat(pollStep.onTimeout()).isEqualTo(PollStep.OnTimeout.NEXT);
        assertThat(poll.extract()).containsEntry("docId", "doc_id");
        ChainRunnerConfig.Call download = full.calls().get(2);
        HttpStep.Save save = ((HttpStep) download.step()).save();
        assertThat(save.saveTo()).isEqualTo("{{documentDir}}/{{env}}/{{today}}/{{accountId}}-{{docId}}.xml");
        assertThat(save.base64Pointer()).isNull();
        assertThat(download.guard().database()).isEqualTo("document");
        assertThat(full.calls().get(0).guard().database()).isNull();
        assertThat(full.calls().get(0).database()).isEqualTo("orders");
        ChainRunnerConfig.Environment dev = config.environment("dev").orElseThrow();
        assertThat(dev.databases()).containsOnlyKeys("orders", "document");
        assertThat(dev.database("orders").url()).isEqualTo("jdbc:postgresql://localhost:5432/orders");
        assertThat(dev.database("document").url()).isEqualTo("jdbc:postgresql://localhost:5432/document");
    }

    @Test
    void checksThePollSteps() {
        String head = """
                environments:
                  dev: { apis: { a: { baseUrl: http://x } }, databases: { main: { url: "jdbc:postgresql://db/x" } } }
                sequences:
                  s:
                    steps:
                """;
        ChainRunnerConfig config = parse(head + """
                      - { type: poll, sql: "select 1", every: 500ms, timeout: 90, onTimeout: stop, onManyRows: fail }
                      - { path: /d, saveTo: out.pdf, saveAs: "base64:$.data.content", overwrite: true }
                """);
        PollStep poll = (PollStep) config.sequences().get(0).calls().get(0).step();
        assertThat(poll.every()).isEqualTo(java.time.Duration.ofMillis(500));
        assertThat(poll.timeout()).isEqualTo(java.time.Duration.ofSeconds(90));
        assertThat(poll.onTimeout()).isEqualTo(PollStep.OnTimeout.STOP);
        assertThat(poll.onManyRows()).isEqualTo(PollStep.OnManyRows.FAIL);
        assertThat(((HttpStep) config.sequences().get(0).calls().get(1).step()).save().base64Pointer()).isEqualTo("/data/content");

        assertThatThrownBy(() -> parse(head + "      - { type: poll }\n")).hasMessageContaining("sql is required");
        assertThatThrownBy(() -> parse(head + "      - { type: ftp, path: /x }\n"))
                .hasMessage("sequences.s.steps[0].type: 'ftp' is unknown (http, poll, sql, file, command)");
        assertThatThrownBy(() -> parse(head + "      - { type: poll, sql: x, every: soon }\n")).hasMessageContaining("every");
        assertThatThrownBy(() -> parse(head + "      - { type: poll, sql: x, onTimeout: wait }\n")).hasMessageContaining("next, stop");
        assertThatThrownBy(() -> parse(head + "      - { type: soap, sql: x }\n")).hasMessageContaining("http, poll");
        assertThatThrownBy(() -> parse(head + "      - { path: /d, saveTo: x, saveAs: zip }\n")).hasMessageContaining("saveAs");
        assertThatThrownBy(() -> parse("""
                environments:
                  dev: { apis: { a: { baseUrl: http://x } }, databases: { main: { url: "jdbc:mysql://db/x" } } }
                sequences: { s: [ { path: /a } ] }
                """)).hasMessageContaining("jdbc:postgresql://");
    }

    @Test
    void databasesInheritTheEnvironmentDefaultsFieldByField() {
        ChainRunnerConfig config = parse("""
                environments:
                  dev:
                    apis: { a: { baseUrl: http://x } }
                    database: { url: "jdbc:postgresql://db/main", username: reader, password: "${EMPTY}" }
                    databases:
                      main: {}
                      audit: { url: "jdbc:postgresql://db/audit", username: auditor }
                sequences: { s: [ { path: /a } ] }
                """);
        ChainRunnerConfig.Environment dev = config.environment("dev").orElseThrow();

        assertThat(dev.database("main")).isEqualTo(new ChainRunnerConfig.Database("main", "jdbc:postgresql://db/main", "reader", ""));
        assertThat(dev.database("audit").username()).isEqualTo("auditor");
        assertThat(dev.database("audit").needsPassword()).isTrue();
        assertThatThrownBy(() -> dev.database(null)).hasMessageContaining("set 'database:' on the step")
                .hasMessageContaining("main, audit");
        assertThatThrownBy(() -> dev.database("billing")).hasMessageContaining("database 'billing' is not defined");
    }

    @Test
    void theEnvironmentDatabaseAloneIsNotADatabase() {
        assertThatThrownBy(() -> parse("""
                environments:
                  dev: { apis: { a: { baseUrl: http://x } }, database: { url: "jdbc:postgresql://db/x" } }
                sequences: { s: [ { path: /a } ] }
                """)).hasMessageContaining("environments.dev.database only holds values that databases inherit")
                .hasMessageContaining("databases.<name>.url");
        ChainRunnerConfig config = parse("""
                environments:
                  dev: { apis: { a: { baseUrl: http://x } }, database: { username: reader } }
                sequences: { s: [ { path: /a } ] }
                """);
        assertThat(config.environment("dev").orElseThrow().databases()).isEmpty();
    }

    private ChainRunnerConfig parse(String yaml) {
        return loader.parse(Path.of("chainrunner.yaml"), yaml);
    }
}
