package org.agent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文预算的单测。
 * <p>
 * 这是全项目<b>唯一一处刻意 fail-open</b> 的判定（见 {@link ContextBudget} 类注释），
 * 所以"什么情况下<b>不</b>触发"比"什么情况下触发"更值得测：配置缺失、预算为 0、
 * 比例写错，都必须安静地不触发 —— 而不是疯狂压缩把内容压没。
 */
class ContextBudgetTest {

    @Nested
    @DisplayName("estimateChars：只数字符，不引入魔法系数")
    class Estimate {

        @Test
        @DisplayName("求和")
        void sums() {
            assertThat(ContextBudget.estimateChars(List.of("对公", "对公业务")))
                    .isEqualTo(2 + 4);
        }

        @Test
        @DisplayName("null 元素按 0 处理（组装上下文时某个块为空是常态）")
        void nullElementsAreZero() {
            assertThat(ContextBudget.estimateChars(Arrays.asList("对公", null, "对私")))
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("null / 空集合 → 0")
        void emptyCollections() {
            assertThat(ContextBudget.estimateChars(null)).isZero();
            assertThat(ContextBudget.estimateChars(List.of())).isZero();
        }

        @Test
        @DisplayName("按码点计数（一个 emoji 算 1）")
        void countsCodePoints() {
            assertThat(ContextBudget.estimateChars(List.of("😀"))).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("shouldCompact：越线才触发")
    class Trigger {

        @Test
        @DisplayName("★ 空上下文永不触发（否则空会话会被反复压缩）")
        void emptyContextNeverTriggers() {
            assertThat(ContextBudget.shouldCompact(0, 1000, 0.5)).isFalse();
            assertThat(ContextBudget.shouldCompact(-1, 1000, 0.5)).isFalse();
        }

        @Test
        @DisplayName("预算未配置 / 为 0 → 不触发")
        void missingBudgetNeverTriggers() {
            assertThat(ContextBudget.shouldCompact(900, 0, 0.5)).isFalse();
            assertThat(ContextBudget.shouldCompact(900, -1, 0.5)).isFalse();
        }

        @Test
        @DisplayName("比例写错（≤0 或 ≥1）→ 不触发（比例 1 意味着「永远差一点」，那是个配置错误不是意图）")
        void badRatioNeverTriggers() {
            assertThat(ContextBudget.shouldCompact(9999, 1000, 0.0)).isFalse();
            assertThat(ContextBudget.shouldCompact(9999, 1000, 1.0)).isFalse();
            assertThat(ContextBudget.shouldCompact(9999, 1000, 1.5)).isFalse();
        }

        @Test
        @DisplayName("越线触发")
        void aboveThreshold() {
            // 比例取 0.5 —— 二进制中可精确表示，边界值才不会有浮点歧义
            assertThat(ContextBudget.shouldCompact(501, 1000, 0.5)).isTrue();
        }

        @Test
        @DisplayName("★ 刚好在线上不算越线（触发的语义是「已经越线」，站在线上不是越线）")
        void exactlyAtThresholdDoesNotTrigger() {
            assertThat(ContextBudget.shouldCompact(500, 1000, 0.5)).isFalse();
        }

        @Test
        @DisplayName("线下一格不触发")
        void belowThreshold() {
            assertThat(ContextBudget.shouldCompact(499, 1000, 0.5)).isFalse();
        }
    }
}
