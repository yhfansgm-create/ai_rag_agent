package org.agent.rag;

import lombok.extern.slf4j.Slf4j;
import org.agent.permission.PermissionAdvisor;
import org.agent.permission.SessionEnvelope;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

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

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final int topK;
    private final CodeRedactor codeRedactor;

    @Autowired
    public RetrievalAdvisor(ObjectProvider<VectorStore> vectorStoreProvider,
                            @Value("${rag.retrieval.top-k:5}") int topK,
                            CodeRedactor codeRedactor) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.topK = topK;
        this.codeRedactor = codeRedactor;
    }

    /** 测试便捷构造：无 Spring 容器时使用默认脱敏器 */
    public RetrievalAdvisor(ObjectProvider<VectorStore> vectorStoreProvider, int topK) {
        this(vectorStoreProvider, topK, new CodeRedactor());
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(before(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(before(request));
    }

    private ChatClientRequest before(ChatClientRequest request) {
        String accessFilter = (String) request.context().get(PermissionAdvisor.CONTEXT_ACCESS_FILTER);
        SessionEnvelope envelope = (SessionEnvelope) request.context().get(PermissionAdvisor.CONTEXT_ENVELOPE);
        VectorStore store = vectorStoreProvider.getIfAvailable();

        // 未过权限校验，或向量库未配置 → 不检索直通（配置好后自动生效）
        if (accessFilter == null || envelope == null) {
            return request;
        }
        if (store == null) {
            log.warn("RetrievalAdvisor: 未配置 VectorStore，本次未检索，LLM 将基于自身知识回答（非授权资料）");
            return request;
        }

        String query = request.prompt().getUserMessage() == null
                ? "" : request.prompt().getUserMessage().getText();

        // 数据锁：过滤条件在向量库执行，只检索授权范围内的 chunk
        List<Document> docs = store.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(accessFilter)
                .build());

        // 检索后处理：门禁复核 + 密级不足时对代码块脱敏（防 metadata 脏数据 / 向量库过滤失效）
        List<Document> safe = docs.stream()
                .map(d -> prepareForUser(d, envelope))
                .filter(Objects::nonNull)
                .toList();
        request.context().put(CONTEXT_RETRIEVED_DOCS, safe);

        String context = safe.stream().map(Document::getText).collect(Collectors.joining("\n\n---\n\n"));
        String augmented = query + "\n\n【请仅基于下列授权资料作答，禁止编造。资料中密级不足的代码块已隐藏为占位符，"
                + "可引用其存在，但禁止编造代码内容。若资料为空，请回复：无权访问或知识库中无相关内容。】\n"
                + context;
        Prompt newPrompt = request.prompt().augmentUserMessage(augmented);
        return new ChatClientRequest(newPrompt, request.context());
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
     *   <li>已标记 is_code 但脱敏器未定位到代码 → 无法确认已清干净，整块丢弃（宁缺毋滥）</li>
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
