package org.agent.memory.distill;

import org.agent.memory.model.FactDraft;
import org.agent.memory.model.FactKey;
import org.agent.memory.model.RecalledFact;
import org.agent.memory.model.FactScope;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LlmFactDistiller} 的单测：<b>纯 Mockito，不起 Spring、不连网</b>。
 * <p>
 * 两条主线：
 * <ol>
 *   <li><b>模型答什么都能兜住</b> —— 散文、空对象、null 元素、抛异常，一律空列表且不外抛。
 *       抽取器在异步线程上跑，一个外抛的异常只会被线程池吞掉（{@code FactDistiller} 契约）</li>
 *   <li>★ <b>提示词里只有用户原话 + 词表</b> —— 这条既是"没走 advisor 链"的证据，
 *       也是"答案没有进入抽取输入"的证据。前者坏了表现为「L2 永远不写」，
 *       后者坏了表现为「模型把幻觉答案记成了用户偏好」，两者都很难从数据上看出来</li>
 * </ol>
 */
class LlmFactDistillerTest {

    private static final String UTTERANCE = "我主要做对公业务，帮我看看对公开户流程";
    /** 打桩答案。它只应存在于"回答"里，绝不该出现在抽取的提示词中 */
    private static final String ANSWER = "按现行规定，需网点运营主管复核后放行。";

    private ChatModel chatModel;
    private LlmFactDistiller distiller;

