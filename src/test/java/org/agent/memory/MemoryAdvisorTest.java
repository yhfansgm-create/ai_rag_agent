package org.agent.memory;

import org.agent.memory.model.AnswerDecision;
import org.agent.memory.model.ChatTurn;
import org.agent.memory.model.TurnFacts;
import org.agent.memory.service.ChatMemoryService;
import org.agent.permission.PermissionAdvisor;
import org.agent.permission.SessionEnvelope;
import org.agent.rag.retrieve.RetrievalAdvisor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Mockito 测试：不连库、不调模型。
 * <p>
 * 本类里几条"看起来多余"的断言其实是最有价值的：order 契约（挪动位置会破坏安全）、
 * 失败隔离（记忆坏了问答不能坏）、流式帧数不减（打字机效果不能牺牲）。
 * 这三条在功能层手工点不出来，只有测试能证明。
 * <p>
 * 关于"落库的 user_message 不含检索原文"这条安全断言，在 {@code TurnExtractorTest} 里 ——
 * 那是约束真正落地的地方，这里只覆盖 advisor 的编排行为。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemoryAdvisor：读历史 / 写回 / 失败隔离 / 流式")
class MemoryAdvisorTest {

    private static final MemoryProperties PROPS = new MemoryProperties(true, 3, 2000, 30);
    private static final String QUESTION = "那它呢？";

    @Mock
    private ChatMemoryService memoryService;
    @Mock
    private CallAdvisorChain callChain;
    @Mock
    private StreamAdvisorChain streamChain;

    private MemoryAdvisor advisor() {
        return new MemoryAdvisor(memoryService, PROPS);
    }

    // ------------------------------------------------------------------
    // order 契约
    // ------------------------------------------------------------------

    @Test
    @DisplayName("order 契约：落在 PermissionAdvisor 之后、RetrievalAdvisor 之前")
    void orderSitsBetweenPermissionAndRetrieval() {
        MemoryAdvisor advisor = advisor();

        assertThat(advisor.getOrder()).isEqualTo(Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER);
        assertThat(advisor.getOrder()).isGreaterThan(Advisor.HIGHEST_PRECEDENCE);
        assertThat(advisor.getOrder()).isLessThan(0);   // RetrievalAdvisor 的 order
        assertThat(advisor.getName()).isEqualTo("MemoryAdvisor");
    }

    // ------------------------------------------------------------------
    // 读：注入历史
    // ------------------------------------------------------------------

    @Test
    @DisplayName("读：历史拼在当前用户消息之前，且呈 user/assistant 交替")
    void historyIsInjectedBeforeCurrentMessage() {
        when(memoryService.loadRecentTurns(any(), any())).thenReturn(List.of(
                new ChatTurn(1, "起存金额是多少？", "20 万元起存。", List.of()),
                new ChatTurn(2, "期限呢？", "有 1/2/3 年三档。", List.of())));
        when(callChain.nextCall(any())).thenReturn(response("答案"));

        advisor().adviseCall(requestWithEnvelope(), callChain);

        ChatClientRequest downstream = capturedDownstreamRequest();
        assertThat(texts(downstream.prompt().getInstructions())).containsExactly(
                "起存金额是多少？", "20 万元起存。",
                "期限呢？", "有 1/2/3 年三档。",
                QUESTION);
    }

    @Test
    @DisplayName("读：注入的历史同时写进 context，供审计/后续阶段复用")
    void historyIsExposedInContext() {
        List<ChatTurn> history = List.of(new ChatTurn(1, "问", "答", List.of()));
        when(memoryService.loadRecentTurns(any(), any())).thenReturn(history);
        when(callChain.nextCall(any())).thenReturn(response("答案"));

        advisor().adviseCall(requestWithEnvelope(), callChain);

        // 这一条同时锁住了"必须在 mutate() 之前 put"这个坑：
        // mutate() 会复制 context，若把 put 挪到 mutate 之后，下游就读不到这个键了
        assertThat(capturedDownstreamRequest().context())
                .containsEntry(MemoryAdvisor.CONTEXT_HISTORY_TURNS, history);
    }

    @Test
    @DisplayName("读：历史为空 → 原 request 原样交给下游（不重建，契约好断言）")
    void emptyHistoryPassesRequestThrough() {
        when(memoryService.loadRecentTurns(any(), any())).thenReturn(List.of());
        when(callChain.nextCall(any())).thenReturn(response("答案"));
        ChatClientRequest original = requestWithEnvelope();

        advisor().adviseCall(original, callChain);

        assertThat(capturedDownstreamRequest()).isSameAs(original);
    }

