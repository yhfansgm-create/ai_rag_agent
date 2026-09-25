package org.agent.rag.retrieve;

import org.agent.rag.TemporalGate;
import org.agent.support.Texts;
import lombok.extern.slf4j.Slf4j;
import org.agent.permission.PermissionAdvisor;
import org.agent.permission.SessionEnvelope;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检索 Advisor（第 3 步）：读取 PermissionAdvisor 写入 context 的 accessFilter 与权限信封，
 * 下推给向量库做 metadata 过滤（数据锁在检索层执行），检索后再做一次旁路校验（双保险），
 * 只把授权范围内的文档拼进 prompt，让 LLM 仅基于授权资料作答。
 * <p>
 * 排序在 PermissionAdvisor 之后（getOrder 更大），保证先鉴权后检索。
 */
@Slf4j
@Component
public class RetrievalAdvisor implements CallAdvisor, StreamAdvisor {

    public static final String CONTEXT_RETRIEVED_DOCS = "org.agent.rag.retrievedDocs";
    /** 拒答原因（NO_HIT / PERMISSION_FILTERED / TEMPORAL_FILTERED / KB_UNAVAILABLE），供审计表落库 */
    public static final String CONTEXT_REFUSAL_REASON = "org.agent.rag.refusalReason";
    /** 越界引用编号列表；非空表示答案引用了本次未提供的资料，需人工复核 */
    public static final String CONTEXT_CITATION_VIOLATIONS = "org.agent.rag.citationViolations";
    /** 业务日期 advisor param（ISO-8601，可选）：按"业务发生日"判断文档时效，缺省为今天 */
    public static final String PARAM_BIZ_DATE = "bizDate";

    public static final String REFUSAL_NO_HIT = "NO_HIT";
    public static final String REFUSAL_PERMISSION_FILTERED = "PERMISSION_FILTERED";
    public static final String REFUSAL_STALE = "TEMPORAL_FILTERED";
    public static final String REFUSAL_KB_UNAVAILABLE = "KB_UNAVAILABLE";

    /** 检索无命中 / 全部未过权限门禁：对用户是同一句话，避免暴露"存在但你无权看" */
    static final String TEXT_NO_HIT = "未在您的授权范围内检索到可回答该问题的资料，本次不作答。"
            + "请补充问题描述，或联系知识库管理员确认是否需要授权。";
    static final String TEXT_STALE = "检索到的相关文件已废止或尚未生效，为避免引用失效条款，本次不作答。"
            + "请核实最新制度版本后再问。";
    static final String TEXT_KB_UNAVAILABLE = "知识库当前不可用，本次不作答。";

