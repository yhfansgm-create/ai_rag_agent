package org.agent.permission;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 权限 Advisor —— 检索/生成前的"数据锁"。
 * <p>
 * 每次请求从 advisorParams 读 sessionId/sessionToken，调 {@link SessionAuthService#verifyAndLoad}
 * 做三层校验（fail-closed），通过后把"权限信封 + 检索过滤条件"写入 context，
 * 供下游检索 advisor 消费（下推给向量库 metadata filter）。
 * <p>
 * getOrder 取最小优先级，保证先于任何记忆/检索 advisor 执行。
 */
@Component
public class PermissionAdvisor implements CallAdvisor, StreamAdvisor {

    /** ChatClient advisor 参数键（由调用方通过 spec.param 传入） */
    public static final String PARAM_SESSION_ID = "sessionId";
    public static final String PARAM_SESSION_TOKEN = "sessionToken";

    /** context 键，下游检索/输出 advisor 从这读 */
    public static final String CONTEXT_ENVELOPE = "org.agent.permission.envelope";
    public static final String CONTEXT_ACCESS_FILTER = "org.agent.permission.accessFilter";

    private final SessionAuthService sessionAuthService;

    public PermissionAdvisor(SessionAuthService sessionAuthService) {
        this.sessionAuthService = sessionAuthService;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(checkPermission(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(checkPermission(request));
    }

    private ChatClientRequest checkPermission(ChatClientRequest request) {
        // Spring AI 1.0.0 中 spec.param(...) 的值会进 request.context()（MessageChatMemoryAdvisor 同款用法）
        String sessionId = getParam(request, PARAM_SESSION_ID);
        String sessionToken = getParam(request, PARAM_SESSION_TOKEN);

        if (sessionId == null || sessionId.isBlank()) {
            throw new SessionAuthException(SessionAuthException.Code.UNAUTHORIZED, "缺少会话标识");
        }
        // fail-closed：校验不通过直接抛异常，不进入模型调用
        SessionEnvelope envelope = sessionAuthService.verifyAndLoad(sessionId, sessionToken);

        // 传给下游：检索 advisor 读 accessFilter 下推向量库；输出侧读 envelope 做打码/高风险标记
        request.context().put(CONTEXT_ENVELOPE, envelope);
        request.context().put(CONTEXT_ACCESS_FILTER, envelope.accessFilter());
        return request;
    }

    private static String getParam(ChatClientRequest request, String key) {
        Object v = request.context().get(key);
        return v == null ? null : String.valueOf(v);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE; // 最先执行
    }

    @Override
    public String getName() {
        return "PermissionAdvisor";
    }
}
