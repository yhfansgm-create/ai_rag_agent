package org.agent.memory;

import lombok.extern.slf4j.Slf4j;
import org.agent.memory.model.ChatTurn;
import org.agent.memory.service.ChatMemoryService;
import org.agent.permission.PermissionAdvisor;
import org.agent.permission.SessionEnvelope;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 记忆 Advisor（L1 情景记忆）：读最近 N 轮历史注入 prompt，轮末把"结论 + 指针"落库。
 *
 * <h2>① 为什么不能用 {@code MessageChatMemoryAdvisor}</h2>
 * 它存的是<b>消息本身</b>：{@code before()} 把历史原样拼到当前 prompt 前面，
 * {@code after()} 把助手输出存回去。挂到本管线上，就形成了权限模型的一条<b>旁路</b>：
 * <ul>
 *   <li><b>绕过权限门禁</b> —— {@code accessFilter} 只在<b>检索那一刻</b>生效，
 *       历史回放根本不经过 {@code RetrievalAdvisor} 的权限分支</li>
 *   <li><b>降级后仍可读</b> —— 用户上轮取到密级 4 的结论，本轮 {@code authVersion} 变了，
 *       检索侧会拦，但<b>历史里上一轮的答案照样进 prompt</b>。等于把 {@code SessionAuthService}
 *       的 TOCTOU 防护整个绕过去</li>
 *   <li><b>密级混洗</b> —— 同会话先问高密级再问低密级，低密级答案由历史里的高密级结论拼出</li>
 *   <li><b>元数据推理</b> —— 即便正文脱敏，引文标签仍在（"存在 doc-004 这份密级 4 的制度"）</li>
 * </ul>
 * 一句话：<b>检索层的权限是"每次现查"的，记忆层若是"整段回放"的，记忆就是权限模型上的一条旁路。</b>
 * 本 Advisor 的对策是"存指针不存内容" + 读取时按实况信封过滤，不是给 {@code ChatMemory} 套个壳。
 *
 * <h2>② 为什么 order 必须早于 {@code RetrievalAdvisor}</h2>
 * 两条理由叠加，缺一不可：
 * <ol>
 *   <li><b>功能理由</b>：检索前要能拿到历史，将来才做得了指代消解（RAG-04 #8 的剩余项）</li>
 *   <li><b>安全理由</b>：一旦挪到 {@code RetrievalAdvisor}(order=0) <b>之后</b>，本 Advisor
 *       看到的用户消息已经是 {@code augment()} 增强过的 —— 那段<b>带检索原文的整段资料
 *       会被写进历史</b>。这个改动在 code review 里看起来人畜无害（"为了让检索用上历史"），
 *       所以在这里写死</li>
 * </ol>
 * 取 {@code Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER}（= {@code HIGHEST_PRECEDENCE + 1000}），
 * 正好落在 {@code PermissionAdvisor}({@code Integer.MIN_VALUE}) 与 {@code RetrievalAdvisor}(0)
 * 之间，不必自己编数字。参见 RAG-07 §六 的顺序表。
 *
 * <h2>③ 失败隔离：记忆是体验层，不是权限层</h2>
 * 本类是记忆读写失败的<b>唯一</b> catch 边界（{@code ChatMemoryService} 与 DAO 保持 fail-fast）。
 * 读失败 → 本轮当作没有历史；写失败 → 只告警。两种情况都<b>不让用户的问答失败</b>。
 * <p>
 * 不上 {@code @Async}：省下的只是一次本地 MySQL 往返（毫秒级，相对 LLM 的秒级可忽略），
 * 代价却是 {@code @EnableAsync} + 同类自调用失效的坑 + 异步线程无请求上下文 + 测试要 await。
 * 等写入变成跨表多条（阶段 2 的 L2 蒸馏）时再上有界队列 + 丢弃策略。
 */
@Slf4j
@Component
public class MemoryAdvisor implements CallAdvisor, StreamAdvisor {

    /**
     * 用 Spring AI 自己的常量而不是魔数：它正是为"对话记忆类 advisor"准备的默认位置，
     * 落在权限 advisor 之后、检索 advisor 之前。
     */
    public static final int ORDER = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;

    /** 本轮实际注入的历史轮次，供审计/测试/将来 L2 复用 */
    public static final String CONTEXT_HISTORY_TURNS = "org.agent.memory.historyTurns";

    private final ChatMemoryService memoryService;
    private final MemoryProperties props;

    @Autowired
    public MemoryAdvisor(ChatMemoryService memoryService,
                         @Value("${rag.memory.enabled:true}") boolean enabled,
                         @Value("${rag.memory.window-turns:3}") int windowTurns,
                         @Value("${rag.memory.max-chars-per-message:2000}") int maxCharsPerMessage,
                         @Value("${rag.memory.retention-days:30}") int retentionDays) {
        this(memoryService, new MemoryProperties(enabled, windowTurns, maxCharsPerMessage, retentionDays));
    }

