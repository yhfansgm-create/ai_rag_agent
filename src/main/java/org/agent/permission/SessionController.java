package org.agent.permission;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 会话管理 + 带权限校验的对话入口。
 * 路由在 server.servlet.context-path=/api 下，即 /api/session、/api/session/chat 等。
 */
@RestController
@RequestMapping("/session")
public class SessionController {

    private final SessionAuthService sessionAuthService;
    private final BankAssistant bankAssistant;

    public SessionController(SessionAuthService sessionAuthService, BankAssistant bankAssistant) {
        this.sessionAuthService = sessionAuthService;
        this.bankAssistant = bankAssistant;
    }

    /** 建会话：入参 userId，返回 sessionId + sessionToken（token 仅此一次返回明文） */
    @PostMapping
    public CreateSessionResponse createSession(@RequestBody CreateSessionRequest req) {
        return sessionAuthService.createSession(req.userId());
    }

    /** 对话：走 PermissionAdvisor 三层校验，fail-closed */
    @PostMapping("/chat")
    public ChatReply chat(@RequestBody ChatRequest req) {
        String answer = bankAssistant.chat(req.sessionId(), req.sessionToken(), req.message());
        return new ChatReply(answer);
    }

    /** 关闭会话 */
    @PostMapping("/{sessionId}/close")
    public Map<String, Object> close(@PathVariable String sessionId) {
        sessionAuthService.closeSession(sessionId);
        return Map.of("closed", true, "sessionId", sessionId);
    }

    @ExceptionHandler(SessionAuthException.class)
    public ResponseEntity<Map<String, Object>> handleAuthException(SessionAuthException e) {
        HttpStatus status = switch (e.getCode()) {
            case UNAUTHORIZED, TOKEN_MISMATCH -> HttpStatus.UNAUTHORIZED;      // 401
            case SESSION_EXPIRED, SESSION_NOT_ACTIVE, SESSION_LOCKED,
                 PERMISSION_CHANGED, QUOTA_EXCEEDED -> HttpStatus.FORBIDDEN;   // 403
        };
        return ResponseEntity.status(status).body(Map.of("code", e.getCode().name(), "message", e.getMessage()));
    }
}
