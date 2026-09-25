package org.agent.playground;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 应用（对照 {@link LoveApp}）：把「检索」和「问答」两步拆开暴露，
 * 便于单独评估向量库检索效果，也便于把检索结果直接喂给 LLM 作答。
 * <p>
 * 与 {@code RetrievalAdvisor} 的区别：Advisor 是挂在对话链路上的自动检索（带权限门禁），
 * 这里是最朴素的「检索 + 拼接 + 作答」，用于测试/调参，不含权限过滤。
 * <p>
 * VectorStore 用 ObjectProvider 注入：未开启 rag.vectorstore.enabled 时应用照常启动，
 * 调用检索方法时才报错提示。
 * <p>
 * <b>它不在银行 RAG 主链路上</b>（主链路见 {@code org.agent.api.BankAssistant}），
 * 是留在 {@code playground} 包里的检索评测工具 —— {@link #retrieve} 不调 LLM，
 * 调检索效果时用它能把"召回质量"和"生成质量"分开看。
 */
@Slf4j
@Component
public class RagApp {

    private final ChatClient chatClient;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final int defaultTopK;

    private static final String SYSTEM_PROMPT = "扮演企业知识库问答助手。只依据【参考资料】作答，"
            + "资料未覆盖的内容直接回复「知识库中无相关内容」，禁止编造。回答时标注依据的章节名。";

    public RagApp(@Qualifier("openAiChatModel") ChatModel chatModel,
                  ObjectProvider<VectorStore> vectorStoreProvider,
                  @Value("${rag.retrieval.top-k:5}") int defaultTopK) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.defaultTopK = defaultTopK;

        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(10)
                .build();

        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /**
     * 纯检索（不调 LLM）：按配置的 top-k 从向量库取回最相似的 chunk。
     * 评估检索效果时用这个，避免把 LLM 的生成质量混进来。
     */
    public List<Document> retrieve(String query) {
        return retrieve(query, defaultTopK);
    }

    public List<Document> retrieve(String query, int topK) {
        return requireVectorStore().similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .build());
    }

    /**
     * 把检索结果打成一行一条的可读文本，用于日志/测试断言。
     * score 由向量库给出（PgVectorStore 走余弦距离，score = 1 - distance，越大越相似）。
     */
    public static String describeHits(List<Document> docs) {
        if (docs.isEmpty()) {
            return "(无命中)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            Document d = docs.get(i);
            Double score = d.getScore();
            sb.append(String.format("%n#%d score=%.4f doc_id=%s level=%s section=%s%n   %s",
                    i + 1,
                    score == null ? -1d : score,
                    d.getMetadata().get("doc_id"),
                    d.getMetadata().get("doc_level"),
                    d.getMetadata().get("section"),
                    excerpt(d.getText())));
        }
        return sb.toString();
    }

    /**
     * RAG 问答：先检索，再把命中的 chunk 拼进 prompt，让 LLM 只依据资料作答。
     * 命中为空时依然调 LLM，由 system prompt 引导其回复「知识库中无相关内容」。
     */
    public String doChat(String message, String chatId) {
        List<Document> docs = retrieve(message);
        log.info("检索到 {} 条资料:{}", docs.size(), describeHits(docs));

        String context = docs.isEmpty() ? "(无)" : docs.stream()
                .map(d -> "【" + d.getMetadata().getOrDefault("section", "未分节") + "】\n" + d.getText())
                .collect(Collectors.joining("\n\n---\n\n"));

        // 不用 prompt 模板的 {} 占位符：资料原文里可能含花括号，直接拼更稳（与 RetrievalAdvisor 同思路）
        String userMessage = message + "\n\n【参考资料】\n" + context + "\n\n请仅基于上述参考资料作答。";

        String answer = chatClient.prompt()
                .user(userMessage)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .content();
        log.info("answer is :{}", answer);
        return answer;
    }

    private VectorStore requireVectorStore() {
        VectorStore store = vectorStoreProvider.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException("未配置 VectorStore：请启用 rag.vectorstore.enabled 并配置 PG 数据源 + embedding 模型");
        }
        return store;
    }

    /** 摘要：压掉换行并截断，避免日志被整篇文档刷屏 */
    private static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "...";
    }
}
