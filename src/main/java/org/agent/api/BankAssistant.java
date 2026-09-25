package org.agent.api;

import org.agent.memory.MemoryAdvisor;
import org.agent.permission.PermissionAdvisor;
import org.agent.rag.guard.NumericGuardrailAdvisor;
import org.agent.rag.retrieve.RetrievalAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 带权限校验的银行内部助手：每次对话都过 PermissionAdvisor 三层校验，再走 RetrievalAdvisor 检索。
 * <p>
 * 管线：PermissionAdvisor(鉴权, 产 accessFilter 写入 context) → MemoryAdvisor(注入最近 N 轮历史)
 * → RetrievalAdvisor(按 accessFilter 下推向量库过滤检索)
 * → NumericGuardrailAdvisor(核对答案里的金额/比例/期限能否在检索到的原文中找到出处)。
 * <p>
 * Advisor 按 getOrder 升序由外到内：鉴权在最外层（最早拦、最晚收尾），数字校验在最内层。
 * sessionId/sessionToken 只通过 advisor 参数传入，不会拼进发给模型的 Prompt。
 * <p>
 * ⚠️ <b>MemoryAdvisor 必须在 RetrievalAdvisor 之前</b>，两条理由（详见 {@link MemoryAdvisor} 类注释）：
 * 一是功能上检索前要能拿到历史，二是安全上只有排在检索之前，它看到的用户消息才是未被
 * {@code augment()} 增强的那一份 —— 否则<b>带检索原文的整段资料会被写进历史</b>。
 * 这个位置在 code review 里看着人畜无害，改动前请先读那段注释。
 */
@Component
public class BankAssistant {

    private final ChatClient chatClient;

    public BankAssistant(@Qualifier("openAiChatModel") ChatModel chatModel,
                         PermissionAdvisor permissionAdvisor,
                         MemoryAdvisor memoryAdvisor,
                         RetrievalAdvisor retrievalAdvisor,
                         NumericGuardrailAdvisor numericGuardrailAdvisor) {
        // 传参顺序只为可读；实际执行顺序由各自 getOrder() 决定（Advisor 链内部按 order 排序）
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(permissionAdvisor, memoryAdvisor, retrievalAdvisor,
                        numericGuardrailAdvisor)
                .build();
    }

    public String chat(String sessionId, String sessionToken, String message) {
        return chat(sessionId, sessionToken, message, null);
    }

    /**
     * 带业务日期的对话。
     * <p>
     * bizDate 用于时效判定 —— "这笔业务发生在 X 日，当时该按哪一版制度办"，
     * 不传则按今天判定。传 null 表示"未指定"，检索层会退回今天。
     */
    public String chat(String sessionId, String sessionToken, String message, LocalDate bizDate) {
        return chatClient.prompt()
                .user(message)
                .advisors(spec -> {
                    spec.param(PermissionAdvisor.PARAM_SESSION_ID, sessionId)
                            .param(PermissionAdvisor.PARAM_SESSION_TOKEN, sessionToken);
                    if (bizDate != null) {
                        spec.param(RetrievalAdvisor.PARAM_BIZ_DATE, bizDate.toString());
                    }
                })
                .call()
                .content();
    }
}
