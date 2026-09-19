package org.agent.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 时效门禁测试：边界（闭区间）、缺失字段放行、脏数据容错。
 */
class TemporalGateTest {

    private final TemporalGate gate = new TemporalGate();
    private static final LocalDate BIZ = LocalDate.of(2026, 9, 19);

    private static Map<String, Object> meta(String from, String until) {
        Map<String, Object> m = new HashMap<>();
        if (from != null) {
            m.put(TemporalGate.META_EFFECTIVE_FROM, from);
        }
        if (until != null) {
            m.put(TemporalGate.META_EFFECTIVE_UNTIL, until);
        }
        return m;
    }

    @Test
    @DisplayName("区间内 → EFFECTIVE")
    void withinRange() {
        assertThat(gate.stateOf(meta("2020-01-01", "2030-12-31"), BIZ)).isEqualTo(TemporalGate.State.EFFECTIVE);
    }

    @Test
    @DisplayName("闭区间：生效当日与失效当日都算有效")
    void boundariesAreInclusive() {
        assertThat(gate.stateOf(meta("2026-09-19", "2030-12-31"), BIZ))
                .as("生效当天").isEqualTo(TemporalGate.State.EFFECTIVE);
        assertThat(gate.stateOf(meta("2020-01-01", "2026-09-19"), BIZ))
                .as("失效当天").isEqualTo(TemporalGate.State.EFFECTIVE);
    }

    @Test
    @DisplayName("早于生效日 → NOT_YET；晚于失效日 → EXPIRED")
    void outsideRange() {
        assertThat(gate.stateOf(meta("2027-01-01", null), BIZ)).isEqualTo(TemporalGate.State.NOT_YET);
        assertThat(gate.stateOf(meta(null, "2026-09-18"), BIZ)).isEqualTo(TemporalGate.State.EXPIRED);
    }

    @Test
    @DisplayName("只声明单边也能判定")
    void singleSided() {
        assertThat(gate.stateOf(meta("2020-01-01", null), BIZ)).isEqualTo(TemporalGate.State.EFFECTIVE);
        assertThat(gate.stateOf(meta(null, "2030-12-31"), BIZ)).isEqualTo(TemporalGate.State.EFFECTIVE);
    }

    @Test
    @DisplayName("缺时效字段 → UNDATED 且放行（避免存量文档整体检索不到）")
    void missingFields_passThrough() {
        assertThat(gate.stateOf(meta(null, null), BIZ)).isEqualTo(TemporalGate.State.UNDATED);
        assertThat(gate.usable(meta(null, null), BIZ)).isTrue();
        assertThat(gate.usable(new HashMap<>(), BIZ)).isTrue();
    }

    @Test
    @DisplayName("区间不自洽(from > until) → 按未声明放行，不把文档判死")
    void contradictoryRange_treatedAsUndated() {
        assertThat(gate.stateOf(meta("2030-01-01", "2020-01-01"), BIZ)).isEqualTo(TemporalGate.State.UNDATED);
    }

    @Test
    @DisplayName("容忍三种日期写法：ISO 字符串 / LocalDate / SnakeYAML 的 Date")
    void acceptsMultipleDateForms() {
        assertThat(TemporalGate.parse("2024-01-01")).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(TemporalGate.parse(LocalDate.of(2024, 1, 2))).isEqualTo(LocalDate.of(2024, 1, 2));
        assertThat(TemporalGate.parse(Date.from(LocalDate.of(2024, 1, 3).atStartOfDay(ZoneOffset.UTC).toInstant())))
                .isEqualTo(LocalDate.of(2024, 1, 3));
    }

    @Test
    @DisplayName("无法解析的日期 → null，按未声明处理（脏数据不判死）")
    void unparsableDate_isNull() {
        assertThat(TemporalGate.parse("不是日期")).isNull();
        assertThat(TemporalGate.parse("")).isNull();
        assertThat(TemporalGate.parse(null)).isNull();
    }

    @Test
    @DisplayName("iso() 输出 ISO-8601，null 保持 null")
    void isoFormat() {
        assertThat(TemporalGate.iso(LocalDate.of(2026, 9, 19))).isEqualTo("2026-09-19");
        assertThat(TemporalGate.iso(null)).isNull();
    }
}
