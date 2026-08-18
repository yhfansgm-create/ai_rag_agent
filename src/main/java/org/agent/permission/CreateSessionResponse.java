package org.agent.permission;

import java.time.LocalDateTime;

/**
 * 建会话响应。sessionToken 明文只在创建时返回一次（表中只存 SHA-256），
 * 后续请求用它做令牌校验。
 */
public record CreateSessionResponse(
        String sessionId,
        String sessionToken,
        LocalDateTime expiresAt,
        String accessFilter
) {
}