    /** 引用编号：[1] / [12] */
    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,3})]");

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final int topK;
    private final int candidatesPerQuery;
    private final CodeRedactor codeRedactor;
    private final TemporalGate temporalGate;
    private final QueryExpander queryExpander;
    private final RrfFusion rrfFusion;

    @Autowired
    public RetrievalAdvisor(ObjectProvider<VectorStore> vectorStoreProvider,
                            @Value("${rag.retrieval.top-k:5}") int topK,
                            @Value("${rag.retrieval.candidates-per-query:0}") int candidatesPerQuery,
                            CodeRedactor codeRedactor,
                            TemporalGate temporalGate,
                            QueryExpander queryExpander,
                            RrfFusion rrfFusion) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.topK = topK;
        // 每路召回条数。必须比 topK 宽：候选集后面要过权限门禁和时效门禁，被剔掉的名额得有人补上，
        // 否则"命中 30 篇但 5 篇无权"会退化成"只凑出 1 篇"。0 = 按 topK 的 3 倍自动取值
        this.candidatesPerQuery = candidatesPerQuery > 0 ? candidatesPerQuery : Math.max(topK * 3, topK);
        this.codeRedactor = codeRedactor;
        this.temporalGate = temporalGate;
        this.queryExpander = queryExpander;
        this.rrfFusion = rrfFusion;
    }

    /** 测试便捷构造：无 Spring 容器时使用默认组件（空术语表 → 查询扩展退化为单路，与原行为等价） */
    public RetrievalAdvisor(ObjectProvider<VectorStore> vectorStoreProvider, int topK) {
        this(vectorStoreProvider, topK, 0, new CodeRedactor(), new TemporalGate(),
                new QueryExpander(), new RrfFusion(RrfFusion.DEFAULT_K));
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Decision d = decide(request);
        if (d.refused()) {
            return refusalResponse(request, d);
        }
        ChatClientResponse response = chain.nextCall(d.request());
        auditCitations(d.request(), response);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Decision d = decide(request);
        if (d.refused()) {
            // 流式下拒答退化成"单帧整段返回"：内容正确，但没有逐字打字效果。
            // 要做到真流式拒答得构造 ChatResponse 分片，属后续优化，不影响正确性。
            return Flux.just(refusalResponse(request, d));
        }
        return chain.nextStream(d.request());
    }

    /** 决策结果：要么放行到模型，要么在本地拒答（拒答路径完全不调用模型） */
    private record Decision(ChatClientRequest request, String refusalReason, String refusalText) {
        static Decision proceed(ChatClientRequest r) {
            return new Decision(r, null, null);
        }

        static Decision refuse(String reason, String text) {
            return new Decision(null, reason, text);
        }

        boolean refused() {
            return refusalText != null;
        }
    }

    /**
     * 检索 + 两层门禁，产出"放行 / 拒答"的决策。
     * <p>
     * 任一环节把候选集清空 → 拒答，<b>绝不让模型改用自身知识兜底</b>（那是无授权、不可溯源的内容）。
     * 顺序：权限门禁 {@link #prepareForUser} → 时效门禁 {@link TemporalGate}。
     */
    private Decision decide(ChatClientRequest request) {
        String accessFilter = (String) request.context().get(PermissionAdvisor.CONTEXT_ACCESS_FILTER);
        SessionEnvelope envelope = (SessionEnvelope) request.context().get(PermissionAdvisor.CONTEXT_ENVELOPE);
        VectorStore store = vectorStoreProvider.getIfAvailable();

        // 未过权限校验：PermissionAdvisor 在前且 fail-closed，正常不会走到这里
        if (accessFilter == null || envelope == null) {
            return Decision.proceed(request);
        }
        // 检索能力不可用 → 拒答。原实现是"直通让模型用自身知识回答"，那等于输出无授权、
        // 不可溯源的内容，与"拒答优先"原则直接冲突，故改为拒答。
        if (store == null) {
            log.warn("RetrievalAdvisor: 未配置 VectorStore，拒绝作答（不允许模型用自身知识兜底）");
            return Decision.refuse(REFUSAL_KB_UNAVAILABLE, TEXT_KB_UNAVAILABLE);
        }

        String query = request.prompt().getUserMessage() == null
                ? "" : request.prompt().getUserMessage().getText();

        // 数据锁：过滤条件在向量库执行，只检索授权范围内的 chunk
        List<Document> docs = retrieve(store, query, accessFilter);
        if (docs.isEmpty()) {
            return Decision.refuse(REFUSAL_NO_HIT, TEXT_NO_HIT);
        }

        // 一层：门禁复核 + 密级不足时对代码块脱敏（防 metadata 脏数据 / 向量库过滤失效）
        List<Document> permitted = new ArrayList<>();
        for (Document d : docs) {
            Document p = prepareForUser(d, envelope);
            if (p != null) {
                permitted.add(p);
            }
        }
        if (permitted.isEmpty()) {
            log.warn("RetrievalAdvisor: 命中 {} 篇但全部未过权限门禁，拒答", docs.size());
            return Decision.refuse(REFUSAL_PERMISSION_FILTERED, TEXT_NO_HIT);
        }

        // 二层：时效门禁 —— 按业务日期剔除已废止 / 尚未生效的文件
        LocalDate bizDate = bizDate(request);
        List<Document> usable = new ArrayList<>();
        int stale = 0;
        for (Document d : permitted) {
            TemporalGate.State st = temporalGate.stateOf(d.getMetadata(), bizDate);
            if (st == TemporalGate.State.EXPIRED || st == TemporalGate.State.NOT_YET) {
                stale++;
                log.info("RetrievalAdvisor: 剔除{}文档 doc_id={}（业务日期 {}）",
                        st == TemporalGate.State.EXPIRED ? "已废止" : "未生效",
                        d.getMetadata().get("doc_id"), bizDate);
                continue;
            }
            usable.add(d);
        }
        if (usable.isEmpty()) {
            log.warn("RetrievalAdvisor: 命中 {} 篇但全部已废止/未生效（业务日期 {}），拒答", stale, bizDate);
            return Decision.refuse(REFUSAL_STALE, TEXT_STALE);
        }

        // 截断放在两层门禁<b>之后</b>：候选集放大就是为了让门禁剔除后仍能凑满 topK，
        // 若先截断再剔除，被剔掉的名额就白白浪费了（"命中 30 篇但 5 篇无权"会退化成只剩 1 篇）
        List<Document> selected = usable.size() > topK
                ? new ArrayList<>(usable.subList(0, topK))
                : usable;
        request.context().put(CONTEXT_RETRIEVED_DOCS, selected);
        Prompt newPrompt = request.prompt().augmentUserMessage(augment(query, selected, stale, bizDate));
        return Decision.proceed(new ChatClientRequest(newPrompt, request.context()));
    }

    /**
     * 多路召回 + RRF 融合。
     * <p>
     * 每一路都是"原问题或它的术语归一化变体"（见 {@link QueryExpander}），全部套同一个 accessFilter ——
     * <b>数据锁不能被查询改写绕过</b>：多出来的路只是换个问法，权限边界一条都不放宽。
     * <p>
     * 单路时融合结果与原顺序一致（RRF 只按排名计分，单路的排名就是它自己），
     * 因此术语表为空时本方法的行为与改造前的单次检索完全等价。
     */
    private List<Document> retrieve(VectorStore store, String query, String accessFilter) {
        List<String> queries = queryExpander.expand(query);
        if (queries.isEmpty()) {
            return List.of();
        }
        List<List<Document>> ranked = new ArrayList<>(queries.size());
        for (String q : queries) {
            ranked.add(store.similaritySearch(SearchRequest.builder()
                    .query(q)
                    .topK(candidatesPerQuery)
                    .filterExpression(accessFilter)
                    .build()));
        }
        if (ranked.size() == 1) {
            return ranked.get(0);   // 无扩展变体：免去一次融合，也避免无谓的重排
        }
        log.debug("多路召回：{} 路查询变体，融合后候选 {} 篇", ranked.size(),
                ranked.stream().mapToInt(List::size).sum());
        return rrfFusion.fuse(ranked, Document::getId);
    }

    /**
     * 业务日期：调用方可传（用于追溯"当时按哪一版制度办"），缺省今天。
     * <p>
     * 提到 {@code public} 是为了让 {@code org.agent.memory.TurnExtractor} 复用同一套口径 ——
     * 记忆要把"这一轮按哪天判的时效"落库，若在那边重写一遍"未传即今天"，
     * 两处口径就是这条链路上最容易悄悄漂移的地方。
     */
    public static LocalDate bizDate(ChatClientRequest request) {
        Object raw = request.context().get(PARAM_BIZ_DATE);
        if (raw == null) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(String.valueOf(raw).strip());
        } catch (DateTimeParseException e) {
            log.warn("bizDate 格式非法（{}），按今天处理", raw);
            return LocalDate.now();
        }
    }

    /** 拼装用户消息：原问题 + 作答约束 + 带编号的授权资料 */
    private static String augment(String query, List<Document> docs, int staleCount, LocalDate bizDate) {
        StringBuilder sb = new StringBuilder(query).append("\n\n");
        sb.append("【请仅基于下列授权资料作答，禁止编造，禁止用你自己的知识补充。")
                .append("引用资料时必须在句末标注编号，如 [1][2]。")
                .append("资料中密级不足的代码块已隐藏为占位符，可引用其存在，但禁止编造代码内容。")
                // 与 NumericGuardrailAdvisor 配对：这里事前约束，那里事后核对。
                // 少了这句，模型会按口语习惯改口径（"5万元"说成"五万元整"、"年利率"换算成"月利率"），
                // 事后校验就会把本来正确的回答拦掉 —— 用户体验是"答了又撤回"
                .append("金额、比例、期限必须与资料原文完全一致，不得改写口径、不得换算单位。")
                .append("若资料不足以回答，请直接回复「资料中未提及」，不要推测。】\n")
                .append("（业务日期：").append(bizDate).append("）\n");
        if (staleCount > 0) {
            sb.append("（另有 ").append(staleCount).append(" 篇相关文件已废止或尚未生效，未提供给你，严禁引用。）\n");
        }
        return sb.append(citedContext(docs)).toString();
    }

    /** 逐篇资料带编号与出处：[n] 即引文锚点，用户可据此回原文核对 */
    private static String citedContext(List<Document> docs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            if (i > 0) {
                sb.append("\n\n---\n\n");
            }
            sb.append('[').append(i + 1).append("] ")
                    .append(sourceLabel(docs.get(i))).append('\n')
                    .append(docs.get(i).getText());
        }
        return sb.toString();
    }

    /** 引文出处：来源：《标题》（doc_id） 版本 X · 章节 */
    private static String sourceLabel(Document doc) {
        Map<String, Object> m = doc.getMetadata();
        String title = Texts.trimToNull(m.get("doc_title"));
        String docId = Texts.trimToNull(m.get("doc_id"));
        String version = Texts.trimToNull(m.get("doc_version"));
        String section = Texts.trimToNull(m.get("section"));
        StringBuilder sb = new StringBuilder("来源：");
        if (title != null) {
            sb.append('《').append(title).append('》');
        }
        if (docId != null) {
            sb.append(title != null ? "（" + docId + "）" : docId);
        }
        if (title == null && docId == null) {
            sb.append("未标注文档");
        }
        if (version != null) {
            sb.append(" 版本 ").append(version);
        }
        if (section != null) {
            sb.append(" · ").append(section);
        }
        return sb.toString();
    }

    /** 拒答响应：本地构造，不经过模型 */
    private static ChatClientResponse refusalResponse(ChatClientRequest request, Decision d) {
        request.context().put(CONTEXT_REFUSAL_REASON, d.refusalReason());
        ChatResponse resp = new ChatResponse(List.of(new Generation(new AssistantMessage(d.refusalText()))));
        return new ChatClientResponse(resp, request.context());
    }

    /**
     * 引用归因校验：答案里的 [n] 必须落在本次提供的资料编号范围内，越界即告警并打标。
     * <p>
     * 当前只检测不重写答案（重写需重建 ChatResponse，会丢掉 token 用量等元数据）。
     * 升级方向：越界时降级为拒答，或做"片段对齐"（要求 [n] 能落到原文某一段落）。
     */
    private void auditCitations(ChatClientRequest request, ChatClientResponse response) {
        Object docs = request.context().get(CONTEXT_RETRIEVED_DOCS);
        if (!(docs instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        String answer = Texts.answerText(response);
        if (answer == null || answer.isBlank()) {
            return;
        }
        List<Integer> bad = invalidCitations(answer, list.size());
        if (!bad.isEmpty()) {
            request.context().put(CONTEXT_CITATION_VIOLATIONS, bad);
            log.warn("RetrievalAdvisor: 答案引用了不存在的资料编号 {}（本次仅提供 {} 篇），该答案需人工复核",
                    bad, list.size());
        }
    }

    /** 答案中所有不在 [1, max] 范围内的引用编号（按出现顺序，允许重复） */
    static List<Integer> invalidCitations(String answer, int max) {
        List<Integer> bad = new ArrayList<>();
        Matcher m = CITATION.matcher(answer);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n < 1 || n > max) {
                bad.add(n);
            }
        }
        return bad;
    }

    /**
     * 门禁（数据锁第一层）：kb_id ∈ 会话可检索库 && doc_level <= 会话密级 && orgPath ∈ org_codes。
     * 任一不符即拒绝（fail-closed，防 metadata 脏数据 / 向量库过滤失效）。
     * <p>
     * 不含 is_code 判定：代码块对低密级用户走"脱敏/丢弃"（见 {@link #prepareForUser}），
     * 不属于门禁拒绝；代码密级由 is_code 标签 + 密级阈值单独控制。
     */
    boolean passesGate(Document doc, SessionEnvelope envelope) {
        Object kbObj = doc.getMetadata().get("kb_id");
        if (kbObj == null || !envelope.kbIds().contains(String.valueOf(kbObj))) {
            return false;
        }
        Object level = doc.getMetadata().get("doc_level");
        int lv = level instanceof Number n ? n.intValue() : -1;
        if (lv < 1 || lv > envelope.dataClearanceLevel()) {
            return false;
        }
        return orgPathAllowed(doc.getMetadata().get("org_codes"), envelope.orgPath());
    }

    /**
     * 检索后处理（数据锁第二层）：门禁不过 → null（丢弃）。
     * <p>
     * 密级 ≥ 5 → 原文放行（可看代码）；密级 &lt; 5 → 对代码块脱敏：
     * <ul>
     *   <li>已标记 is_code 但脱敏器未定位到代码（裸代码/声明为代码却无内容）→ 无法确认已清干净，整块丢弃（宁缺毋滥）</li>
     *   <li>脱敏后仍有代码残留 → 整块丢弃（fail-closed）</li>
     *   <li>脱敏成功 → 保留脱敏后的 chunk（正文 + 代码占位符）</li>
     * </ul>
     */
    Document prepareForUser(Document doc, SessionEnvelope envelope) {
        if (!passesGate(doc, envelope)) {
            return null;
        }
        if (envelope.dataClearanceLevel() >= 5) {
            return doc;
        }
        CodeRedactor.RedactResult r = codeRedactor.redact(doc.getText());
        boolean taggedCode = Boolean.TRUE.equals(doc.getMetadata().get("is_code"));
        if (taggedCode && !r.hadCode()) {
            return null;
        }
        if (r.hadCode() && !r.fullyRedacted()) {
            return null;
        }
        return r.hadCode() ? doc.mutate().text(r.text()).build() : doc;
    }

    /** org_codes 支持 List 或逗号分隔字符串；授权组织清单显式含用户 orgPath 才算数，否则 fail-closed */
    private static boolean orgPathAllowed(Object orgs, String orgPath) {
        if (orgs instanceof Collection<?> c) {
            return c.stream().map(String::valueOf).anyMatch(orgPath::equals);
        }
        if (orgs instanceof String s) {
            return Arrays.stream(s.split(",")).map(String::trim).anyMatch(orgPath::equals);
        }
        return false;
    }

    @Override
    public int getOrder() {
        return 0; // 在 PermissionAdvisor(Ordered.HIGHEST_PRECEDENCE) 之后执行
    }

    @Override
    public String getName() {
        return "RetrievalAdvisor";
    }
}
