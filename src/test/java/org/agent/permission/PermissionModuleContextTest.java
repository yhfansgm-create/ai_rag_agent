package org.agent.permission;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 集成测试：连真实 MySQL 走一遍 createSession → verifyAndLoad 往返，不调 LLM。
 * 需要本机 MySQL(127.0.0.1:3306/fan_ai_agent) 在运行。
 */
@SpringBootTest
@ActiveProfiles("local")
class PermissionModuleContextTest {

    @Autowired
    private SessionAuthService sessionAuthService;
    @Autowired
    private MockUserPermissionProvider mockIam;

    @AfterEach
    void reset() {
        mockIam.reset();
    }

    @Test
    @DisplayName("应用上下文启动 + 建会话/校验往返")
    void createAndVerifyRoundTrip() {
        CreateSessionResponse created = sessionAuthService.createSession("10086");
        assertThat(created.sessionToken()).isNotBlank();

        SessionEnvelope envelope = sessionAuthService.verifyAndLoad(created.sessionId(), created.sessionToken());
        assertThat(envelope.accessFilter()).contains("kb-teller");
        assertThat(envelope.roleCode()).isEqualTo("TELLER");

        sessionAuthService.closeSession(created.sessionId());
    }

    @Test
    @DisplayName("错误令牌 fail-closed")
    void wrongTokenFailsClosed() {
        CreateSessionResponse created = sessionAuthService.createSession("10087");

        assertThatThrownBy(() -> sessionAuthService.verifyAndLoad(created.sessionId(), "wrong-token"))
                .isInstanceOf(SessionAuthException.class)
                .extracting(e -> ((SessionAuthException) e).getCode())
                .isEqualTo(SessionAuthException.Code.TOKEN_MISMATCH);
    }

    @Test
    @DisplayName("权限版本变更(TOCTOU) → 刷新快照")
    void authVersionBumpRefreshesSnapshot() {
        CreateSessionResponse created = sessionAuthService.createSession("10086");
        long vBefore = sessionAuthService.verifyAndLoad(created.sessionId(), created.sessionToken()).authVersion();

        mockIam.bumpAuthVersion("10086");

        SessionEnvelope after = sessionAuthService.verifyAndLoad(created.sessionId(), created.sessionToken());
        assertThat(after.authVersion()).isGreaterThan(vBefore);
    }
}