    /** 依次收到的每一份 Prompt */
    private final List<Prompt> prompts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        distiller = new LlmFactDistiller(chatModel);
    }

    @Nested
    @DisplayName("正常抽取")
    class HappyPath {

        @Test
        @DisplayName("模型给出合法 JSON → 一条 FactDraft")
        void normalJson() {
            stubModel("""
                    {"facts":[{"key":"business_line","value":"对公","confidence":0.9}]}
                    """);

            List<FactDraft> drafts = distiller.distill(request(UTTERANCE));

            assertThat(drafts).hasSize(1);
            assertThat(drafts.get(0).rawKey()).isEqualTo("business_line");
            assertThat(drafts.get(0).rawValue()).isEqualTo("对公");
            assertThat(drafts.get(0).confidence()).isEqualByComparingTo("0.9");
        }

        @Test
        @DisplayName("没有任何候选（{\"facts\":[]}）→ 空列表")
        void emptyFacts() {
            stubModel("{\"facts\":[]}");

            assertThat(distiller.distill(request(UTTERANCE))).isEmpty();
        }

        @Test
        @DisplayName("★ 中文标签原样传下去，抽取器**不**做键解析（那是 FactKey.resolve 的活）")
        void chineseLabelIsPassedThrough() {
            // 若这里"顺手"解析成枚举，策略层就再也看不到模型实际答的是什么 ——
            // 而"模型总答中文标签"这件事正是判断词表要不要加别名时唯一的线索
            stubModel("""
                    {"facts":[{"key":"业务条线","value":"对公"}]}
                    """);

            List<FactDraft> drafts = distiller.distill(request(UTTERANCE));

            assertThat(drafts).extracting(FactDraft::rawKey).containsExactly("业务条线");
        }

        @Test
        @DisplayName("★ 置信度是 NaN 字面量 → 整份结果作废，但**不抛异常**")
        void nanConfidenceDiscardsTheWholeResult() {
            // ⚠️ 实测行为与预想不同，值得记下来：Spring AI 的 BeanOutputConverter 用的
            // ObjectMapper **拒绝** NaN 字面量，所以整份结果在解析阶段就被判失败了 ——
            // 也就是说 LlmFactDistiller.toConfidence 里那道 NaN 兜底在当前配置下**到不了**。
            // 这条测试锁的是实际行为：一个"不参与任何判定"的字段变成 NaN，
            // 代价是整份结果作废，而最要紧的是**不能把这个异常抛出去**（抽取在异步线程上，
            // 外抛只会被线程池吞掉，症状是"什么都没发生"）
            stubModel("""
                    {"facts":[{"key":"business_line","value":"对公","confidence":NaN}]}
                    """);

            assertThat(distiller.distill(request(UTTERANCE))).isEmpty();
        }

        @Test
        @DisplayName("置信度缺失 → null，不影响候选")
        void missingConfidence() {
            stubModel("""
                    {"facts":[{"key":"business_line","value":"对公"}]}
                    """);

            List<FactDraft> drafts = distiller.distill(request(UTTERANCE));

            assertThat(drafts).hasSize(1);
            assertThat(drafts.get(0).confidence()).isNull();
        }
    }

    @Nested
    @DisplayName("★ 模型出错时一律空列表，绝不外抛")
    class FailureIsolation {

        @Test
        @DisplayName("★ 模型答了一堆散文 → 空列表，且不抛")
        void proseYieldsEmptyList() {
            stubModel("用户说他主要做对公业务，我建议记录 business_line=对公。");

            assertThatCode(() -> {
                List<FactDraft> drafts = distiller.distill(request(UTTERANCE));
                assertThat(drafts).isEmpty();
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("模型调用抛异常 → 空列表，且不抛")
        void modelFailureYieldsEmptyList() {
            when(chatModel.call(any(Prompt.class)))
                    .thenThrow(new IllegalStateException("模型挂了"));

            assertThat(distiller.distill(request(UTTERANCE))).isEmpty();
        }

        @Test
        @DisplayName("模型返回空对象 {}（facts 为 null）→ 空列表")
        void nullFactsYieldsEmptyList() {
            stubModel("{}");

            assertThat(distiller.distill(request(UTTERANCE))).isEmpty();
        }

        @Test
        @DisplayName("★ {\"facts\":[null]} → 一条 key/value 全空的 draft（**不**静默跳过）")
        void nullCandidateBecomesBlankDraft() {
            // 静默跳过的话，"模型回了一个 null"这件事在审计里没有任何痕迹 ——
            // 而它是一条真实的异常输出。造一条空 draft，它会拿到 UNKNOWN_KEY 这条具名理由
            stubModel("{\"facts\":[null]}");

            List<FactDraft> drafts = distiller.distill(request(UTTERANCE));

            assertThat(drafts).hasSize(1);
            assertThat(drafts.get(0).rawKey()).isNull();
            assertThat(drafts.get(0).rawValue()).isNull();
        }

        @Test
        @DisplayName("★ 空 key / 空 value **原样**交给下游，不在这里过滤")
        void blankValuesAreNotFilteredHere() {
            // 在这里滤掉的话，那些候选永远不会出现在审计里 ——
            // 而"模型提了但被拒"正是审计要回答的问题
            stubModel("""
                    {"facts":[{"key":"","value":""},{"key":"business_line","value":""}]}
                    """);

            assertThat(distiller.distill(request(UTTERANCE))).hasSize(2);
        }

        @Test
        @DisplayName("本轮没有用户原话 → 空列表，且**不调模型**")
        void blankUtteranceSkipsTheModel() {
            assertThat(distiller.distill(request(null))).isEmpty();
            assertThat(distiller.distill(request("   "))).isEmpty();
            assertThat(distiller.distill(null)).isEmpty();

            // ⚠️ 用 never().call(...) 而不是 verifyNoInteractions：构造 ChatClient 时
            // Spring AI 会读一次 chatModel.getDefaultOptions()，那也是一次"交互"——
            // 断言整个 mock 没被碰过会因为一个无关的 getter 而失败。
            // 这里真正要断言的是"没有发起模型调用"
            verify(chatModel, never()).call(any(Prompt.class));
        }
    }

    @Nested
    @DisplayName("★ 提示词：只有用户原话 + 词表")
    class PromptShape {

        @Test
        @DisplayName("★ 只有用户原话与词表 —— 不含答案、不含检索增强前缀")
        void onlyUtteranceAndVocabulary() {
            stubModel("{\"facts\":[]}");
            distiller.distill(request(UTTERANCE));

            String prompt = textOf(prompts.get(0));

            assertThat(prompt).as("用户原话必须原样在里面").contains(UTTERANCE);
            assertThat(prompt).as("词表由 FactKey 渲染").contains("business_line").contains("preferred_org");
            // ★ 这两条才是重点：答案与检索原文**不能**出现在抽取输入里。
            // 答案可能是模型编的，把它放进输入 = 给了模型一条"把幻觉抄成记忆"的路，
            // 而 L2 的记忆会被注入未来所有轮次的 prompt
            assertThat(prompt).as("答案不许进抽取输入").doesNotContain(ANSWER);
            assertThat(prompt)
                    .as("检索层的增强前缀不在里面 ⇒ 蒸馏没走 advisor 链")
                    .doesNotContain(LlmFactDistiller.RETRIEVAL_AUGMENT_PREFIX);
        }

        @Test
        @DisplayName("★ 禁止类目被渲染成显式禁令（否定式词表比缺席的词表更能约束模型）")
        void forbiddenKeysAreRendered() {
            String system = LlmFactDistiller.buildSystemPrompt();

            assertThat(system).contains("amount_threshold");
            assertThat(system).as("CONFIRM 档也在提示词里（本阶段同样拒写）").contains("watched_client");
        }

        @Test
        @DisplayName("已知事实进提示词（避免重复提出），用可读的单行形式")
        void knownFactsAreRendered() {
            stubModel("{\"facts\":[]}");
            distiller.distill(request(UTTERANCE, List.of(knownFact())));

            String prompt = textOf(prompts.get(0));

            assertThat(prompt).contains("已知事实").contains("- 业务条线：对公");
        }

        @Test
        @DisplayName("没有已知事实时也有一行说明，不留空段")
        void noKnownFacts() {
            stubModel("{\"facts\":[]}");
            distiller.distill(request(UTTERANCE));

            assertThat(textOf(prompts.get(0))).contains("已知事实：（无）");
        }

        @Test
        @DisplayName("词表渲染器与枚举不漂移：加一个枚举值就会自动出现在提示词里")
        void vocabularyCannotDrift() {
            String system = LlmFactDistiller.buildSystemPrompt();

            for (FactKey key : FactKey.values()) {
                assertThat(system).as("词表里每个键都该被渲染：%s", key.code()).contains(key.code());
            }
        }
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    /** 按顺序回放 replies；用完后重复最后一条（便于断言"只问了两次"之类的场景） */
    private void stubModel(String... replies) {
        AtomicInteger index = new AtomicInteger();
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            prompts.add(invocation.getArgument(0));
            String text = replies[Math.min(index.getAndIncrement(), replies.length - 1)];
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        });
    }

    private static DistillRequest request(String utterance) {
        return request(utterance, List.of());
    }

    private static DistillRequest request(String utterance, List<RecalledFact> known) {
        return DistillRequest.of(utterance, LocalDate.of(2026, 9, 26), known);
    }

    private static RecalledFact knownFact() {
        return new RecalledFact("uid-1", FactKey.BUSINESS_LINE, "对公", FactScope.USER, 4,
                1, null, null);
    }

    /** 把一份 Prompt 里所有消息的文本拼起来 */
    private static String textOf(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        for (Message message : prompt.getInstructions()) {
            if (message.getText() != null) {
                sb.append(message.getText()).append('\n');
            }
        }
        return sb.toString();
    }
}
