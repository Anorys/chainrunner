package com.anorys.chainrunner.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateTest {

    private final Map<String, Object> vars = Map.of("today", "2026-12-31", "now", "2026-12-31T23:05:00",
            "days", List.of("2026-01-31", "2026-02-28"), "account", "acc0001");

    @Test
    void formatsDates() {
        // YYYY is the calendar year here, not Java's week-based year (which would give 2027 on 2026-12-31).
        assertThat(Template.text("{{today | date(\"YYYY\")}}-{{today | date(\"MM\")}}", vars)).isEqualTo("2026-12");
        assertThat(Template.text("{{today | date(\"YYYYMMDD\")}}", vars)).isEqualTo("20261231");
        assertThat(Template.text("{{now | date(\"dd/MM/yyyy HH:mm\")}}", vars)).isEqualTo("31/12/2026 23:05");
        assertThat(Template.text("{{today | date}}", vars)).isEqualTo("2026-12-31");
    }

    @Test
    void movesDates() {
        assertThat(Template.text("{{today | plusDays(1)}}", vars)).isEqualTo("2027-01-01");
        assertThat(Template.text("{{today | plusMonths(-1) | date(\"M\")}}", vars)).isEqualTo("11");
        assertThat(Template.value("{{today | date(\"MM\") | number}}", vars)).isEqualTo(12L);
        assertThat(Template.value("{{days | date(\"MM\")}}", vars)).isEqualTo(List.of("01", "02"));
        assertThat(Template.text("{{account | upper}}", vars)).isEqualTo("ACC0001");
    }

    @Test
    void explainsWhatIsWrong() {
        assertThatThrownBy(() -> Template.text("{{account | date(\"YYYY\")}}", vars)).hasMessageContaining("is not a date");
        assertThatThrownBy(() -> Template.text("{{today | format}}", vars)).hasMessageContaining("unknown filter 'format'");
        assertThatThrownBy(() -> Template.text("{{today | plusDays(x)}}", vars)).hasMessageContaining("a number is expected");
        assertThat(Template.preview("{{docId | number}} {{today | date(\"YYYY\")}}", vars)).isEqualTo("{{docId | number}} 2026");
    }
}
