package org.agent.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.agent.permission.dao.SessionDao;
import org.agent.permission.dao.SessionRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 三层校验逻辑的单元测试（不依赖 DB / LLM）。
 */
@ExtendWith(MockitoExtension.class)
class SessionAuthServiceTest {

    @Mock
    private SessionDao sessionDao;
    @Mock
    private UserPermissionProvider permissionProvider;

    private SessionAuthService service;

    @BeforeEach
    void setUp() {
        service = new SessionAuthService(sessionDao, permissionProvider, new ObjectMapper());
    }

    private UserPermissionProfile profile(String userId, long authVersion) {
        return new UserPermissionProfile(userId, "TELLER", "T3", "0101", "0100/0101",
                2, List.of("kb-teller"), authVersion);
    }

    private SessionRow row(String sessionId, String userId, long authVersion, int status,
                           int quotaUsed, int quotaLimit, int failCount, String tokenHash) {
        return new SessionRow(sessionId, userId, "TELLER", "T3", "0101", "0100/0101",
                2, authVersion, SessionAuthService.PERMISSION_SOURCE_MOCK, status,
                LocalDateTime.now().plusHours(1), quotaUsed, quotaLimit, failCount, tokenHash,
                "{\"kb_ids\":[\"kb-teller\"]}");
    }

    @Test
    @DisplayName("建会话：从 IAM 拉画像并生成令牌/过滤条件")
    void createSession_snapshotsProfile() {
        when(permissionProvider.loadProfile("10086")).thenReturn(profile("10086", 0L));

        CreateSessionResponse resp = service.createSession("10086");

        assertThat(resp.sessionId()).isNotBlank();
        assertThat(resp.sessionToken()).isNotBlank();
        assertThat(resp.expiresAt()).isNotNull();
        assertThat(resp.accessFilter()).contains("kb-teller").contains("doc_level <= 2").doesNotContain("is_code");
        verify(sessionDao).insert(any(SessionRow.class));
    }

    @Test
    @DisplayName("未知用户：身份拒绝")
    void createSession_unknownUser_throws() {
        when(permissionProvider.loadProfile("99999")).thenThrow(
                new SessionAuthException(SessionAuthException.Code.UNAUTHORIZED, "身份不可识别"));

        assertThatThrownBy(() -> service.createSession("99999"))
                .isInstanceOf(SessionAuthException.class)
                .extracting(e -> ((SessionAuthException) e).getCode())
                .isEqualTo(SessionAuthException.Code.UNAUTHORIZED);
    }

    @Test
    @DisplayName("校验通过：返回带 accessFilter 的权限信封")
    void verifyAndLoad_happyPath() {
        String token = "good-token";
        String hash = sha256(token);
        when(sessionDao.findById("s1")).thenReturn(row("s1", "10086", 0L, 1, 0, 100, 0, hash));
        when(permissionProvider.currentAuthVersion("10086")).thenReturn(0L);

        SessionEnvelope env = service.verifyAndLoad("s1", token);

        assertThat(env.accessFilter()).contains("kb-teller");
        assertThat(env.roleCode()).isEqualTo("TELLER");
        verify(sessionDao).incrementQuota("s1");
        verify(sessionDao).resetAuthFailCount("s1");
    }

    @Test
    @DisplayName("会话不存在：UNAUTHORIZED")
    void verifyAndLoad_missingSession() {
        when(sessionDao.findById("none")).thenReturn(null);

        assertThatThrownBy(() -> service.verifyAndLoad("none", "t"))
                .isInstanceOf(SessionAuthException.class)
                .extracting(e -> ((SessionAuthException) e).getCode())
                .isEqualTo(SessionAuthException.Code.UNAUTHORIZED);
    }

