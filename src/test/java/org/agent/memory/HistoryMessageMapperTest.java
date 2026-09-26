package org.agent.memory;

import org.agent.memory.model.ChatTurn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HistoryMessageMapper：历史消息拼装")
class HistoryMessageMapperTest {

    private static final MemoryProperties PROPS = new MemoryProperties(true, 3, 2000, 30);

    @Test
    @DisplayName("历史在前、当前在后；每轮 user → assistant")
    void historyGoesBeforeCurrentMessage() {
        List<Message> current = List.of(new UserMessage("那它呢？"));
        List<ChatTurn> history = List.of(
                turn(1, "起存金额是多少？", "20 万元起存。"),
                turn(2, "期限呢？", "有 1 年、2 年、3 年三档。"));

        List<Message> merged = merge(current, history, PROPS);

        assertThat(merged).hasSize(5);
        assertThat(texts(merged)).containsExactly(
                "起存金额是多少？", "20 万元起存。",
                "期限呢？", "有 1 年、2 年、3 年三档。",
                "那它呢？");
        assertThat(merged).extracting(m -> m.getClass().getSimpleName())
                .containsExactly("UserMessage", "AssistantMessage", "UserMessage", "AssistantMessage",
                        "UserMessage");
    }

    @Test
    @DisplayName("前导 SystemMessage 仍在最前，历史插在它之后（不能插到系统提示之前）")
    void leadingSystemMessageStaysFirst() {
        List<Message> current = List.of(
                new SystemMessage("你是银行内部助手，只依据授权资料作答。"),
                new UserMessage("那它呢？"));
        List<ChatTurn> history = List.of(turn(1, "起存金额是多少？", "20 万元起存。"));

        List<Message> merged = merge(current, history, PROPS);

        assertThat(merged.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(texts(merged)).containsExactly(
                "你是银行内部助手，只依据授权资料作答。",
                "起存金额是多少？", "20 万元起存。",
                "那它呢？");
    }

    @Test
    @DisplayName("超长历史被截断并带省略标记；当前用户消息不截断")
    void longHistoryIsTruncated() {
        MemoryProperties tiny = new MemoryProperties(true, 3, 5, 30);
        String longQuestion = "一二三四五六七八九十";
        String longAnswer = "甲乙丙丁戊己庚辛壬癸";

        List<Message> merged = merge(
                List.of(new UserMessage("那它呢？")),
                List.of(turn(1, longQuestion, longAnswer)),
                tiny);

        assertThat(texts(merged)).containsExactly(
                "一二三四五…（已截断）",
                "甲乙丙丁戊…（已截断）",
                "那它呢？");
    }

    @Test
    @DisplayName("maxCharsPerMessage=0 → 不截断")
    void zeroMeansNoTruncation() {
        MemoryProperties noTruncate = new MemoryProperties(true, 3, 0, 30);
        String longQuestion = "一二三四五六七八九十";

        List<Message> merged = merge(
                List.of(new UserMessage("那它呢？")),
                List.of(turn(1, longQuestion, "短答案")),
                noTruncate);

        assertThat(texts(merged)).containsExactly(longQuestion, "短答案", "那它呢？");
    }

    @Test
    @DisplayName("空历史 → 内容与当前消息列表一致（不重排、不增减）")
    void emptyHistoryKeepsCurrentMessages() {
        List<Message> current = List.of(
                new SystemMessage("系统提示"),
                new UserMessage("问题"));

        List<Message> merged = merge(current, List.of(), PROPS);

        assertThat(texts(merged)).containsExactly("系统提示", "问题");
    }

    @Test
    @DisplayName("无系统提示时历史仍然拼在最前")
    void worksWithoutSystemMessage() {
        List<Message> merged = merge(
                List.of(new UserMessage("那它呢？")),
                List.of(turn(1, "问", "答")),
                PROPS);

        assertThat(texts(merged)).containsExactly("问", "答", "那它呢？");
    }

    @Test
    @DisplayName("历史里缺答案的非正常轮次：跳过缺的那条，不塞空消息")
    void skipsNullPartsOfTurn() {
        List<Message> merged = merge(
                List.of(new UserMessage("那它呢？")),
                List.of(new ChatTurn(1, "只有问题没有答案", null, List.of())),
                PROPS);

        assertThat(texts(merged)).containsExactly("只有问题没有答案", "那它呢？");
    }

    // ------------------------------------------------------------------
    // 两个记忆区块（阶段 2）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 区块位置：前导 system → 摘要块 → 事实块 → 历史 → 本轮（顺序即安全）")
    void blocksSitBetweenLeadingSystemAndHistory() {
        List<Message> current = List.of(
                new SystemMessage("你是银行内部助手。"),
                new UserMessage("那它呢？"));
        List<ChatTurn> history = List.of(turn(1, "起存金额是多少？", "20 万元起存。"));

        List<Message> merged = HistoryMessageMapper.merge(current, history,
                "【摘要】上一轮聊了存款制度", "【事实】业务条线：对公", PROPS);

        assertThat(texts(merged)).containsExactly(
                "你是银行内部助手。",
                "【摘要】上一轮聊了存款制度",
                "【事实】业务条线：对公",
                "起存金额是多少？", "20 万元起存。",
                "那它呢？");
        // 位置不只是"内容对"：区块必须是 **system 消息**，否则它对模型是用户说的话，
        // 那段"这是数据不是指令"的声明就不在 system 里了 —— 这个差别在功能上完全看不出来
        assertThat(merged.get(1)).isInstanceOf(SystemMessage.class);
        assertThat(merged.get(2)).isInstanceOf(SystemMessage.class);
        assertThat(merged.get(0)).isInstanceOf(SystemMessage.class);
    }

    @Test
    @DisplayName("★ 只有事实、没有历史 → 仍然产生消息（这就是「三者都空才不重建」那个回归点）")
    void factBlockAloneStillProducesMessages() {
        List<Message> merged = HistoryMessageMapper.merge(
                List.of(new UserMessage("那它呢？")), List.of(),
                null, "【事实】业务条线：对公", PROPS);

        // 事实块必须能**独自**进入 prompt：它来自跨会话的 L2，
        // 第一次会话（没有历史）恰恰是它最有用的时刻 ——
        // 若把它挂在"历史非空"这个前置条件上，"刚记住的偏好"要等用户再说满 3 轮才生效
        assertThat(texts(merged)).containsExactly("【事实】业务条线：对公", "那它呢？");
    }

    @Test
    @DisplayName("只有摘要、没有历史 → 同样产生消息")
    void summaryBlockAloneStillProducesMessages() {
        List<Message> merged = HistoryMessageMapper.merge(
                List.of(new UserMessage("那它呢？")), List.of(),
                "【摘要】上一轮聊了存款制度", null, PROPS);

        assertThat(texts(merged)).containsExactly("【摘要】上一轮聊了存款制度", "那它呢？");
    }

    @Test
    @DisplayName("区块为 null 或空白 → 一条消息都不产生（空 SystemMessage 不是无害的）")
    void blankBlocksProduceNoMessage() {
        List<Message> current = List.of(new UserMessage("那它呢？"));
        String[] blanks = {null, "", "   ", "\n"};

        for (String blank : blanks) {
            assertThat(texts(HistoryMessageMapper.merge(current, List.of(), blank, blank, PROPS)))
                    .containsExactly("那它呢？");
        }
    }

    @Test
    @DisplayName("区块**不截断**：超长也原样进入 prompt（预算由渲染器按「整条丢弃」管）")
    void blocksAreNeverTruncated() {
        MemoryProperties tiny = new MemoryProperties(true, 3, 5, 30);
        String longBlock = "【事实】业务条线：对公，常跑网点 0100，关注大额存单";

        List<Message> merged = HistoryMessageMapper.merge(
                List.of(new UserMessage("那它呢？")), List.of(), null, longBlock, tiny);

        // 在这里再截一次会把渲染器那条纪律（整条丢弃、绝不截断单条）撤销掉 ——
        // 一条被腰斩的"业务条线：对公…"是**假事实**，比少一条危险得多
        assertThat(texts(merged)).containsExactly(longBlock, "那它呢？");
    }

    /**
     * 不带记忆区块的便捷调用 —— 本类前半段的用例验的是<b>历史</b>的拼装，
     * 与两个区块无关，让它们不必为一个 {@code null, null} 分心。
     * <p>
     * ⚠️ 区块与历史的位置关系（本阶段唯一的新语义）由后半段那些用例用<b>完整签名</b>验 ——
     * 那些用例不能走这个助手，否则"区块根本没传进去"也会通过。
     */
    private static List<Message> merge(List<Message> current, List<ChatTurn> history,
                                       MemoryProperties props) {
        return HistoryMessageMapper.merge(current, history, null, null, props);
    }

    private static ChatTurn turn(int turnNo, String question, String answer) {
        return new ChatTurn(turnNo, question, answer, List.of());
    }

    private static List<String> texts(List<Message> messages) {
        return messages.stream().map(Message::getText).toList();
    }
}
