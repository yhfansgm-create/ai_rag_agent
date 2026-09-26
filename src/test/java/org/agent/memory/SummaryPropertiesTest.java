package org.agent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SummaryProperties} 的单测。
 * <p>
 * 重点全在 {@code triggerRatio} 上：它的两头都是"功能静默失效"——
 * 配成 1 就永远不触发压缩，配成 0 就每轮都触发。而中间那些合法值又必须原样保留。
 */
class SummaryPropertiesTest {

    @Nested
    @DisplayName("默认值")
    class Defaults {

        @Test
        @DisplayName("defaults()：摘要开着，比例落在 (0,1) 内")
        void defaults() {
            SummaryProperties props = SummaryProperties.defaults();

            assertThat(props.enabled()).isTrue();
            assertThat(props.triggerRatio()).isBetween(0.0, 1.0);
            assertThat(props.maxSummaryChars()).isPositive();
            assertThat(props.budgetChars()).isPositive();
        }

        @Test
        @DisplayName("disabled()：只关摘要（复现「第 3 轮以前整段消失」）")
        void disabled() {
            assertThat(SummaryProperties.disabled().enabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("★ triggerRatio 的夹取：两头都是静默失效")
    class TriggerRatio {

        @Test
        @DisplayName("配成 1.0 或更大 → 夹回默认（否则压缩永远不触发，等于功能被悄悄关掉）")
        void tooLargeFallsBack() {
            // 1.0 的语义是"预算全用满了才压缩" —— 那时压缩已经没有意义，
            // 而这个配置在文件里看着完全正常，没人会觉得它等于关掉功能
            assertThat(propsWith(1.0).triggerRatio()).isEqualTo(SummaryProperties.DEFAULT_TRIGGER_RATIO);
            assertThat(propsWith(2.5).triggerRatio()).isEqualTo(SummaryProperties.DEFAULT_TRIGGER_RATIO);
        }

        @Test
        @DisplayName("配成 0 或负数 → 夹回默认（否则每轮都压缩，把优化变成每轮一次 LLM 调用）")
        void nonPositiveFallsBack() {
            assertThat(propsWith(0.0).triggerRatio()).isEqualTo(SummaryProperties.DEFAULT_TRIGGER_RATIO);
            assertThat(propsWith(-0.1).triggerRatio()).isEqualTo(SummaryProperties.DEFAULT_TRIGGER_RATIO);
        }

        @Test
        @DisplayName("★ NaN → 夹回默认（否则它会静默通过区间检查）")
        void nanFallsBack() {
            // ⚠️ 这是这条夹取写成 !(x > 0) || !(x < 1) 而不是 x <= 0 || x >= 1 的全部理由：
            // NaN 与**任何**值比较都返回 false，所以 <= 和 >= 两种写法都会放它过去，
            // 而一个 NaN 比例在 ContextBudget 里会让判定恒为 false —— 功能静默失效
            assertThat(propsWith(Double.NaN).triggerRatio())
                    .isEqualTo(SummaryProperties.DEFAULT_TRIGGER_RATIO);
        }

        @Test
        @DisplayName("合法值一律原样保留（0.001 与 0.999 都是用户的选择，不该被改掉）")
        void legalValuesSurvive() {
            assertThat(propsWith(0.001).triggerRatio()).isEqualTo(0.001);
            assertThat(propsWith(0.999).triggerRatio()).isEqualTo(0.999);
        }
    }

    @Nested
    @DisplayName("其它项夹取")
    class OtherClamps {

        @Test
        @DisplayName("计数项非正 → 夹回默认")
        void countsFallBack() {
            SummaryProperties props = new SummaryProperties(false, 0, -1, 0.7, 0, -1);

            assertThat(props.maxSummaryChars()).isEqualTo(SummaryProperties.DEFAULT_MAX_SUMMARY_CHARS);
            assertThat(props.budgetChars()).isEqualTo(SummaryProperties.DEFAULT_BUDGET_CHARS);
            assertThat(props.sourceTurnMaxChars())
                    .isEqualTo(SummaryProperties.DEFAULT_SOURCE_TURN_MAX_CHARS);
            assertThat(props.retentionDays()).isEqualTo(SummaryProperties.DEFAULT_RETENTION_DAYS);
        }

        @Test
        @DisplayName("合法值原样保留")
        void legalValuesSurvive() {
            SummaryProperties props = new SummaryProperties(true, 300, 4000, 0.5, 200, 7);

            assertThat(props).isEqualTo(new SummaryProperties(true, 300, 4000, 0.5, 200, 7));
        }
    }

    private static SummaryProperties propsWith(double triggerRatio) {
        return new SummaryProperties(true, 600, 8000, triggerRatio, 500, 30);
    }
}
