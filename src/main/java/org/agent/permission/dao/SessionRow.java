package org.agent.permission.dao;

import java.time.LocalDateTime;

/**
 * rag_assistant_session 行模型（MyBatis 结果映射用）。
 * <p>
 * accessScopeJson 对应表里 access_scope_json 列（{"kb_ids":[...]}），
 * 由 Service 层负责序列化/反序列化。
 */
public record SessionRow(
        String sessionId,
        String userId,
        String roleCode,
        String rankCode,
        String deptCode,
        String orgPath,
        int dataClearanceLevel,
        long authVersion,
        String permissionSource,
        int sessionStatus,
        LocalDateTime expiresAt,
        int quotaUsed,
        int quotaLimit,
        int authFailCount,
        String sessionTokenHash,
        String accessScopeJson
) {
}
