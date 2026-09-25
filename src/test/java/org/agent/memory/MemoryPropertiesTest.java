package org.agent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯测试：不打桩、不起 Spring 上下文。
 * <p>
 * 这些回退规则看着琐碎，但每一条都对应一个真实的配置错误：
 * {@code window-turns=0} 会让"最近 N 轮"变成"一轮都不要"（静默失忆），
 * {@code max-chars-per-message} 的负值若被当成"不截断"会撑爆 prompt 预算。
 * 夹取规则有测试兜着，配错就是可预期的退化，而不是运行期的怪现象。
 */
@DisplayName("MemoryProperties：配置校验与回退")
class MemoryPropertiesTest {

    @Test
    @DisplayName("默认值：开启 / 3 轮 / 2000 字符 / 30 天")
    void defaults() {
        MemoryProperties p = MemoryProperties.defaults();

        assertThat(p.enabled()).isTrue();
        assertThat(p.windowTurns()).isEqualTo(3);
        assertThat(p.maxCharsPerMessage()).isEqualTo(2000);
        assertThat(p.retentionDays()).isEqualTo(30);
    }

    @Test
    @DisplayName("windowTurns<=0 → 回退默认（配错不能静默变成无记忆）")
    void invalidWindowTurnsFallsBack() {
        assertThat(new MemoryProperties(true, 0, 2000, 30).windowTurns()).isEqualTo(3);
        assertThat(new MemoryProperties(true, -5, 2000, 30).windowTurns()).isEqualTo(3);
    }

    @Test
    @DisplayName("maxCharsPerMessage：负数回退默认，0 是合法值表示不截断")
    void maxCharsSemantics() {
        assertThat(new MemoryProperties(true, 3, -1, 30).maxCharsPerMessage()).isEqualTo(2000);

        MemoryProperties noTruncate = new MemoryProperties(true, 3, 0, 30);
        assertThat(noTruncate.maxCharsPerMessage()).isZero();
        assertThat(noTruncate.truncateEnabled()).isFalse();
    }

    @Test
    @DisplayName("retentionDays：负数回退默认，0 表示不设过期")
    void retentionSemantics() {
        assertThat(new MemoryProperties(true, 3, 2000, -1).retentionDays()).isEqualTo(30);

        MemoryProperties noExpiry = new MemoryProperties(true, 3, 2000, 0);
        assertThat(noExpiry.retentionDays()).isZero();
        assertThat(noExpiry.retentionEnabled()).isFalse();
    }

    @Test
    @DisplayName("promptRowLimit = windowTurns × 2（一轮两行）")
    void promptRowLimit() {
        assertThat(new MemoryProperties(true, 3, 2000, 30).promptRowLimit()).isEqualTo(6);
        assertThat(new MemoryProperties(true, 1, 2000, 30).promptRowLimit()).isEqualTo(2);
    }

    @Test
    @DisplayName("enabled=false 时其余字段仍被规整（关掉也要是个合法对象）")
    void disabledStillNormalized() {
        MemoryProperties off = new MemoryProperties(false, 0, -1, -1);

        assertThat(off.enabled()).isFalse();
        assertThat(off.windowTurns()).isEqualTo(3);
        assertThat(off.maxCharsPerMessage()).isEqualTo(2000);
        assertThat(off.retentionDays()).isEqualTo(30);
    }
}
