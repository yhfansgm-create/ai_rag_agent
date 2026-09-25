package org.agent.api;

import org.agent.permission.CreateSessionRequest;
import org.agent.permission.CreateSessionResponse;
import org.agent.permission.SessionAuthException;
import org.agent.permission.SessionAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 会话管理 + 带权限校验的对话入口。
 *
 * <h2>实际路由（以配置为准）</h2>
 * <ul>
 *   <li>{@code POST /session} —— 建会话</li>
 *   <li>{@code POST /session/chat} —— 对话</li>
 *   <li>{@code POST /session/{sessionId}/close} —— 关闭会话</li>
 * </ul>
 * ⚠️ <b>没有 {@code /api} 前缀。</b>项目里<b>没有配置</b>
 * {@code server.servlet.context-path}，{@code server.port} 是 8123。
 * 但 {@code docs/} 下多篇文档和部分旧注释写的是 {@code /api/session/chat}、
 * {@code /api/kb/ingest} —— 那是<b>过时写法</b>，照它发请求会 404。
 * 以本注释和 {@code kb.http} 里的样例为准。
 * <p>
 * 若确实想要 {@code /api} 前缀，应在 {@code application.yml} 补
 * {@code server.servlet.context-path: /api}（一处配置即可，不必改本类），
 * 但那会同时改掉所有路由，需与 {@code kb.http}、文档一起对齐。
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
        String answer = bankAssistant.chat(req.sessionId(), req.sessionToken(), req.message(),
                parseBizDate(req.bizDate()));
        return new ChatReply(answer);
    }

    /** 业务日期可选；格式非法按"未指定"处理，不因一个可选出参把整次对话顶掉 */
    private static LocalDate parseBizDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
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
