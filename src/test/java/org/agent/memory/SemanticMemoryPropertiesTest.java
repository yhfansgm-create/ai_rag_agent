package org.agent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SemanticMemoryProperties} 的单测。
 * <p>
 * 配置对象看着没什么好测的，但这里的每一条都对应一个<b>会静默出错</b>的场景：
 * 配置写错了不会报错，只会让某个功能"看起来在跑、其实没跑"。
 */
class SemanticMemoryPropertiesTest {

    @Nested
    @DisplayName("默认值与预设")
    class Presets {

        @Test
        @DisplayName("defaults()：L2 与复述验证都开着")
        void defaults() {
            SemanticMemoryProperties props = SemanticMemoryProperties.defaults();

            assertThat(props.enabled()).isTrue();
            assertThat(props.groundingEnabled()).as("★ 复述验证默认必须开").isTrue();
            assertThat(props.quotaPerUser()).isEqualTo(200);
            assertThat(props.recallLimit()).isPositive();
            assertThat(props.distillQueueCapacity()).isPositive();
        }

        @Test
        @DisplayName("★ disabled() 只关 L2，**不**顺手关掉复述验证")
        void disabledKeepsGroundingOn() {
            // 这一条是为了防一个很自然的"顺手"改动：把 disabled() 写成全 false。
            // 后果是"关掉 L2"变成"关掉 L2 且埋下一个关着验证的配置"——
            // 哪天有人把 enabled 改回 true（比如做对比演示），复述验证是关着的，
            // 而这个差异在两个几乎同名的静态工厂里根本看不出来
            SemanticMemoryProperties props = SemanticMemoryProperties.disabled();

            assertThat(props.enabled()).isFalse();
            assertThat(props.groundingEnabled()).as("关 L2 不等于关验证").isTrue();
        }
    }

    @Nested
    @DisplayName("非法值夹取（配置写错不该让助手起不来）")
    class Clamping {

        @Test
        @DisplayName("每个计数项取 0 或负数 → 夹回默认")
        void nonPositiveCountsFallBack() {
            SemanticMemoryProperties props =
                    new SemanticMemoryProperties(true, true, 0, -1, 0, -1, 0, -1, 0);

            assertThat(props.quotaPerUser()).isEqualTo(SemanticMemoryProperties.DEFAULT_QUOTA_PER_USER);
            assertThat(props.recallLimit()).isEqualTo(SemanticMemoryProperties.DEFAULT_RECALL_LIMIT);
            assertThat(props.maxFactsPerBlock())
                    .isEqualTo(SemanticMemoryProperties.DEFAULT_MAX_FACTS_PER_BLOCK);
            assertThat(props.maxLineChars()).isEqualTo(SemanticMemoryProperties.DEFAULT_MAX_LINE_CHARS);
            assertThat(props.maxBlockChars()).isEqualTo(SemanticMemoryProperties.DEFAULT_MAX_BLOCK_CHARS);
            assertThat(props.distillThreads()).isEqualTo(SemanticMemoryProperties.DEFAULT_DISTILL_THREADS);
            assertThat(props.distillQueueCapacity())
                    .isEqualTo(SemanticMemoryProperties.DEFAULT_DISTILL_QUEUE_CAPACITY);
        }

        @Test
        @DisplayName("合法值一律原样保留（夹取不能顺手改掉用户配的数）")
        void legalValuesSurvive() {
            SemanticMemoryProperties props = new SemanticMemoryProperties(
                    false, false, 7, 3, 4, 40, 400, 1, 5);

            assertThat(props).isEqualTo(new SemanticMemoryProperties(
                    false, false, 7, 3, 4, 40, 400, 1, 5));
        }
    }

    @Nested
    @DisplayName("★ groundingEnabled 是演示开关，不做安全兜底")
    class GroundingSwitch {

        @Test
        @DisplayName("显式传 false 就是 false（配置写了什么，行为就是什么）")
        void explicitFalseIsKept() {
            // 这个开关的用途是"关掉它，复现漏洞现场"（照 clearance-filter-enabled 的先例）。
            // 如果这里加一道"安全起见还是置 true"的兜底，那个演示就做不成了 ——
            // 而且更糟：配置里写着 false、行为却是 true，这个开关会变成骗人的
            SemanticMemoryProperties props = new SemanticMemoryProperties(
                    true, false, 200, 10, 10, 80, 800, 2, 200);

            assertThat(props.groundingEnabled()).isFalse();
        }
    }
}
