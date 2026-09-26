package org.agent.memory;

import lombok.extern.slf4j.Slf4j;
import org.agent.memory.model.ChatTurn;
import org.agent.memory.model.RecalledFact;
import org.agent.memory.model.SessionSummary;
import org.agent.memory.model.TurnFacts;
import org.agent.memory.service.ChatMemoryService;
import org.agent.memory.service.SemanticMemoryService;
import org.agent.memory.service.SessionSummaryService;
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

import java.util.ArrayList;
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
 * <h2>③ 三层注入：历史 + 摘要块 + 事实块</h2>
 * 阶段 2 起，本 Advisor 注入 <b>三段</b>东西，各自独立开关、独立失败、独立审计：
 * <ol>
 *   <li><b>L1 历史</b>（{@code rag.memory.enabled}）—— 本会话最近 N 轮，见 ①</li>
 *   <li><b>会话摘要块</b>（{@code rag.memory.summary.enabled}）—— 滑出窗口的那一段，
 *       只用于定位、不承载事实（RAG-07 附录 A1 的校正）</li>
 *   <li><b>L2 事实块</b>（{@code rag.memory.semantic.enabled}）—— 跨会话的稳定事实，
 *       第二条注入面，见 {@code MemoryBlockRenderer} 那段声明</li>
 * </ol>
 * 三者的顺序由 {@code HistoryMessageMapper} 定死，本类只负责把内容取出来交给它。
 * <p>
 * ⚠️ <b>一个具体的回归点</b>：本类的读路径原先写的是"历史为空 → 原 request 原样交下游"。
 * 三层化之后那句话<b>不再成立</b>：只有事实、没有历史时也<b>必须</b>重建 request，
 * 否则"刚记住的偏好"要等到用户再说满 3 轮才生效 —— 表现为"它明明记过了却想不起来"。
 * 判据因此收窄成"<b>三者都空</b>才不重建"，并且这个判据只有一个地方写
 * （见 {@link #injectMemory} 的早返回）。
 *
 * <h2>④ 失败隔离：记忆是体验层，不是权限层</h2>
 * 本类仍是记忆读写失败的<b>唯一</b> catch 边界（{@code ChatMemoryService} /
 * {@code SemanticMemoryService} / {@code SessionSummaryService} 与 DAO 全部保持 fail-fast）。
 * 读失败 → 本轮当作没有那一层；写失败 → 只告警。两种情况都<b>不让用户的问答失败</b>。
 * <p>
 * 与阶段 1 的差别是<b>边界从一个变成了一组</b>：读侧三条通路各自一个 try/catch
 * （历史挂了不该连事实一起丢），写侧外面再包一层 + 两个提交点各一个。
 * 数量变多了，但<b>纪律没变</b>：DAO 与服务层照旧不吞异常，
 * 所以"抛出来了"这件事在单测里仍然可断言。
 * <p>
 * 不上 {@code @Async}（本类自身）：省下的只是一次本地 MySQL 往返（毫秒级，相对 LLM 的秒级可忽略），
 * 代价却是 {@code @EnableAsync} + 同类自调用失效的坑 + 异步线程无请求上下文 + 测试要 await。
 * <p>
 * ✅ <b>那句承诺已经兑现</b>：阶段 1 在这里写过"等写入变成跨表多条（阶段 2 的 L2 蒸馏）时
 * 再上有界队列 + 丢弃策略" —— 蒸馏确实是一次秒级 LLM 往返，所以它走
 * {@code memoryDistillExecutor}（有界队列 + 拒绝时打 WARN，见 {@code MemoryDistillConfig}），
 * <b>但同步段仍然只有"提交任务"这一步</b>，本类的调用栈上没有模型调用。
 * 这是刻意的：{@code MemoryAdvisor} 在用户等待的那条链上，任何一次 LLM 往返加在这里
 * 都是直接加在响应时间上的。
 *
 * <h2>⑤ 写侧顺序：无源不写靠<b>执行顺序</b>保证</h2>
 * <pre>
 *   appendTurn（L1 落库，同步，毫秒级）      ← 拿到真实 turnNo
 *     → scheduleDistill（L2 蒸馏，异步）      ← 每条记忆都要指回上面那个 turnNo
 *     → scheduleCompaction（摘要压缩，异步）  ← 它读的正是 L1 刚写下的行
 * </pre>
 * 两个"先"都是<b>硬约束不是偏好</b>：L1 写失败时轮号不存在，L2 就<b>不该</b>被提交
 * （提交了也只能写出无源的记忆）；而压缩读的是 {@code chat_message}，L1 没落地时
 * 它读不到本轮。所以这两步都在 {@code appendTurn} 的<b>后面</b>、
 * 且在同一个 try 里 —— 顺序本身就是防线，不需要额外的注释去提醒谁。
 * <p>
 * <b>推论（要说清楚）</b>：{@code rag.memory.enabled=false} 时 L2 与摘要<b>一定不会写</b> ——
 * 不是被开关连带关掉，而是它们拿不到轮号。顺着这个推论，
 * "只想关掉 L2 但留着 L1"是支持的（关 {@code rag.memory.semantic.enabled}），
 * 反过来"关掉 L1 只留 L2"则<b>结构上不成立</b>，不要把它当成一个可配的组合。
 */
@Slf4j
@Component
public class MemoryAdvisor implements CallAdvisor, StreamAdvisor {

    /**
     * 用 Spring AI 自己的常量而不是魔数：它正是为"对话记忆类 advisor"准备的默认位置，
     * 落在权限 advisor 之后、检索 advisor 之前。
     */
    public static final int ORDER = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;

    /** 本轮实际注入的历史轮次，供审计/测试复用 */
    public static final String CONTEXT_HISTORY_TURNS = "org.agent.memory.historyTurns";

    /**
     * 本轮召回的事实（L2）。
     * <p>
     * ⚠️ 存的是<b>召回结果</b>，不是"真的进了 prompt 的那几条" —— 区块超预算时渲染器会从尾部
     * 丢掉一些，而这里仍然是完整的召回列表。这个偏差是<b>刻意</b>的，方向是"多算"：
     * 它只影响阶段 4 巡检③的 LRU 排序（把没显示出来的也算成命中），
     * <b>不参与任何权限判定</b>，也不会让用户看到不该看的。要精确对齐需要渲染器回传保留集，
     * 那份复杂度换不来收益。
     */
    public static final String CONTEXT_RECALLED_FACTS = "org.agent.memory.recalledFacts";

    /**
     * 本轮注入的会话摘要（L2 之外的一层）。
     * <p>
     * ⚠️ <b>没有摘要时不 put 这个键，而不是 put 一个 null</b>：
     * {@code Map} 里"键存在但值为 null"与"键不存在"用 {@code get()} 区分不出来，
     * 于是这个键的<b>存在性</b>本身就成了一个会骗人的信号（审计端会以为"查过、有摘要"）。
     * 宁可让消费方多一次 null 判断，也不要一个语义含混的键。
     */
    public static final String CONTEXT_SESSION_SUMMARY = "org.agent.memory.sessionSummary";

    private final ChatMemoryService memoryService;
    private final SemanticMemoryService semanticService;
    private final SessionSummaryService summaryService;
    private final MemoryProperties props;
    private final SemanticMemoryProperties semanticProps;
    private final SummaryProperties summaryProps;

    @Autowired
    public MemoryAdvisor(ChatMemoryService memoryService,
                         SemanticMemoryService semanticService,
                         SessionSummaryService summaryService,
                         SemanticMemoryProperties semanticProps,
                         SummaryProperties summaryProps,
                         @Value("${rag.memory.enabled:true}") boolean enabled,
                         @Value("${rag.memory.window-turns:3}") int windowTurns,
                         @Value("${rag.memory.max-chars-per-message:2000}") int maxCharsPerMessage,
                         @Value("${rag.memory.retention-days:30}") int retentionDays,
                         @Value("${rag.memory.clearance-filter-enabled:true}") boolean clearanceFilterEnabled) {
        // 这里刻意走 5 参规范构造、不用 4 参便捷构造：那个便捷构造是给测试与其它模块用的，
        // 生产路径必须把开关真读进来，否则 rag.memory.clearance-filter-enabled 形同虚设。
        // ⚠️ 两个 L2 的配置对象走 @Bean 注入（MemoryDistillConfig）而不是在这里现造 ——
        //    它们各有 4 个消费方，现造四遍就会出现"四处默认值不一致"
        this(memoryService, semanticService, summaryService,
                new MemoryProperties(enabled, windowTurns, maxCharsPerMessage,
                        retentionDays, clearanceFilterEnabled),
                semanticProps, summaryProps);
    }

    /** 测试便捷构造，照 {@code NumericGuardrailAdvisor} 的两段式构造 */
    public MemoryAdvisor(ChatMemoryService memoryService,
                         SemanticMemoryService semanticService,
                         SessionSummaryService summaryService,
                         MemoryProperties props,
                         SemanticMemoryProperties semanticProps,
                         SummaryProperties summaryProps) {
        this.memoryService = memoryService;
        this.semanticService = semanticService;
        this.summaryService = summaryService;
        this.props = props;
        this.semanticProps = semanticProps;
        this.summaryProps = summaryProps;
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
        ChatClientRequest withMemory = injectMemory(request, envelope);
        ChatClientResponse response = chain.nextCall(withMemory);
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
        ChatClientRequest withMemory = injectMemory(request, envelope);
        return Mono.just(withMemory)
                // 写回是阻塞 JDBC，放到 boundedElastic 上，别占住 reactor 的 token 线程
                .publishOn(BaseAdvisor.DEFAULT_SCHEDULER)
                .flatMapMany(chain::nextStream)
                .transform(flux -> new ChatClientMessageAggregator()
                        .aggregateChatClientResponse(flux,
                                aggregated -> appendTurnSafely(request, envelope, aggregated)));
    }

    /**
     * 读三样东西并注入 prompt：L1 历史、会话摘要块、L2 事实块。
     * <p>
     * 三条通路<b>各自独立</b> try/catch：一条挂了不该连累另外两条。
     * 这不是洁癖 —— 摘要与事实分别读两张表，一张表出问题（比如列刚加、SQL 写错）
     * 时另外两层还能正常工作，是"记忆是体验层"这条原则在<b>层内</b>的继续。
     * <p>
     * ⚠️ 早返回的判据是"<b>三者都空</b>"，不是"历史为空" —— 见类注释 ③ 的回归点。
     */
    private ChatClientRequest injectMemory(ChatClientRequest request, SessionEnvelope envelope) {
        List<ChatTurn> history = loadHistorySafely(envelope);
        List<RecalledFact> facts = recallFactsSafely(envelope);
        SessionSummary summary = loadSummarySafely(envelope);

        // 渲染器已经把"没有内容"统一成 null 了（见其类注释），这里直接用
        String summaryBlock = MemoryBlockRenderer.renderSummary(summary, summaryProps.maxSummaryChars());
        String factBlock = MemoryBlockRenderer.renderFacts(facts, semanticProps.maxFactsPerBlock(),
                semanticProps.maxLineChars(), semanticProps.maxBlockChars());

        if (history.isEmpty() && summaryBlock == null && factBlock == null) {
            // 三层都空 → 不重建 request：让"关掉即原样"这条契约好断言，也省一次无谓的拷贝。
            // ⚠️ 只有事实、没有历史时**一定**会走到下面的重建分支 —— 这是本类最容易写回的 bug
            return request;
        }

        // ⚠️ 必须在 mutate() 之前 put。ChatClientRequest.mutate() 内部是
        //    new HashMap<>(this.context) —— 它**复制**了一份 context，
        //    mutate 之后再 put 的键，下游（以及响应里的 context）都看不到。
        request.context().put(CONTEXT_HISTORY_TURNS, history);
        request.context().put(CONTEXT_RECALLED_FACTS, facts);
        if (summary != null) {
            // 没有摘要时**不 put**（而不是 put null）—— 理由见该常量的注释
            request.context().put(CONTEXT_SESSION_SUMMARY, summary);
        }

        // Prompt.getInstructions() 返回的是**完整**消息列表（不只是 system 消息），
        // 名字有误导性 —— HistoryMessageMapper 按"前导 system + 其余"切开处理。
        List<Message> merged = HistoryMessageMapper.merge(
                request.prompt().getInstructions(), history, summaryBlock, factBlock, props);
        warnIfOverBudget(merged, envelope);
        return request.mutate()
                .prompt(request.prompt().mutate().messages(merged).build())
                .build();
    }

    /** L1 历史。读失败 → 当作没有历史（绝不能把用户的问答顶掉） */
    private List<ChatTurn> loadHistorySafely(SessionEnvelope envelope) {
        try {
            return memoryService.loadRecentTurns(envelope, props);
        } catch (Exception e) {
            log.warn("MemoryAdvisor: 读取历史失败，本轮按无历史处理。sessionId={}", envelope.sessionId(), e);
            return List.of();
        }
    }

    /** L2 事实。读失败 → 当作没有事实 */
    private List<RecalledFact> recallFactsSafely(SessionEnvelope envelope) {
        try {
            return semanticService.recall(envelope);
        } catch (Exception e) {
            log.warn("MemoryAdvisor: 召回 L2 事实失败，本轮按无事实处理。sessionId={}", envelope.sessionId(), e);
            return List.of();
        }
    }

    /** 会话摘要。读失败 → 当作没有摘要 */
    private SessionSummary loadSummarySafely(SessionEnvelope envelope) {
        try {
            return summaryService.loadSummary(envelope);
        } catch (Exception e) {
            log.warn("MemoryAdvisor: 读取会话摘要失败，本轮按无摘要处理。sessionId={}", envelope.sessionId(), e);
            return null;
        }
    }

    /**
     * §5.5 次触发的<b>可观测化</b>：组装后的上下文如果超过 {@code budget-chars × trigger-ratio}，
     * 打一条带数字的 WARN。
     *
     * <h2>⚠️ 它不触发任何动作 —— 这是刻意的，必须写清楚</h2>
     * §5.5 的 token 规则是为<b>恢复</b>场景写的（会话暂停后重新拉起，一次性把长历史塞回去），
     * 而本项目的窗口是<b>连续滑动</b>的：每轮组装的体积由
     * {@code window-turns × max-chars-per-message} 从结构上封顶，超预算不会累积、不会恶化。
     * 所以真正生效的触发是"<b>有轮次滑出窗口且未被摘要覆盖</b>"（见
     * {@code SessionSummaryService.runCompaction}），那是一条会自愈的路径。
     * <p>
     * 把这条规则接成"超了就再压一次"会得到一个<b>每轮都可能触发的 LLM 调用</b>，
     * 而且压完体积并不会变小（压的是已经滑出去的内容，不是当前窗口）——
     * 那是纯粹的浪费，不是保护。所以这里只做观测，并在第 9 步的文档里记下这个偏离。
     * <p>
     * <b>这条 WARN 频繁出现说明什么</b>：{@code budget-chars} / {@code trigger-ratio}
     * 与 {@code window-turns} / {@code max-chars-per-message} 不匹配（前一组是按恢复场景
     * 默认的，比连续滑动场景小）。它是个<b>配置提示</b>，不是故障信号。
     * <p>
     * 估算用字符数不用 token 数、以及本类为什么在这里 fail-open，见 {@code ContextBudget} 的类注释。
     */
    private void warnIfOverBudget(List<Message> merged, SessionEnvelope envelope) {
        // 只数正文，不加"消息条数 × 固定开销"的经验值 —— 那种魔法系数一引入，预算就再也解释不清
        List<String> texts = new ArrayList<>(merged.size());
        for (Message message : merged) {
            texts.add(message.getText());
        }
        int estimated = ContextBudget.estimateChars(texts);
        if (ContextBudget.shouldCompact(estimated, summaryProps.budgetChars(), summaryProps.triggerRatio())) {
            log.warn("MemoryAdvisor: 组装后的上下文估算 {} 字符，已越过预算 {} × {} 的触发线"
                            + "（§5.5 次触发只做观测、不触发再压缩，见方法注释）。"
                            + "sessionId={}, messages={}",
                    estimated, summaryProps.budgetChars(), summaryProps.triggerRatio(),
                    envelope.sessionId(), merged.size());
        }
    }

    /**
     * 写回一轮，并按「L1 先落地」的顺序提交后面两层的异步任务。失败只告警 ——
     * 记忆落库失败等于少一条回看记录，不是用户的问题。
     * <p>
     * 三层各自的失败隔离见类注释 ④：外层这个 catch 覆盖"抽轮 + L1 落库"，
     * 两个异步提交点各有自己的 catch —— 蒸馏提交失败不该连带把压缩也丢掉。
     * <p>
     * ⚠️ 两个 schedule 都<b>只做提交</b>：真正的 LLM 往返在
     * {@code memoryDistillExecutor} 的线程上，用户不等它（那个池的拒绝策略
     * 也刻意不是 {@code CallerRunsPolicy}，就是为了不把过载转嫁到本轮响应上）。
     */
    private void appendTurnSafely(ChatClientRequest original, SessionEnvelope envelope,
                                  ChatClientResponse response) {
        TurnFacts facts;
        int turnNo;
        try {
            facts = TurnExtractor.extract(original, envelope, response);
            // ⚠️ 顺序是硬约束：L2 的每条记忆都要指回这个 turnNo（无源不写）。
            //    这里抛异常 ⇒ 下面两步都不会执行，这正是我们要的 —— 轮号不存在时
            //    "不写"比"写一条指不回去的记忆"正确
            turnNo = memoryService.appendTurn(facts, props);
        } catch (Exception e) {
            log.warn("MemoryAdvisor: 写回历史失败（不影响本次回答）。sessionId={}", envelope.sessionId(), e);
            return;
        }
        scheduleDistillSafely(facts, turnNo, envelope);
        scheduleCompactionSafely(envelope);
    }

    /** 提交 L2 蒸馏（异步）。提交失败只告警 */
    private void scheduleDistillSafely(TurnFacts facts, int turnNo, SessionEnvelope envelope) {
        try {
            semanticService.scheduleDistill(facts, turnNo, envelope);
        } catch (Exception e) {
            log.warn("MemoryAdvisor: 提交 L2 蒸馏失败（本轮记忆不会写入，不影响回答）。sessionId={}",
                    envelope.sessionId(), e);
        }
    }

    /** 提交会话压缩（异步）。提交失败只告警 */
    private void scheduleCompactionSafely(SessionEnvelope envelope) {
        try {
            summaryService.scheduleCompaction(envelope, props);
        } catch (Exception e) {
            log.warn("MemoryAdvisor: 提交会话压缩失败（本轮摘要不会更新，不影响回答）。sessionId={}",
                    envelope.sessionId(), e);
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