    /** 测试便捷构造，照 {@code NumericGuardrailAdvisor} 的两段式构造 */
    public MemoryAdvisor(ChatMemoryService memoryService, MemoryProperties props) {
        this.memoryService = memoryService;
        this.props = props;
    }

    @Override
    public String getName() {
        return "MemoryAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        SessionEnvelope envelope = envelopeOf(request);
        if (!props.enabled() || envelope == null) {
            return chain.nextCall(request);
        }
        ChatClientRequest withHistory = injectHistory(request, envelope);
        ChatClientResponse response = chain.nextCall(withHistory);
        // 传**原始** request —— 它上面的用户消息未经过 augment() 增强（见 TurnExtractor 类注释）
        appendTurnSafely(request, envelope, response);
        return response;
    }

    /**
     * 流式路径：聚合出完整答案后再写回，但<b>不缓冲、不重排、不减少帧</b>，所以打字机效果不受影响。
     * <p>
     * 与 {@code NumericGuardrailAdvisor} 的"先缓冲全帧再校验"是<b>相反</b>的取舍：
     * 那边必须缓冲才能拦得住，这边只是记账，没有理由牺牲体验。
     * <p>
     * 不用 {@code BaseAdvisor} 的默认 {@code adviseStream}：它的 {@code after()} 只在
     * "带 finishReason 的那一帧"上触发，拿到的是最后一帧而不是完整答案 ——
     * 这也正是 {@code MessageChatMemoryAdvisor} 必须自己重写 {@code adviseStream} 的原因。
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        SessionEnvelope envelope = envelopeOf(request);
        if (!props.enabled() || envelope == null) {
            return chain.nextStream(request);
        }
        ChatClientRequest withHistory = injectHistory(request, envelope);
        return Mono.just(withHistory)
                // 写回是阻塞 JDBC，放到 boundedElastic 上，别占住 reactor 的 token 线程
                .publishOn(BaseAdvisor.DEFAULT_SCHEDULER)
                .flatMapMany(chain::nextStream)
                .transform(flux -> new ChatClientMessageAggregator()
                        .aggregateChatClientResponse(flux,
                                aggregated -> appendTurnSafely(request, envelope, aggregated)));
    }

    /**
     * 读历史并注入 prompt。读失败 → 当作没有历史（绝不能把用户的问答顶掉）。
     */
    private ChatClientRequest injectHistory(ChatClientRequest request, SessionEnvelope envelope) {
        List<ChatTurn> history;
        try {
            history = memoryService.loadRecentTurns(envelope, props);
        } catch (Exception e) {
            log.warn("MemoryAdvisor: 读取历史失败，本轮按无记忆处理。sessionId={}", envelope.sessionId(), e);
            return request;
        }
        if (history.isEmpty()) {
            // 不重建 request：让"关掉即原样"这条契约好断言，也省一次无谓的拷贝
            return request;
        }

        // ⚠️ 必须在 mutate() 之前 put。ChatClientRequest.mutate() 内部是
        //    new HashMap<>(this.context) —— 它**复制**了一份 context，
        //    mutate 之后再 put 的键，下游（以及响应里的 context）都看不到。
        request.context().put(CONTEXT_HISTORY_TURNS, history);

        // Prompt.getInstructions() 返回的是**完整**消息列表（不只是 system 消息），
        // 名字有误导性 —— HistoryMessageMapper 按"前导 system + 其余"切开处理。
        List<Message> merged = HistoryMessageMapper.merge(
                request.prompt().getInstructions(), history, props);
        return request.mutate()
                .prompt(request.prompt().mutate().messages(merged).build())
                .build();
    }

    /**
     * 写回一轮。失败只告警 —— 记忆落库失败等于少一条回看记录，不是用户的问题。
     */
    private void appendTurnSafely(ChatClientRequest original, SessionEnvelope envelope,
                                  ChatClientResponse response) {
        try {
            memoryService.appendTurn(TurnExtractor.extract(original, envelope, response), props);
        } catch (Exception e) {
            log.warn("MemoryAdvisor: 写回历史失败（不影响本次回答）。sessionId={}", envelope.sessionId(), e);
        }
    }

    /**
     * 取本轮的实况权限信封。
     * <p>
     * 取不到就直接直通（不读不写）—— 记忆不是权限层，拿不到实况时宁可不开口，
     * 也不能凭猜测去读库。{@code PermissionAdvisor} 是 fail-closed 且排在最先，
     * 正常不可能走到这里，所以用 {@code debug} 而不是 {@code warn}，避免刷屏。
     */
    private static SessionEnvelope envelopeOf(ChatClientRequest request) {
        Object raw = request.context().get(PermissionAdvisor.CONTEXT_ENVELOPE);
        if (raw instanceof SessionEnvelope envelope) {
            return envelope;
        }
        log.debug("MemoryAdvisor: 本轮 context 里没有权限信封，记忆功能直通");
        return null;
    }
}
