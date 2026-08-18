package org.agent.permission;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.agent.permission.dao.SessionDao;
import org.agent.permission.dao.SessionRow;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话生命周期 + 三层权限校验。
 * <p>
 * 校验模型（回答"不存用户数据怎么校验"）：
 * <ol>
 *   <li><b>会话有效性</b>：状态/过期/令牌哈希 —— 会话本身的完整性</li>
 *   <li><b>快照新鲜度(TOCTOU)</b>：auth_version 与 IAM 当前版本比对，变了就重拉权限刷新快照；
 *       用户在 IAM 已不存在则强制终止会话</li>
 *   <li><b>资源锁</b>：配额计数，超限熔断转人工</li>
 * </ol>
 * 全部 fail-closed：任一环不通过抛 {@link SessionAuthException}，绝不降级。
 * <p>
 * 持久层走 MyBatis（{@link SessionDao}），行模型 {@link SessionRow} 在 dao 包下。
 */
@Service
public class SessionAuthService {

    public static final String PERMISSION_SOURCE_MOCK = "MOCK";
    public static final String PERMISSION_SOURCE_IAM = "IAM";

    /** 会话状态（与会话表约定一致） */
    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_CLOSED = 3;
    public static final int STATUS_TERMINATED = 4; // 越权/异常/锁定强制终止

    private static final int SESSION_TTL_HOURS = 8;
    private static final int DEFAULT_QUOTA_LIMIT = 100;
    private static final int MAX_AUTH_FAIL = 3;

    private final SessionDao sessionDao;
    private final UserPermissionProvider permissionProvider;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public SessionAuthService(SessionDao sessionDao, UserPermissionProvider permissionProvider,
                              ObjectMapper objectMapper) {
        this.sessionDao = sessionDao;
        this.permissionProvider = permissionProvider;
        this.objectMapper = objectMapper;
    }

    /** 建会话：从 IAM 拉权限画像，快照写入会话表，返回会话凭证。 */
    public CreateSessionResponse createSession(String userId) {
        UserPermissionProfile profile = permissionProvider.loadProfile(userId);
        String sessionId = UUID.randomUUID().toString();
        String sessionToken = generateToken();
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(SESSION_TTL_HOURS);

        sessionDao.insert(new SessionRow(
                sessionId, userId, profile.roleCode(), profile.rankCode(), profile.deptCode(),
                profile.orgPath(), profile.dataClearanceLevel(), profile.authVersion(),
                PERMISSION_SOURCE_MOCK, STATUS_ACTIVE, expiresAt, 0, DEFAULT_QUOTA_LIMIT, 0,
                sha256(sessionToken), toAccessScopeJson(profile.kbIds())));

        return new CreateSessionResponse(sessionId, sessionToken, expiresAt, buildAccessFilter(profile));
    }

