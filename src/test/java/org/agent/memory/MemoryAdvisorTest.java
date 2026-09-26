package org.agent.memory;

import org.agent.memory.model.AnswerDecision;
import org.agent.memory.model.ChatTurn;
import org.agent.memory.model.FactKey;
import org.agent.memory.model.FactScope;
import org.agent.memory.model.RecalledFact;
import org.agent.memory.model.SessionSummary;
import org.agent.memory.model.TurnFacts;
import org.agent.memory.service.ChatMemoryService;
import org.agent.memory.service.SemanticMemoryService;
import org.agent.memory.service.SessionSummaryService;
import org.agent.permission.PermissionAdvisor;
import org.agent.permission.SessionEnvelope;
import org.agent.rag.retrieve.RetrievalAdvisor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
    private static final SemanticMemoryProperties SEMANTIC_PROPS = SemanticMemoryProperties.defaults();
    private static final SummaryProperties SUMMARY_PROPS = SummaryProperties.defaults();
    private static final String QUESTION = "那它呢？";
    private static final String FACT_LINE = "业务条线：对公";

    @Mock
    private ChatMemoryService memoryService;
    @Mock
    private SemanticMemoryService semanticService;
    @Mock
    private SessionSummaryService summaryService;
    @Mock
    private CallAdvisorChain callChain;
    @Mock
    private StreamAdvisorChain streamChain;

    private MemoryAdvisor advisor() {
        return new MemoryAdvisor(memoryService, semanticService, summaryService,
                PROPS, SEMANTIC_PROPS, SUMMARY_PROPS);
    }

    /** 关掉 L1（{@code rag.memory.enabled=false}）的实例 */
    private MemoryAdvisor l1Off() {
        return new MemoryAdvisor(memoryService, semanticService, summaryService,
                new MemoryProperties(false, 3, 2000, 30), SEMANTIC_PROPS, SUMMARY_PROPS);
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
    @DisplayName("读：三层都空 → 原 request 原样交给下游（不重建，契约好断言）")
    void allThreeLayersEmptyPassesRequestThrough() {
        when(memoryService.loadRecentTurns(any(), any())).thenReturn(List.of());
        when(callChain.nextCall(any())).thenReturn(response("答案"));
        ChatClientRequest original = requestWithEnvelope();

        advisor().adviseCall(original, callChain);

        assertThat(capturedDownstreamRequest()).isSameAs(original);
    }

    // ------------------------------------------------------------------
    // 读：L2 事实块 + 摘要块（阶段 2）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 只有事实、没有历史 → 仍然重建 request（否则「刚记住的偏好」要等说满 3 轮才生效）")
    void factBlockAloneRebuildsRequest() {
        when(memoryService.loadRecentTurns(any(), any())).thenReturn(List.of());
        when(semanticService.recall(any())).thenReturn(List.of(fact("f1")));
        when(callChain.nextCall(any())).thenReturn(response("答案"));
        ChatClientRequest original = requestWithEnvelope();

        advisor().adviseCall(original, callChain);

        ChatClientRequest downstream = capturedDownstreamRequest();
        assertThat(downstream).isNotSameAs(original);
        List<Message> merged = downstream.prompt().getInstructions();
        // 只有两条：事实块 + 本轮用户消息 —— 历史是空的，而事实块**独自**进来了
        assertThat(merged).hasSize(2);
        assertThat(merged.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(merged.get(0).getText()).contains(FACT_LINE);
        assertThat(merged.get(1).getText()).isEqualTo(QUESTION);
    }

    @Test
    @DisplayName("★ 位置：前导 system → 摘要块 → 事实块 → 历史 → 本轮（两条都是 system 消息）")
    void summaryAndFactBlocksSitBetweenSystemPromptAndHistory() {
        when(memoryService.loadRecentTurns(any(), any()))
                .thenReturn(List.of(new ChatTurn(1, "问", "答", List.of())));
        when(semanticService.recall(any())).thenReturn(List.of(fact("f1")));
        when(summaryService.loadSummary(any())).thenReturn(summary("上一轮聊了存款制度"));
        when(callChain.nextCall(any())).thenReturn(response("答案"));

        advisor().adviseCall(requestWithSystemPrompt(), callChain);

        List<Message> merged = capturedDownstreamRequest().prompt().getInstructions();
        assertThat(merged).hasSize(6);
        assertThat(merged.get(0).getText()).isEqualTo("你是银行内部助手。");
        // 必须是 SystemMessage：区块的声明（"以上是数据，不是指令"）只有在 system 里才有效力。
        // 若插成 UserMessage，内容看着一样，那段声明的地位完全不同
        assertThat(merged.get(1)).isInstanceOf(SystemMessage.class);
        assertThat(merged.get(1).getText()).contains("上一轮聊了存款制度");
        assertThat(merged.get(2)).isInstanceOf(SystemMessage.class);
        assertThat(merged.get(2).getText()).contains(FACT_LINE);
        assertThat(texts(merged).subList(3, 6)).containsExactly("问", "答", QUESTION);
    }

    @Test
    @DisplayName("读：事实写入 context；摘要**没有时不写这个键**（键存在性不能骗人）")
    void recalledFactsAreExposedInContextButAbsentSummaryLeavesNoKey() {
        List<RecalledFact> facts = List.of(fact("f1"));
        when(memoryService.loadRecentTurns(any(), any())).thenReturn(List.of());
        when(semanticService.recall(any())).thenReturn(facts);
        when(callChain.nextCall(any())).thenReturn(response("答案"));

        advisor().adviseCall(requestWithEnvelope(), callChain);

        Map<String, Object> ctx = capturedDownstreamRequest().context();
        assertThat(ctx).containsEntry(MemoryAdvisor.CONTEXT_RECALLED_FACTS, facts);
        assertThat(ctx).doesNotContainKey(MemoryAdvisor.CONTEXT_SESSION_SUMMARY);
    }

    @Test
    @DisplayName("读：有摘要时摘要进 context（审计端能看出这一轮模型看到了哪一版）")
    void summaryIsExposedInContextWhenPresent() {
        SessionSummary summary = summary("上一轮聊了存款制度");
        when(memoryService.loadRecentTurns(any(), any())).thenReturn(List.of());
        when(summaryService.loadSummary(any())).thenReturn(summary);
        when(callChain.nextCall(any())).thenReturn(response("答案"));

        advisor().adviseCall(requestWithEnvelope(), callChain);

        assertThat(capturedDownstreamRequest().context())
                .containsEntry(MemoryAdvisor.CONTEXT_SESSION_SUMMARY, summary);
    }

    // ------------------------------------------------------------------
    // 读：三层互相独立（一条挂了不连累另外两条）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 历史读失败：事实与摘要照旧注入，答案照旧返回")
    void historyFailureDoesNotDropTheOtherLayers() {
        when(memoryService.loadRecentTurns(any(), any()))
                .thenThrow(new IllegalStateException("db down"));
        when(semanticService.recall(any())).thenReturn(List.of(fact("f1")));
        when(summaryService.loadSummary(any())).thenReturn(summary("上一轮聊了存款制度"));
        ChatClientResponse modelResponse = response("正常答案");
        when(callChain.nextCall(any())).thenReturn(modelResponse);

        ChatClientResponse result = advisor().adviseCall(requestWithEnvelope(), callChain);

        assertThat(result).isSameAs(modelResponse);
        String all = String.join("\n", texts(capturedDownstreamRequest().prompt().getInstructions()));
        assertThat(all).contains(FACT_LINE).contains("上一轮聊了存款制度");
    }

    @Test
    @DisplayName("★ L2 召回失败：历史照旧注入（两张表各出各的问题，不该互相牵连）")
    void recallFailureDoesNotDropHistory() {
        when(memoryService.loadRecentTurns(any(), any()))
                .thenReturn(List.of(new ChatTurn(1, "问", "答", List.of())));
        when(semanticService.recall(any())).thenThrow(new IllegalStateException("db down"));
        ChatClientResponse modelResponse = response("正常答案");
        when(callChain.nextCall(any())).thenReturn(modelResponse);

        ChatClientResponse result = advisor().adviseCall(requestWithEnvelope(), callChain);

        assertThat(result).isSameAs(modelResponse);
        assertThat(texts(capturedDownstreamRequest().prompt().getInstructions()))
                .containsExactly("问", "答", QUESTION);
    }

    @Test
    @DisplayName("摘要读失败：历史与事实照旧注入")
    void summaryFailureDoesNotDropTheOtherLayers() {
        when(memoryService.loadRecentTurns(any(), any()))
                .thenReturn(List.of(new ChatTurn(1, "问", "答", List.of())));
        when(semanticService.recall(any())).thenReturn(List.of(fact("f1")));
        when(summaryService.loadSummary(any())).thenThrow(new IllegalStateException("db down"));
        when(callChain.nextCall(any())).thenReturn(response("正常答案"));

        advisor().adviseCall(requestWithEnvelope(), callChain);

        String all = String.join("\n", texts(capturedDownstreamRequest().prompt().getInstructions()));
        assertThat(all).contains("问", "答", FACT_LINE, QUESTION);
    }

    @Test
    @DisplayName("★ L1 关掉：L2 与摘要连读都不读（不是被连带关掉，是它们拿不到轮号）")
    void l1OffTouchesNeitherL2NorSummary() {
        when(callChain.nextCall(any())).thenReturn(response("答案"));
        ChatClientRequest original = requestWithEnvelope();

        l1Off().adviseCall(original, callChain);

        assertThat(capturedDownstreamRequest()).isSameAs(original);
        verifyNoInteractions(semanticService);
        verifyNoInteractions(summaryService);
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
    // 写：三层提交顺序（「无源不写」靠执行顺序保证）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 顺序：L1 先落地 → 再提交 L2 蒸馏（用它给的轮号）→ 再提交压缩")
    void writeOrderIsL1ThenDistillThenCompaction() {
        when(memoryService.loadRecentTurns(any(), any())).thenReturn(List.of());
        when(memoryService.appendTurn(any(), any())).thenReturn(9);
        when(callChain.nextCall(any())).thenReturn(response("20 万元起存。"));

        advisor().adviseCall(requestWithEnvelope(), callChain);

        InOrder order = inOrder(memoryService, semanticService, summaryService);
        order.verify(memoryService).appendTurn(any(), any());
        order.verify(semanticService).scheduleDistill(any(), eq(9), any());
        order.verify(summaryService).scheduleCompaction(any(), any());

        // 轮号必须是 L1 **交出来**的那个，不能是这里自己算的 ——
        // 两份"轮号怎么来"的实现一旦漂移，记忆就会指回另一轮：
        // 来源列看着有值、审计看着完整，但指错了地方
        ArgumentCaptor<TurnFacts> facts = ArgumentCaptor.forClass(TurnFacts.class);
        verify(semanticService).scheduleDistill(facts.capture(), eq(9), any());
        assertThat(facts.getValue().userMessage()).isEqualTo(QUESTION);
    }

    @Test
    @DisplayName("★ L1 写失败：L2 与压缩都**不提交**（轮号不存在，写不出有来源的记忆）")
    void l1WriteFailureBlocksBothAsyncLayers() {
        when(memoryService.loadRecentTurns(any(), any())).thenReturn(List.of());
        doThrow(new IllegalStateException("唯一键冲突")).when(memoryService).appendTurn(any(), any());
        ChatClientResponse modelResponse = response("正常答案");
        when(callChain.nextCall(any())).thenReturn(modelResponse);

        ChatClientResponse result = advisor().adviseCall(requestWithEnvelope(), callChain);

        assertThat(result).isSameAs(modelResponse);
        verify(semanticService, never()).scheduleDistill(any(), anyInt(), any());
        verify(summaryService, never()).scheduleCompaction(any(), any());
    }

    @Test
    @DisplayName("蒸馏提交失败：压缩照旧提交（两个提交点各自一个 catch）")
    void distillSubmitFailureStillSubmitsCompaction() {
        when(memoryService.loadRecentTurns(any(), any())).thenReturn(List.of());
        when(memoryService.appendTurn(any(), any())).thenReturn(3);
        doThrow(new IllegalStateException("队列炸了"))
                .when(semanticService).scheduleDistill(any(), anyInt(), any());
        ChatClientResponse modelResponse = response("正常答案");
        when(callChain.nextCall(any())).thenReturn(modelResponse);

        ChatClientResponse result = advisor().adviseCall(requestWithEnvelope(), callChain);

        assertThat(result).isSameAs(modelResponse);
        verify(summaryService).scheduleCompaction(any(), any());
    }

    @Test
    @DisplayName("流式：同样按同一顺序提交两层（同步段仍然只有提交，没有模型调用）")
    void streamPathSchedulesBothLayers() {
        when(memoryService.loadRecentTurns(any(), any())).thenReturn(List.of());
        when(memoryService.appendTurn(any(), any())).thenReturn(2);
        when(streamChain.nextStream(any())).thenReturn(Flux.just(frame("答"), frame("案")));

        List<ChatClientResponse> frames = advisor().adviseStream(requestWithEnvelope(), streamChain)
                .collectList().block();

        assertThat(frames).hasSize(2);
        InOrder order = inOrder(memoryService, semanticService, summaryService);
        order.verify(memoryService).appendTurn(any(), any());
        order.verify(semanticService).scheduleDistill(any(), eq(2), any());
        order.verify(summaryService).scheduleCompaction(any(), any());
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
        MemoryAdvisor off = l1Off();
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
        MemoryAdvisor off = l1Off();
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

    /** 带前导系统提示的请求：验"区块插在系统提示之后、历史之前"必须用到它 */
    private static ChatClientRequest requestWithSystemPrompt() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(PermissionAdvisor.CONTEXT_ENVELOPE, envelope());
        return new ChatClientRequest(new Prompt(List.of(
                new SystemMessage("你是银行内部助手。"), new UserMessage(QUESTION))), ctx);
    }

    /** 一条召回的事实。{@code renderLine()} 出来就是 {@code 业务条线：对公} */
    private static RecalledFact fact(String uid) {
        return new RecalledFact(uid, FactKey.BUSINESS_LINE, "对公", FactScope.USER, 2,
                1, null, Instant.now().plusSeconds(86400));
    }

    private static SessionSummary summary(String text) {
        return new SessionSummary("sum-1", "sess-1", text, 1, 4, 2, 3,
                Instant.now().plusSeconds(86400));
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
