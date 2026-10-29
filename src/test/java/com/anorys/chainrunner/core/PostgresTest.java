package com.anorys.chainrunner.core;

import com.anorys.chainrunner.config.ChainRunnerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Against a real PostgreSQL, when {@code CHAINRUNNER_TEST_PG_URL} (and {@code CHAINRUNNER_TEST_PG_USER},
 * {@code CHAINRUNNER_TEST_PG_PASSWORD}) are set; skipped otherwise.
 */
@EnabledIfEnvironmentVariable(named = "CHAINRUNNER_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class PostgresTest {

    private Database open() throws SQLException {
        return Database.postgres(new ChainRunnerConfig.Database("main", System.getenv("CHAINRUNNER_TEST_PG_URL"),
                System.getenv("CHAINRUNNER_TEST_PG_USER"), System.getenv("CHAINRUNNER_TEST_PG_PASSWORD")), Duration.ofSeconds(10));
    }

    @Test
    void bindsTextToDatesAndNumbers() throws Exception {
        try (Database db = open()) {
            Database.Statement s = Database.Statement.of(
                    "select {{n}}::int + 1 as next, {{day}}::date = current_date as today, {{codes}} as codes",
                    Map.of("n", "41", "day", LocalDate.now().toString(), "codes", "ACC1"));
            List<Map<String, Object>> rows = db.query(s.sql(), s.params());

            assertThat(rows).containsExactly(Map.of("next", 42L, "today", true, "codes", "ACC1"));
        }
    }

    @Test
    void refusesToWrite() throws Exception {
        try (Database db = open()) {
            assertThatThrownBy(() -> db.query("with d as (delete from pg_catalog.pg_class where false returning 1) select * from d", List.of()))
                    .isInstanceOf(SQLException.class).hasMessageContaining("read-only");
            // The connection is still usable after the refusal.
            assertThat(db.query("select 1 as one", List.of())).containsExactly(Map.of("one", 1L));
        }
    }
}