    /**
     * 每次请求的三层校验，通过则返回"权限信封"（供检索/输出侧使用）。
     *
     * @throws SessionAuthException 任一层不通过（fail-closed）
     */
    public SessionEnvelope verifyAndLoad(String sessionId, String sessionToken) {
        SessionRow row = sessionDao.findById(sessionId);
        if (row == null) {
            throw new SessionAuthException(SessionAuthException.Code.UNAUTHORIZED, "会话不存在: " + sessionId);
        }

        // ① 会话有效性
        if (row.sessionStatus() != STATUS_ACTIVE) {
            throw new SessionAuthException(SessionAuthException.Code.SESSION_NOT_ACTIVE,
                    "会话状态异常: " + row.sessionStatus());
        }
        if (row.expiresAt() != null && LocalDateTime.now().isAfter(row.expiresAt())) {
            throw new SessionAuthException(SessionAuthException.Code.SESSION_EXPIRED, "会话已过期");
        }

        // ② 令牌校验（fail-closed + 失败计数锁定，防重放爆破）
        if (!sha256(sessionToken).equals(row.sessionTokenHash())) {
            sessionDao.incrementAuthFailCount(sessionId);
            if (row.authFailCount() + 1 >= MAX_AUTH_FAIL) {
                sessionDao.setSessionStatus(sessionId, STATUS_TERMINATED);
                throw new SessionAuthException(SessionAuthException.Code.SESSION_LOCKED, "连续校验失败过多，会话已锁定，转人工");
            }
            throw new SessionAuthException(SessionAuthException.Code.TOKEN_MISMATCH, "会话令牌不匹配");
        }

        // ③ 快照新鲜度（TOCTOU）：版本变化 → 重新拉权限并刷新快照
        UserPermissionProfile profile;
        long currentVersion = permissionProvider.currentAuthVersion(row.userId());
        if (currentVersion > row.authVersion()) {
            profile = permissionProvider.loadProfile(row.userId());
            sessionDao.updateSnapshot(snapshotRow(row, profile));
        } else if (currentVersion < 0) {
            sessionDao.setSessionStatus(sessionId, STATUS_TERMINATED);
            throw new SessionAuthException(SessionAuthException.Code.PERMISSION_CHANGED,
                    "用户在权限中心已失效，会话强制终止");
        } else {
            profile = toProfile(row);
        }

        // ④ 资源锁：配额熔断
        if (row.quotaUsed() + 1 > row.quotaLimit()) {
            throw new SessionAuthException(SessionAuthException.Code.QUOTA_EXCEEDED, "本会话调用次数超限，已熔断，转人工");
        }
        sessionDao.incrementQuota(sessionId);
        sessionDao.resetAuthFailCount(sessionId);
        sessionDao.touch(sessionId);

        return new SessionEnvelope(row.sessionId(), row.userId(), profile.roleCode(), profile.rankCode(),
                profile.deptCode(), profile.orgPath(), profile.dataClearanceLevel(), profile.kbIds(),
                profile.authVersion(), row.permissionSource(), row.expiresAt(), buildAccessFilter(profile));
    }

    public void closeSession(String sessionId) {
        sessionDao.setSessionStatus(sessionId, STATUS_CLOSED);
    }

    /**
     * 生成检索层 metadata filter DSL（数据锁的载体）。
     * 后续真正的检索 advisor 从 context 读 accessFilter 并下推给向量库：
     * SearchRequest.builder().query(q).filterExpression(accessFilter).build()
     */
    public String buildAccessFilter(UserPermissionProfile p) {
        if (p.kbIds() == null || p.kbIds().isEmpty()) {
            throw new SessionAuthException(SessionAuthException.Code.UNAUTHORIZED, "用户无任何可检索知识库，拒绝访问");
        }
        String kb = String.join("', '", p.kbIds());
        return "kb_id in ['" + kb + "'] && doc_level <= " + p.dataClearanceLevel()
                + " && org_codes in ['" + p.orgPath() + "']";
    }

    /** 用新权限画像构造待刷新的行（保留原会话标识/状态/配额等） */
    private SessionRow snapshotRow(SessionRow row, UserPermissionProfile profile) {
        return new SessionRow(row.sessionId(), row.userId(), profile.roleCode(), profile.rankCode(),
                profile.deptCode(), profile.orgPath(), profile.dataClearanceLevel(), profile.authVersion(),
                row.permissionSource(), row.sessionStatus(), row.expiresAt(), row.quotaUsed(), row.quotaLimit(),
                row.authFailCount(), row.sessionTokenHash(), toAccessScopeJson(profile.kbIds()));
    }

    private UserPermissionProfile toProfile(SessionRow row) {
        return new UserPermissionProfile(row.userId(), row.roleCode(), row.rankCode(), row.deptCode(),
                row.orgPath(), row.dataClearanceLevel(), parseKbIds(row.accessScopeJson()), row.authVersion());
    }

    private String toAccessScopeJson(List<String> kbIds) {
        try {
            return objectMapper.writeValueAsString(Map.of("kb_ids", kbIds));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("access_scope_json 序列化失败", e);
        }
    }

    private List<String> parseKbIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> m = objectMapper.readValue(json, new TypeReference<>() {
            });
            Object kb = m.get("kb_ids");
            if (kb instanceof List<?> list) {
                return list.stream().map(String::valueOf).toList();
            }
            return List.of();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("access_scope_json 解析失败", e);
        }
    }

    private String generateToken() {
        byte[] b = new byte[32];
        secureRandom.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
