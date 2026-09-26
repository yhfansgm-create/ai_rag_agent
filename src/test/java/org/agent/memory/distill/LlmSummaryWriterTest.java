package org.agent.memory.distill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LlmSummaryWriter} 的单测：<b>纯 Mockito</b>。
 * <p>
 * 这个类要证明的是 RAG-07 附录 A1 那条「已知偏差」被机械化落实了：
 * <blockquote>
 *   摘要只能用来定位，不能用来承载事实；数字按指针回原文取。
 * </blockquote>
 * 提示词里写"禁止记录数字"只是一句<b>承诺</b>。这里的用例让模型<b>不听话</b>
 * （打桩返回带数字的摘要），断言 Java 侧真的把它拦下来了 ——
 * 并且反过来断言"文号里的数字不会被误报"（误报会让这个功能因为
 * "经常没有摘要"而被关掉，那等于闸门自己把自己拆了）。
 */
class LlmSummaryWriterTest {

    private static final String Q1 = "大额存单的起存金额是怎么规定的";
    private static final String A1 = "按现行规定，需网点运营主管复核后放行。";
    /** 合规的摘要：只有主题与术语，没有金额/比例/期限 */
    private static final String GOOD = "用户询问了大额存单起存金额的相关规定。";
    /** 违规的摘要：把原文里的数字抄了进来 —— A1 说的正是这一种损失 */
    private static final String WITH_NUMBER = "用户询问了大额存单 20 万元的起存金额规定。";
    /** 合规且容易误报：文号里的数字不是金额 */
    private static final String WITH_DOC_NUMBER = "用户询问了《XX 办法》（银保监发〔2019〕23 号）的相关规定。";

    private ChatModel chatModel;
    private LlmSummaryWriter writer;