    // ------------------------------------------------------------------
    // 写：落库
    // ------------------------------------------------------------------

    @Test
    @DisplayName("写：一轮结束后写回，入参取自本轮请求与响应")
    void writesTurnFactsAfterAnswer() {
        when(memoryService.loadRecentTurns(any(), any())).thenReturn(List.of());
        when(callChain.nextCall(any())).thenReturn(response("20 万元起存。"));

        advisor().adviseCall(requestWithEnvelope(), callChain);

        TurnFacts facts = capturedFacts();
        assertThat(facts.userMessage()).isEqualTo(QUESTION);
        assertThat(facts.answerText()).isEqualTo("20 万元起存。");
        assertThat(facts.sessionId()).isEqualTo("sess-1");
        assertThat(facts.userId()).isEqualTo("10086");
    }

    @Test
    @DisplayName("写：放行轮 → PROCEED，拒答轮 → REFUSED（理由来自 context）")
    void decisionFollowsRefusalReason() {
        when(memoryService.loadRecentTurns(any(), any())).thenReturn(List.of());

        when(callChain.nextCall(any())).thenReturn(response("正常答案"));
        advisor().adviseCall(requestWithEnvelope(), callChain);
        assertThat(capturedFacts().decision()).isEqualTo(AnswerDecision.PROCEED);
        assertThat(capturedFacts().refusalReason()).isNull();

        when(callChain.nextCall(any())).thenReturn(refusalResponse("NO_HIT"));
        advisor().adviseCall(requestWithEnvelope(), callChain);
        assertThat(capturedFacts().decision()).isEqualTo(AnswerDecision.REFUSED);
        assertThat(capturedFacts().refusalReason()).isEqualTo("NO_HIT");
    }

    // ------------------------------------------------------------------
    // 失败隔离：记忆坏了，问答不能坏
    // ------------------------------------------------------------------

    @Test
    @DisplayName("失败隔离：读历史抛异常 → 仍然调用下游并正常返回答案")
    void readFailureDoesNotBreakAnswer() {
        when(memoryService.loadRecentTurns(any(), any()))
                .thenThrow(new IllegalStateException("db down"));
        ChatClientResponse modelResponse = response("正常答案");
        when(callChain.nextCall(any())).thenReturn(modelResponse);
        ChatClientRequest original = requestWithEnvelope();

        ChatClientResponse result = advisor().adviseCall(original, callChain);

        assertThat(result).isSameAs(modelResponse);
        // 读不到历史，就把原 request 原样交给下游
        assertThat(capturedDownstreamRequest()).isSameAs(original);
    }

    @Test
    @DisplayName("失败隔离：写回抛异常 → 响应原样返回、异常不上抛")
    void writeFailureDoesNotBreakAnswer() {
        when(memoryService.loadRecentTurns(any(), any())).thenReturn(List.of());
        ChatClientResponse modelResponse = response("正常答案");
        when(callChain.nextCall(any())).thenReturn(modelResponse);
        doThrow(new IllegalStateException("db down")).when(memoryService).appendTurn(any(), any());

        ChatClientResponse result = advisor().adviseCall(requestWithEnvelope(), callChain);

        assertThat(result).isSameAs(modelResponse);
    }

    // ------------------------------------------------------------------
    // 退化路径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("开关关掉：不读不写，request 原样传给下游（逐字节等价于无记忆）")
    void disabledPassesEverythingThrough() {
        MemoryAdvisor off = new MemoryAdvisor(memoryService, new MemoryProperties(false, 3, 2000, 30));
        when(callChain.nextCall(any())).thenReturn(response("答案"));
        ChatClientRequest original = requestWithEnvelope();

        off.adviseCall(original, callChain);

        verifyNoInteractions(memoryService);
        assertThat(capturedDownstreamRequest()).isSameAs(original);
    }

    @Test
    @DisplayName("取不到权限信封：不读不写、直通（记忆不是权限层，拿不到实况就不开口）")
    void missingEnvelopePassesThrough() {
        when(callChain.nextCall(any())).thenReturn(response("答案"));
        ChatClientRequest noEnvelope =
                new ChatClientRequest(new Prompt(new UserMessage(QUESTION)), new HashMap<>());

        advisor().adviseCall(noEnvelope, callChain);

        verifyNoInteractions(memoryService);
        assertThat(capturedDownstreamRequest()).isSameAs(noEnvelope);
    }

    // ------------------------------------------------------------------
    // 流式
    // ------------------------------------------------------------------