    @Test
    @DisplayName("会话已过期：SESSION_EXPIRED")
    void verifyAndLoad_expired() {
        String token = "t";
        SessionRow expired = new SessionRow(
                "s1", "10086", "TELLER", "T3", "0101", "0100/0101", 2, 0L,
                SessionAuthService.PERMISSION_SOURCE_MOCK, 1, LocalDateTime.now().minusMinutes(1),
                0, 100, 0, sha256(token), "{\"kb_ids\":[\"kb-teller\"]}");
        when(sessionDao.findById("s1")).thenReturn(expired);

        assertThatThrownBy(() -> service.verifyAndLoad("s1", token))
                .isInstanceOf(SessionAuthException.class)
                .extracting(e -> ((SessionAuthException) e).getCode())
                .isEqualTo(SessionAuthException.Code.SESSION_EXPIRED);
    }

    @Test
    @DisplayName("令牌不匹配：TOKEN_MISMATCH 并计数失败")
    void verifyAndLoad_tokenMismatch() {
        when(sessionDao.findById("s1")).thenReturn(row("s1", "10086", 0L, 1, 0, 100, 0, sha256("right")));

        assertThatThrownBy(() -> service.verifyAndLoad("s1", "wrong"))
                .isInstanceOf(SessionAuthException.class)
                .extracting(e -> ((SessionAuthException) e).getCode())
                .isEqualTo(SessionAuthException.Code.TOKEN_MISMATCH);
        verify(sessionDao).incrementAuthFailCount("s1");
    }

    @Test
    @DisplayName("连续失败达到阈值：会话被锁定")
    void verifyAndLoad_lockAfterMaxFailures() {
        when(sessionDao.findById("s1")).thenReturn(row("s1", "10086", 0L, 1, 0, 100, 2, sha256("right")));

        assertThatThrownBy(() -> service.verifyAndLoad("s1", "wrong"))
                .isInstanceOf(SessionAuthException.class)
                .extracting(e -> ((SessionAuthException) e).getCode())
                .isEqualTo(SessionAuthException.Code.SESSION_LOCKED);
        verify(sessionDao).setSessionStatus("s1", SessionAuthService.STATUS_TERMINATED);
    }

    @Test
    @DisplayName("TOCTOU：权限版本变更后重新拉取并刷新快照")
    void verifyAndLoad_versionBump_refreshesSnapshot() {
        String token = "t";
        String hash = sha256(token);
        when(sessionDao.findById("s1")).thenReturn(row("s1", "10086", 0L, 1, 0, 100, 0, hash));
        when(permissionProvider.currentAuthVersion("10086")).thenReturn(5L);
        when(permissionProvider.loadProfile("10086")).thenReturn(profile("10086", 5L));

        SessionEnvelope env = service.verifyAndLoad("s1", token);

        assertThat(env.authVersion()).isEqualTo(5L);
        verify(sessionDao).updateSnapshot(any(SessionRow.class));
    }

    @Test
    @DisplayName("用户在权限中心已失效：会话强制终止")
    void verifyAndLoad_userRevoked() {
        String token = "t";
        String hash = sha256(token);
        when(sessionDao.findById("s1")).thenReturn(row("s1", "10086", 0L, 1, 0, 100, 0, hash));
        when(permissionProvider.currentAuthVersion("10086")).thenReturn(-1L);

        assertThatThrownBy(() -> service.verifyAndLoad("s1", token))
                .isInstanceOf(SessionAuthException.class)
                .extracting(e -> ((SessionAuthException) e).getCode())
                .isEqualTo(SessionAuthException.Code.PERMISSION_CHANGED);
        verify(sessionDao).setSessionStatus("s1", SessionAuthService.STATUS_TERMINATED);
    }

    @Test
    @DisplayName("配额超限：熔断转人工")
    void verifyAndLoad_quotaExceeded() {
        String token = "t";
        String hash = sha256(token);
        when(sessionDao.findById("s1")).thenReturn(row("s1", "10086", 0L, 1, 100, 100, 0, hash));
        when(permissionProvider.currentAuthVersion("10086")).thenReturn(0L);

        assertThatThrownBy(() -> service.verifyAndLoad("s1", token))
                .isInstanceOf(SessionAuthException.class)
                .extracting(e -> ((SessionAuthException) e).getCode())
                .isEqualTo(SessionAuthException.Code.QUOTA_EXCEEDED);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