    private final List<Prompt> prompts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        writer = new LlmSummaryWriter(chatModel);
    }

    @Nested
    @DisplayName("正常压缩")
    class HappyPath {

        @Test
        @DisplayName("干净的摘要原样返回")
        void cleanSummary() {
            stubReply(GOOD);

            assertThat(writer.summarize(request())).isEqualTo(GOOD);
            assertThat(prompts).as("一次就过，不该重试").hasSize(1);
        }

        @Test
        @DisplayName("模型失败 → null（= 放弃这次压缩），不抛")
        void modelFailureReturnsNull() {
            when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("模型挂了"));

            assertThat(writer.summarize(request())).isNull();
        }

        @Test
        @DisplayName("没有要折叠的轮次 → null，且**不调模型**")
        void noSourceTurnsSkipsTheModel() {
            CompactionRequest empty = new CompactionRequest(null, List.of(), 600);

            assertThat(writer.summarize(empty)).isNull();
            assertThat(writer.summarize(null)).isNull();

            // ⚠️ 用 never().call(...) 而不是 verifyNoInteractions：构造 ChatClient 时
            // Spring AI 会读一次 chatModel.getDefaultOptions()，那也是一次"交互"
            verify(chatModel, never()).call(any(Prompt.class));
        }

        @Test
        @DisplayName("输出压成单行（多行会让模型有机会伪造区块结构）")
        void outputIsCollapsedToOneLine() {
            stubReply("用户询问了\n大额存单\n起存金额。");

            assertThat(writer.summarize(request())).isEqualTo("用户询问了 大额存单 起存金额。");
        }
    }

    @Nested
    @DisplayName("★ 数字禁令：模型不听话时，闸门还在")
    class NumberBan {

        @Test
        @DisplayName("★ 带数字 → 打回一次；第二次干净则**用第二次的**（证明是重写不是直接丢）")
        void numberIsRejectedThenRewritten() {
            stubReply(WITH_NUMBER, GOOD);

            assertThat(writer.summarize(request())).isEqualTo(GOOD);
            assertThat(prompts).as("原始 + 打回，共两次").hasSize(2);
            assertThat(textOf(prompts.get(1))).as("重写请求里要说明上一版错在哪")
                    .contains("金额");
        }

        @Test
        @DisplayName("★ 两次都带数字 → null（宁可让窗口滑走，也不让违规摘要进入未来所有轮次）")
        void numberInBothAttemptsDiscards() {
            stubReply(WITH_NUMBER, WITH_NUMBER);

            assertThat(writer.summarize(request())).isNull();
            assertThat(prompts).as("问两次就停，不做第三次").hasSize(2);
        }

        @Test
        @DisplayName("★ 打回说明里**不含**被拦下的那个数字（复核链路不该成为新的泄露面）")
        void feedbackDoesNotLeakTheNumber() {
            stubReply(WITH_NUMBER, GOOD);
            writer.summarize(request());

            String secondUserMessage = userTextOf(prompts.get(1));

            // 把那个数字告诉模型"让它避开"看起来更有效，但那等于把敏感数值又抄了一遍
            // 到请求体里 —— 而这个请求会经第三方 API。与 FactRedlines「绝不返回命中原值」、
            // 审计里 GUARD_REJECT 只放 {factKey, pattern, valueLength} 是同一个立场。
            //
            // ⚠️ 断言的是**用户消息**而不是整份 prompt：系统提示词里举着
            // 「1.35%」「20 万元」这样的反例（那是通用示例，不是用户数据），
            // 对整份 prompt 断言"不含 20"会被它误伤
            assertThat(secondUserMessage).doesNotContain("20").doesNotContain("万元");
            assertThat(secondUserMessage).as("只给类别，说明确实说了错在哪").contains("金额");
        }

        @Test
        @DisplayName("★ 文号里的数字**不能**误报（银保监发〔2019〕23 号是合规内容）")
        void documentNumberIsNotMisreported() {
            // 误报的后果不是"更安全"，而是"摘要经常没有" ⇒ 这个功能被当成没做好而关掉。
            // 复用 NumericExtractor 而不是另写正则，一半的理由就在这里：
            // 它的单位白名单已经排除了文号、条款号、计数、日期
            stubReply(WITH_DOC_NUMBER);

            assertThat(writer.summarize(request())).isEqualTo(WITH_DOC_NUMBER);
            assertThat(prompts).as("一次通过，没有重试").hasSize(1);
        }

        @Test
        @DisplayName("百分比与期限同样被拦（三类数字一个都不放过）")
        void percentAndDurationAreAlsoRejected() {
            stubReply("用户询问了年利率 1.35% 的规定。", "用户询问了存期 3 个月的规定。");

            // 两次分别是比例与期限，都不合格 ⇒ null。三类判据来自 NumericExtractor，
            // 所以这里不需要为每一类各写一套逻辑，只要证明"三类都在拦"
            assertThat(writer.summarize(request())).isNull();
            assertThat(prompts).hasSize(2);
        }
    }

    @Nested
    @DisplayName("超长：打回重写而不是截断")
    class LengthLimit {

        @Test
        @DisplayName("★ 超过 maxChars → 打回重写；第二次合格则采用")
        void overlongIsReasked() {
            // 上限取 GOOD 的长度：第二次刚好卡在边界上（= 不超），验证判据是 > 而不是 >=
            CompactionRequest request = new CompactionRequest(null,
                    List.of(turn(1, Q1, A1, true)), GOOD.length());
            stubReply(GOOD + "另外还补充了很长的一段说明文字。", GOOD);

            assertThat(writer.summarize(request)).isEqualTo(GOOD);
            assertThat(textOf(prompts.get(1))).contains("太长");
        }

        @Test
        @DisplayName("maxChars 为 0 → 不做长度判断（0 是「不限」而不是「不许有内容」）")
        void zeroMaxCharsMeansUnlimited() {
            CompactionRequest request = new CompactionRequest(null, List.of(turn(1, Q1, A1, true)), 0);
            stubReply(GOOD);

            assertThat(writer.summarize(request)).isEqualTo(GOOD);
            assertThat(prompts).hasSize(1);
        }
    }

    @Nested
    @DisplayName("提示词形状")
    class PromptShape {

        @Test
        @DisplayName("★ 拒答轮渲染成「未获答复」，且**不带**拒答文案")
        void refusedTurnIsMarkedNotQuoted() {
            String refusalText = "未在您的授权范围内检索到相关资料。";
            CompactionRequest request = new CompactionRequest(null,
                    List.of(turn(2, Q1, refusalText, false)), 600);
            stubReply(GOOD);

            writer.summarize(request);
            String prompt = textOf(prompts.get(0));

            assertThat(prompt).contains("（本轮未获答复）");
            // 两个理由：拒答文案没有定位信息量，而且反复喂回去会强化拒答倾向
            assertThat(prompt).doesNotContain(refusalText);
        }

        @Test
        @DisplayName("上一版摘要进了提示词（增量压缩，不重读已摘要过的轮次）")
        void previousSummaryIsIncluded() {
            CompactionRequest request = new CompactionRequest("之前聊过柜面取现。",
                    List.of(turn(3, Q1, A1, true)), 600);
            stubReply(GOOD);

            writer.summarize(request);

            assertThat(textOf(prompts.get(0))).contains("之前聊过柜面取现。");
        }

        @Test
        @DisplayName("轮次按轮号升序渲染，且带轮号（定位信息要有位置感）")
        void turnsAreRenderedInOrder() {
            CompactionRequest request = new CompactionRequest(null,
                    List.of(turn(1, Q1, A1, true), turn(2, "那它呢", A1, true)), 600);
            stubReply(GOOD);

            writer.summarize(request);
            String prompt = textOf(prompts.get(0));

            assertThat(prompt).contains("第1轮 用户：" + Q1).contains("第2轮 用户：那它呢");
            assertThat(prompt.indexOf("第1轮")).isLessThan(prompt.indexOf("第2轮"));
        }
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    /** 按顺序回放 replies；用完后重复最后一条 */
    private void stubReply(String... replies) {
        AtomicInteger index = new AtomicInteger();
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            prompts.add(invocation.getArgument(0));
            String text = replies[Math.min(index.getAndIncrement(), replies.length - 1)];
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        });
    }

    private static CompactionRequest request() {
        return new CompactionRequest(null, List.of(turn(1, Q1, A1, true)), 600);
    }

    private static CompactionRequest.SourceTurn turn(int turnNo, String question,
                                                     String answer, boolean answered) {
        return new CompactionRequest.SourceTurn(turnNo, question, answered ? answer : null, answered);
    }

    private static String textOf(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        for (Message message : prompt.getInstructions()) {
            if (message.getText() != null) {
                sb.append(message.getText()).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 只取最后一条消息（用户消息）的文本。
     * <p>
     * 需要它是因为<b>系统提示词里举着反例数字</b>（「1.35%」「20 万元」）——
     * 断言"打回说明没泄露具体值"时，对整份 prompt 断言会被那些通用示例误伤。
     */
    private static String userTextOf(Prompt prompt) {
        List<Message> instructions = prompt.getInstructions();
        return instructions.get(instructions.size() - 1).getText();
    }
}
