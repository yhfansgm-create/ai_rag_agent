package org.agent.permission.dao;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * rag_assistant_session 表 MyBatis DAO —— "权限信封"的持久化读写。
 * 注解式 SQL；后续复杂检索/分页可迁到 XML Mapper。
 */
@Mapper
public interface SessionDao {

    @Insert("""
            INSERT INTO rag_assistant_session
              (session_id, user_id, role_code, rank_code, dept_code, org_path,
               data_clearance_level, access_scope_json, auth_version, permission_source,
               session_status, session_token_hash, quota_used, quota_limit, expires_at)
            VALUES (#{sessionId}, #{userId}, #{roleCode}, #{rankCode}, #{deptCode}, #{orgPath},
                    #{dataClearanceLevel}, #{accessScopeJson}, #{authVersion}, #{permissionSource},
                    #{sessionStatus}, #{sessionTokenHash}, 0, #{quotaLimit}, #{expiresAt})
            """)
    int insert(SessionRow row);

    @Select("""
            SELECT session_id              AS sessionId,
                   user_id                AS userId,
                   role_code              AS roleCode,
                   rank_code              AS rankCode,
                   dept_code              AS deptCode,
                   org_path               AS orgPath,
                   data_clearance_level   AS dataClearanceLevel,
                   auth_version           AS authVersion,
                   permission_source      AS permissionSource,
                   session_status         AS sessionStatus,
                   expires_at             AS expiresAt,
                   quota_used             AS quotaUsed,
                   quota_limit            AS quotaLimit,
                   auth_fail_count        AS authFailCount,
                   session_token_hash     AS sessionTokenHash,
                   access_scope_json      AS accessScopeJson
            FROM rag_assistant_session
            WHERE session_id = #{sessionId} AND is_deleted = 0
            """)
    SessionRow findById(String sessionId);

    /** TOCTOU 后刷新权限快照 */
    @Update("""
            UPDATE rag_assistant_session
               SET role_code            = #{roleCode},
                   rank_code            = #{rankCode},
                   dept_code            = #{deptCode},
                   org_path             = #{orgPath},
                   data_clearance_level = #{dataClearanceLevel},
                   access_scope_json    = #{accessScopeJson},
                   auth_version         = #{authVersion},
                   permission_source    = #{permissionSource},
                   permission_checked_at = NOW(),
                   auth_fail_count      = 0
             WHERE session_id = #{sessionId}
            """)
    int updateSnapshot(SessionRow row);

    @Update("UPDATE rag_assistant_session SET quota_used = quota_used + 1 WHERE session_id = #{sessionId}")
    int incrementQuota(String sessionId);

    @Update("UPDATE rag_assistant_session SET auth_fail_count = auth_fail_count + 1 WHERE session_id = #{sessionId}")
    int incrementAuthFailCount(String sessionId);

    @Update("UPDATE rag_assistant_session SET auth_fail_count = 0 WHERE session_id = #{sessionId}")
    int resetAuthFailCount(String sessionId);

    @Update("UPDATE rag_assistant_session SET session_status = #{status} WHERE session_id = #{sessionId}")
    int setSessionStatus(@Param("sessionId") String sessionId, @Param("status") int status);

    @Update("UPDATE rag_assistant_session SET last_active_at = NOW() WHERE session_id = #{sessionId}")
    int touch(String sessionId);
}
