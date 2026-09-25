package org.agent.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 输出侧数字校验 Advisor 测试：放行 / 拒答 / 无依据时跳过 / 流式缓冲。
 * <p>
 * 注意一个容易误解的点：本 Advisor **一定会调用模型** —— 要先拿到答案才能核对数字。
 * 它拦的是"答案到达用户"，不是"模型被调用"。所以断言的是**返回值被换成了拒答**，
 * 而不是 {@code verify(chain, never())}（那是 {@link RetrievalAdvisor} 检索前拒答才有的性质）。
 */
@ExtendWith(MockitoExtension.class)
class NumericGuardrailAdvisorTest {

    private static final String SOURCE =
            "单笔取现 5 万元（含）以上须经网点主管授权，授权后 3 个工作日内完成复核。";

    @Mock
    private CallAdvisorChain callChain;

    @Mock
    private StreamAdvisorChain streamChain;

    private final NumericGuardrailAdvisor advisor = new NumericGuardrailAdvisor();

    private static Document doc(String text) {
        return new Document(text);
    }

    private static ChatClientRequest requestWithDocs(List<Document> docs) {
        Map<String, Object> ctx = new HashMap<>();
        if (docs != null) {
            ctx.put(RetrievalAdvisor.CONTEXT_RETRIEVED_DOCS, docs);
        }
        return new ChatClientRequest(new Prompt("大额取现要授权吗"), ctx);
    }

    private static ChatClientResponse responseWith(String text) {
        return new ChatClientResponse(
                new ChatResponse(List.of(new Generation(new AssistantMessage(text)))),
                new HashMap<>());
    }

    private static String textOf(ChatClientResponse response) {
        return response.chatResponse().getResult().getOutput().getText();
    }

    // ---------- 同步路径 ----------

    @Test
    @DisplayName("答案里的数字都能在原文找到 → 原样放行")
    void consistentAnswerPassesThrough() {
        ChatClientResponse modelResponse = responseWith("单笔取现 5 万元（含）以上须经网点主管授权[1]。");
        when(callChain.nextCall(any())).thenReturn(modelResponse);

        ChatClientResponse result = advisor.adviseCall(
                requestWithDocs(List.of(doc(SOURCE))), callChain);

        assertThat(result).isSameAs(modelResponse);
    }

    @Test
    @DisplayName("数字无出处 → 返回值被换成拒答，并写下违规明细与拒答原因")
    void fabricatedNumberIsRefused() {
        when(callChain.nextCall(any()))
                .thenReturn(responseWith("单笔取现 5 万元（含）以上须经网点主管授权，最高 8 万元。"));
        ChatClientRequest request = requestWithDocs(List.of(doc(SOURCE)));

        ChatClientResponse result = advisor.adviseCall(request, callChain);

        assertThat(textOf(result)).contains("无法在知识库原文中核对");
        assertThat(request.context())
                .containsEntry(RetrievalAdvisor.CONTEXT_REFUSAL_REASON,
                        NumericGuardrailAdvisor.REFUSAL_UNVERIFIED_NUMBER);
        assertThat(violationsOf(request)).containsExactly("8 万元 → 80000元");
    }

    @SuppressWarnings("unchecked")
    private static List<String> violationsOf(ChatClientRequest request) {
        return (List<String>) request.context().get(NumericGuardrailAdvisor.CONTEXT_NUMERIC_VIOLATIONS);
    }

    @Test
    @DisplayName("用户可见文案只给条数，不回显被编造的具体数字")
    void refusalTextDoesNotEchoTheNumber() {
        when(callChain.nextCall(any())).thenReturn(responseWith("限额 8 万元。"));

        ChatClientResponse result = advisor.adviseCall(requestWithDocs(List.of(doc(SOURCE))), callChain);

        assertThat(textOf(result)).contains("1 处").doesNotContain("8 万元");
    }

    @Test
    @DisplayName("取不到检索结果 → 跳过校验（fail-open，与权限门禁的 fail-closed 相反）")
    void skipsWhenNoRetrievalContext() {
        ChatClientResponse modelResponse = responseWith("限额 999 万元。");
        when(callChain.nextCall(any())).thenReturn(modelResponse);

        // 没有任何 agent 往 context 里放过 CONTEXT_RETRIEVED_DOCS
        ChatClientResponse result = advisor.adviseCall(requestWithDocs(null), callChain);

        assertThat(result).isSameAs(modelResponse);
    }

