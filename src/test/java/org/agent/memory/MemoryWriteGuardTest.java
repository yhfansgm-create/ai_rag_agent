package org.agent.memory;

import org.agent.permission.SessionEnvelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯测试：不打桩、不起 Spring 上下文。本类是纯函数，密级公式就是全部被测内容。
 * <p>
 * 重点在两处容易被"优化"掉的语义：
 * <ul>
 *   <li><b>必须是 max 而不是 min</b> —— 见 {@link #takesMaxNotMin()}</li>
 *   <li><b>不用会话密级夹上限</b> —— 见 {@link #doesNotCapByEnvelopeLevel()}</li>
 * </ul>
 * 这两个方向都<b>偏严</b>。反过来（偏松）就会变成 D2「洗密级」通道：
 * 把密级 4 的内容标成 2 存下来，降级用户下次就能读到。
 */
@DisplayName("MemoryWriteGuard：本轮密级 = max(引用资料密级)，无资料回退会话密级")
class MemoryWriteGuardTest {

    @Test
    @DisplayName("有资料：取该轮引用资料密级的最大值")
    void takesMaxOfCitedDocs() {
        assertThat(MemoryWriteGuard.clearanceFor(
                List.of(doc(1), doc(3), doc(2)), envelope(5)))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("有资料：单篇时就是它自己的密级")
    void singleDoc() {
        assertThat(MemoryWriteGuard.clearanceFor(List.of(doc(4)), envelope(5))).isEqualTo(4);
    }

    @Test
    @DisplayName("无资料：回退会话密级（无从判定来源，按用户能看到的天花板封顶）")
    void fallsBackToEnvelopeWhenNoDocs() {
        assertThat(MemoryWriteGuard.clearanceFor(List.of(), envelope(5))).isEqualTo(5);
        assertThat(MemoryWriteGuard.clearanceFor(null, envelope(5))).isEqualTo(5);
        assertThat(MemoryWriteGuard.clearanceFor(List.of(), envelope(2))).isEqualTo(2);
    }

    @Test
    @DisplayName("个别资料缺 doc_level：忽略该篇，用其余篇取 max")
    void ignoresDocsWithoutLevel() {
        assertThat(MemoryWriteGuard.clearanceFor(
                Arrays.asList(doc(null), doc(3), doc(null)), envelope(5)))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("全部资料都读不出密级：回退会话密级（而不是当成 0）")
    void fallsBackWhenNoDocHasReadableLevel() {
        assertThat(MemoryWriteGuard.clearanceFor(
                Arrays.asList(doc(null), doc("不是数字")), envelope(5)))
                .isEqualTo(5);
    }

    @Test
    @DisplayName("脏值：0 / 负数 / 字符串 不参与取 max；离群高值 6 原样保留（越严越安全）")
    void handlesDirtyLevels() {
        // 0 与负数不是合法密级。若当成 0 参与比较，结果会被拉到 0 —— 那是偏松的方向。
        // 正确处理是"不参与"，于是要么取到别人，要么回退会话密级
        assertThat(MemoryWriteGuard.clearanceFor(
                List.of(doc(0), doc(-1), doc(2)), envelope(5)))
                .isEqualTo(2);
        assertThat(MemoryWriteGuard.clearanceFor(
                List.of(doc(0), doc(-1)), envelope(4)))
                .isEqualTo(4);

        // 字符串 "4" 读不出数值（docLevelOf 只认 Number），同上
        assertThat(MemoryWriteGuard.clearanceFor(
                List.of(doc("4"), doc(2)), envelope(5)))
                .isEqualTo(2);

        // 6 高于合法上限，但方向是"更严"：存 6 意味着谁都读不到这轮。
        // 刻意不夹成 5 —— 夹取就是把脏数据往松的方向掰
        assertThat(MemoryWriteGuard.clearanceFor(List.of(doc(6)), envelope(5))).isEqualTo(6);
    }

    @Test
    @DisplayName("列表里混入 null 元素：跳过，不影响其余资料")
    void skipsNullElements() {
        List<Document> docs = new ArrayList<>();
        docs.add(null);
        docs.add(doc(3));
        docs.add(null);

        assertThat(MemoryWriteGuard.clearanceFor(docs, envelope(5))).isEqualTo(3);
    }

    @Test
    @DisplayName("★ 取 max 而不是 min：用户密级 2 + 资料密级 4 → 4（D2 洗密级通道的直接断言）")
    void takesMaxNotMin() {
        // 这是文档里那个被标成"✗ 错！"的例子。min(2,4)=2 会把密级 4 的内容标成 2。
        // 干净数据走不到这里（门禁保证 doc_level <= 会话密级），所以 min 是个空操作；
        // 它只在 metadata 脏数据时生效，而生效方向恰好是不安全的那一侧
        assertThat(MemoryWriteGuard.clearanceFor(List.of(doc(4)), envelope(2))).isEqualTo(4);
    }

    @Test
    @DisplayName("★ 刻意不按会话密级夹上限：资料密级高于会话密级时，存的是资料那一侧")
    void doesNotCapByEnvelopeLevel() {
        assertThat(MemoryWriteGuard.clearanceFor(
                List.of(doc(3), doc(4)), envelope(2)))
                .isEqualTo(4);
    }

    @Test
    @DisplayName("会话密级 5 且无资料 → 5（上界一侧的兜底）")
    void topClearanceFallback() {
        assertThat(MemoryWriteGuard.clearanceFor(List.of(), envelope(5))).isEqualTo(5);
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    /** {@code level} 传 {@code null} 表示"这篇没有 doc_level 列" */
    private static Document doc(Object level) {
        Map<String, Object> m = new HashMap<>();
        m.put("kb_id", "kb-teller");
        m.put("doc_id", "DOC-1");
        if (level != null) {
            m.put("doc_level", level);
        }
        return new Document("正文", m);
    }

    private static SessionEnvelope envelope(int clearance) {
        return new SessionEnvelope("s1", "10086", "TELLER", "T3", "0101", "0100/0101",
                clearance, List.of("kb-teller"), 7L, "MOCK", null,
                "kb_id in ['kb-teller'] && doc_level <= " + clearance);
    }
}
