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

        List<Message> merged = HistoryMessageMapper.merge(current, history, PROPS);

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

        List<Message> merged = HistoryMessageMapper.merge(current, history, PROPS);

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

        List<Message> merged = HistoryMessageMapper.merge(
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

        List<Message> merged = HistoryMessageMapper.merge(
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

        List<Message> merged = HistoryMessageMapper.merge(current, List.of(), PROPS);

        assertThat(texts(merged)).containsExactly("系统提示", "问题");
    }

    @Test
    @DisplayName("无系统提示时历史仍然拼在最前")
    void worksWithoutSystemMessage() {
        List<Message> merged = HistoryMessageMapper.merge(
                List.of(new UserMessage("那它呢？")),
                List.of(turn(1, "问", "答")),
                PROPS);

        assertThat(texts(merged)).containsExactly("问", "答", "那它呢？");
    }

    @Test
    @DisplayName("历史里缺答案的非正常轮次：跳过缺的那条，不塞空消息")
    void skipsNullPartsOfTurn() {
        List<Message> merged = HistoryMessageMapper.merge(
                List.of(new UserMessage("那它呢？")),
                List.of(new ChatTurn(1, "只有问题没有答案", null, List.of())),
                PROPS);

        assertThat(texts(merged)).containsExactly("只有问题没有答案", "那它呢？");
    }

    private static ChatTurn turn(int turnNo, String question, String answer) {
        return new ChatTurn(turnNo, question, answer, List.of());
    }

    private static List<String> texts(List<Message> messages) {
        return messages.stream().map(Message::getText).toList();
    }
}