    @Test
    @DisplayName("检索结果为空列表同样跳过 —— 没有可比对的原文")
    void skipsWhenDocsEmpty() {
        ChatClientResponse modelResponse = responseWith("限额 999 万元。");
        when(callChain.nextCall(any())).thenReturn(modelResponse);

        assertThat(advisor.adviseCall(requestWithDocs(List.of()), callChain)).isSameAs(modelResponse);
    }

    @Test
    @DisplayName("开关关掉后退回改造前行为：不校验、不写 context")
    void disabledAdvisorIsPassthrough() {
        NumericGuardrailAdvisor off = new NumericGuardrailAdvisor(false);
        ChatClientResponse modelResponse = responseWith("限额 8 万元。");
        when(callChain.nextCall(any())).thenReturn(modelResponse);
        ChatClientRequest request = requestWithDocs(List.of(doc(SOURCE)));

        assertThat(off.adviseCall(request, callChain)).isSameAs(modelResponse);
        assertThat(request.context())
                .doesNotContainKey(NumericGuardrailAdvisor.CONTEXT_NUMERIC_VIOLATIONS);
    }

    @Test
    @DisplayName("答案里没有可核对数值 → 放行（不该因为「没数字」而拦截）")
    void answerWithoutNumbersPasses() {
        ChatClientResponse modelResponse = responseWith("请携带本人有效身份证件前往网点办理。");
        when(callChain.nextCall(any())).thenReturn(modelResponse);

        assertThat(advisor.adviseCall(requestWithDocs(List.of(doc(SOURCE))), callChain))
                .isSameAs(modelResponse);
    }

    // ---------- 流式路径 ----------

    @Test
    @DisplayName("流式一致 → 缓冲后逐帧原样回放")
    void streamConsistentReplaysAllFrames() {
        List<ChatClientResponse> frames = List.of(
                responseWith("单笔取现 5 万元（含）"),
                responseWith("以上须经网点主管授权[1]。"));
        when(streamChain.nextStream(any())).thenReturn(Flux.fromIterable(frames));

        List<ChatClientResponse> result = advisor
                .adviseStream(requestWithDocs(List.of(doc(SOURCE))), streamChain)
                .collectList().block();

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isSameAs(frames.get(0));
    }

    @Test
    @DisplayName("流式违规 → 丢弃全部已缓冲的帧，只发一帧拒答（数字不会被吐给用户）")
    void streamViolationEmitsSingleRefusal() {
        // 违规数字在**后面**的帧里 —— 若不缓冲，这半句早就到用户屏幕上了
        when(streamChain.nextStream(any())).thenReturn(Flux.just(
                responseWith("单笔取现 8 万元（含）"),
                responseWith("以上须经网点主管授权。")));

        List<ChatClientResponse> result = advisor
                .adviseStream(requestWithDocs(List.of(doc(SOURCE))), streamChain)
                .collectList().block();

        assertThat(result).singleElement()
                .satisfies(r -> assertThat(textOf(r)).contains("无法在知识库原文中核对"));
    }

    @Test
    @DisplayName("流式取不到检索结果 → 直通，不做缓冲（不牺牲打字机效果）")
    void streamSkipsWhenNoRetrievalContext() {
        when(streamChain.nextStream(any()))
                .thenReturn(Flux.just(responseWith("限额 999 万元。")));

        List<ChatClientResponse> result = advisor
                .adviseStream(requestWithDocs(null), streamChain)
                .collectList().block();

        assertThat(result).singleElement()
                .satisfies(r -> assertThat(textOf(r)).isEqualTo("限额 999 万元。"));
    }

    // ---------- 装配契约 ----------

    @Test
    @DisplayName("order 必须是正数（RetrievalAdvisor 为 0），否则读不到它写进 context 的原文")
    void orderRunsAfterRetrieval() {
        // Advisor 执行顺序是"值小者在外层"，越靠内越晚拿到请求。
        // 本 Advisor 要在检索之后才能读到 CONTEXT_RETRIEVED_DOCS，所以必须比 0 大。
        assertThat(advisor.getOrder()).isEqualTo(NumericGuardrailAdvisor.ORDER).isPositive();
        assertThat(advisor.getName()).isEqualTo("NumericGuardrailAdvisor");
    }
}
