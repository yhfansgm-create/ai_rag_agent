package org.agent.permission;

import org.agent.rag.RetrievalAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 带权限校验的银行内部助手：每次对话都过 PermissionAdvisor 三层校验，再走 RetrievalAdvisor 检索。
 * <p>
 * 管线：PermissionAdvisor(鉴权, 产 accessFilter 写入 context) → RetrievalAdvisor(按 accessFilter 下推向量库过滤检索)。
 * sessionId/sessionToken 只通过 advisor 参数传入，不会拼进发给模型的 Prompt。
 */
@Component
public class BankAssistant {

    private final ChatClient chatClient;

    public BankAssistant(@Qualifier("openAiChatModel") ChatModel chatModel,
                         PermissionAdvisor permissionAdvisor,
                         RetrievalAdvisor retrievalAdvisor) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(permissionAdvisor, retrievalAdvisor)
                .build();
    }

    public String chat(String sessionId, String sessionToken, String message) {
        return chatClient.prompt()
                .user(message)
                .advisors(spec -> spec
                        .param(PermissionAdvisor.PARAM_SESSION_ID, sessionId)
                        .param(PermissionAdvisor.PARAM_SESSION_TOKEN, sessionToken))
                .call()
                .content();
    }
}
