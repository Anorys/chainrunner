package com.anorys.chainrunner.core;

import com.anorys.chainrunner.core.Database.Statement;
import com.anorys.chainrunner.core.Template.UnknownVariableException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatementTest {

    @Test
    void bindsTheVariablesAsParameters() {
        Statement s = Statement.of("select docId from table1 where account = {{accountId}} and date = {{ today }}",
                Map.of("accountId", "ACC'1", "today", "2026-10-28"));

        assertThat(s.sql()).isEqualTo("select docId from table1 where account = ? and date = ?");
        assertThat(s.params()).containsExactly("ACC'1", "2026-10-28");
        assertThat(s.shown()).isEqualTo("select docId from table1 where account = 'ACC''1' and date = '2026-10-28'");
    }

    @Test
    void expandsAListForIn() {
        Statement s = Statement.of("select 1 from t where status in ({{statuses}}) and n = {{n}}",
                Map.of("statuses", List.of("DONE", "SENT"), "n", 3L));

        assertThat(s.sql()).isEqualTo("select 1 from t where status in (?, ?) and n = ?");
        assertThat(s.params()).containsExactly("DONE", "SENT", 3L);
        assertThat(s.shown()).endsWith("in ('DONE', 'SENT') and n = 3");
    }

    @Test
    void acceptsOnlyQueriesThatRead() {
        assertThat(Statement.of("-- comment\n/* block */ WITH x as (select 1) select * from x", Map.of()).sql()).startsWith("--");
        assertThatThrownBy(() -> Statement.of("delete from t", Map.of()))
                .hasMessageContaining("only select").hasMessageContaining("delete");
        assertThatThrownBy(() -> Statement.of("select 1 where a = {{missing}}", Map.of()))
                .isInstanceOf(UnknownVariableException.class);
    }

    @Test
    void appliesTheFiltersBeforeBinding() {
        Statement s = Statement.of("select docID from t where exportyear = {{today | date(\"YYYY\")}}"
                        + " and exportmonth = {{ today | date('MM') }} and prev = {{today|plusMonths(-1)|date(\"MM\")|number}}",
                Map.of("today", "2026-01-15"));

        assertThat(s.sql()).isEqualTo("select docID from t where exportyear = ? and exportmonth = ? and prev = ?");
        assertThat(s.params()).containsExactly("2026", "01", 12L);
    }

    @Test
    void ignoresQuotesAroundAPlaceholder() {
        Statement s = Statement.of("select id from export_file where account_id = '{{account}}' and y = {{today | date(\"YYYY\") | number}}",
                Map.of("account", 100L, "today", "2026-10-28"));

        assertThat(s.sql()).isEqualTo("select id from export_file where account_id = ? and y = ?");
        assertThat(s.params()).containsExactly(100L, 2026L);
    }
}
