package org.agent.permission;

/** 对话请求：携带会话凭证，走 PermissionAdvisor 校验 */
public record ChatRequest(String sessionId, String sessionToken, String message) {
}
