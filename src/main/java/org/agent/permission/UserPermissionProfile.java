package org.agent.permission;

import java.util.List;

/**
 * IAM/权限中心返回的用户权限画像 —— 校验的"数据源"。
 * 应用本身不持有用户数据，只把这个画像在会话创建时快照进 rag_assistant_session，
 * 再靠 auth_version 每请求核对快照新鲜度。
 */
public record UserPermissionProfile(
        String userId,
        String roleCode,
        String rankCode,
        String deptCode,
        String orgPath,
        /** 可访问数据密级上限: 1公开/2内部/3秘密/4机密/5绝密(参照 JR/T 0197-2020) */
        int dataClearanceLevel,
        /** 可检索的知识库/向量分片 ID 列表 */
        List<String> kbIds,
        /** IAM 当前权限版本号 */
        long authVersion
) {
}
