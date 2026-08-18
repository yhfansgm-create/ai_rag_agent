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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Collection;
import java.util.List;
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

    public RetrievalAdvisor(ObjectProvider<VectorStore> vectorStoreProvider,
                            @Value("${rag.retrieval.top-k:5}") int topK) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.topK = topK;
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

        // 双保险：旁路校验逐条确认 chunk 在授权范围内（防 metadata 脏数据）
        List<Document> safe = docs.stream()
                .filter(d -> isAuthorized(d, envelope))
                .toList();
        request.context().put(CONTEXT_RETRIEVED_DOCS, safe);

        String context = safe.stream().map(Document::getText).collect(Collectors.joining("\n\n---\n\n"));
        String augmented = query + "\n\n【请仅基于下列授权资料作答，禁止编造。若资料为空，请回复：无权访问或知识库中无相关内容。】\n"
                + context;
        Prompt newPrompt = request.prompt().augmentUserMessage(augmented);
        return new ChatClientRequest(newPrompt, request.context());
    }

    /** 旁路校验：kb_id ∈ 用户可检索库 && doc_level <= 用户密级 && 用户组织 ∈ 文档 org_codes */
    private boolean isAuthorized(Document doc, SessionEnvelope envelope) {
        String kb = String.valueOf(doc.getMetadata().get("kb_id"));
        if (!envelope.kbIds().contains(kb)) {
            return false;
        }
        Object level = doc.getMetadata().get("doc_level");
        int lv = level instanceof Number n ? n.intValue() : -1;
        if (lv < 1 || lv > envelope.dataClearanceLevel()) {
            return false;
        }
        Object orgs = doc.getMetadata().get("org_codes");
        if (orgs instanceof Collection<?> c) {
            return c.stream().map(String::valueOf).anyMatch(envelope.orgPath()::equals);
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
