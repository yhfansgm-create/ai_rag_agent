package org.agent.api;

/**
 * 对话请求：携带会话凭证，走 PermissionAdvisor 校验。
 *
 * @param bizDate 业务日期（ISO-8601，可选）。用于时效判定：问"这笔业务当时该按哪版制度办"时传入，
 *                缺省按今天判定文档是否有效。
 */
public record ChatRequest(String sessionId, String sessionToken, String message, String bizDate) {

    /** 兼容不带业务日期的调用 */
    public ChatRequest(String sessionId, String sessionToken, String message) {
        this(sessionId, sessionToken, message, null);
    }
}