    @Test
    @DisplayName("流式：3 帧原样透传（打字机效果不牺牲），只写回 1 次且是聚合后的完整答案")
    void streamPassesFramesThroughButWritesOnce() {
        when(memoryService.loadRecentTurns(any(), any())).thenReturn(List.of());
        when(streamChain.nextStream(any()))
                .thenReturn(Flux.just(frame("起存"), frame("金额 20"), frame(" 万元")));

        List<ChatClientResponse> frames = advisor().adviseStream(requestWithEnvelope(), streamChain)
                .collectList().block();

        assertThat(frames).hasSize(3);
        assertThat(frames.stream().map(MemoryAdvisorTest::textOf).toList())
                .containsExactly("起存", "金额 20", " 万元");

        TurnFacts facts = capturedFacts();
        assertThat(facts.answerText()).isEqualTo("起存金额 20 万元");
        verify(memoryService, times(1)).appendTurn(any(), any());
    }

    @Test
    @DisplayName("流式：写回失败不影响已下发的帧")
    void streamWriteFailureIsSwallowed() {
        when(memoryService.loadRecentTurns(any(), any())).thenReturn(List.of());
        doThrow(new IllegalStateException("db down")).when(memoryService).appendTurn(any(), any());
        when(streamChain.nextStream(any())).thenReturn(Flux.just(frame("a"), frame("b")));

        List<ChatClientResponse> frames = advisor().adviseStream(requestWithEnvelope(), streamChain)
                .collectList().block();

        assertThat(frames).hasSize(2);
    }

    @Test
    @DisplayName("流式：开关关掉 → 不读不写，直接透传下游的流")
    void disabledStreamPassesThrough() {
        MemoryAdvisor off = new MemoryAdvisor(memoryService, new MemoryProperties(false, 3, 2000, 30));
        when(streamChain.nextStream(any())).thenReturn(Flux.just(frame("a")));

        List<ChatClientResponse> frames = off.adviseStream(requestWithEnvelope(), streamChain)
                .collectList().block();

        assertThat(frames).hasSize(1);
        verify(memoryService, never()).loadRecentTurns(any(), any());
        verify(memoryService, never()).appendTurn(any(), any());
    }

    // ------------------------------------------------------------------
    // 测试数据构造
    // ------------------------------------------------------------------

    /** 取最后一次传给下游链的 request */
    private ChatClientRequest capturedDownstreamRequest() {
        ArgumentCaptor<ChatClientRequest> captor = ArgumentCaptor.forClass(ChatClientRequest.class);
        verify(callChain, org.mockito.Mockito.atLeastOnce()).nextCall(captor.capture());
        return captor.getValue();
    }

    private TurnFacts capturedFacts() {
        ArgumentCaptor<TurnFacts> captor = ArgumentCaptor.forClass(TurnFacts.class);
        verify(memoryService, org.mockito.Mockito.atLeastOnce()).appendTurn(captor.capture(), any());
        return captor.getValue();
    }

    private static ChatClientRequest requestWithEnvelope() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(PermissionAdvisor.CONTEXT_ENVELOPE, envelope());
        return new ChatClientRequest(new Prompt(new UserMessage(QUESTION)), ctx);
    }

    private static ChatClientResponse response(String text) {
        return new ChatClientResponse(
                new ChatResponse(List.of(new Generation(new AssistantMessage(text)))),
                new HashMap<>());
    }

    /** 拒答响应：理由写在 context 上，TurnExtractor 从这里读 */
    private static ChatClientResponse refusalResponse(String reason) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(RetrievalAdvisor.CONTEXT_REFUSAL_REASON, reason);
        return new ChatClientResponse(
                new ChatResponse(List.of(new Generation(new AssistantMessage("拒答文案")))),
                ctx);
    }

    private static ChatClientResponse frame(String delta) {
        return new ChatClientResponse(
                new ChatResponse(List.of(new Generation(new AssistantMessage(delta)))),
                new HashMap<>());
    }

    private static List<String> texts(List<Message> messages) {
        return messages.stream().map(Message::getText).toList();
    }

    private static String textOf(ChatClientResponse response) {
        return response.chatResponse().getResult().getOutput().getText();
    }

    private static SessionEnvelope envelope() {
        return new SessionEnvelope("sess-1", "10086", "TELLER", "P3", "0101", "0100/0101",
                2, List.of("kb-teller"), 7L, "MOCK",
                LocalDateTime.now().plusHours(1), "kb_id in ['kb-teller']");
    }
}
