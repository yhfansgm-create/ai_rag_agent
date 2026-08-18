package org.agent.permission;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 校验通过后的"会话权限信封"，由 PermissionAdvisor 写入 ChatClient context，
 * 供下游检索 advisor（数据锁执行）和输出侧（打码/高风险标记）使用。
 */
public record SessionEnvelope(
        String sessionId,
        String userId,
        String roleCode,
        String rankCode,
        String deptCode,
        String orgPath,
        int dataClearanceLevel,
        List<String> kbIds,
        long authVersion,
        String permissionSource,
        LocalDateTime expiresAt,
        /** 检索层 metadata filter DSL，如 kb_id in ['kb-teller'] && doc_level <= 2 && org_codes in ['0100/0101'] */
        String accessFilter
) {
}
