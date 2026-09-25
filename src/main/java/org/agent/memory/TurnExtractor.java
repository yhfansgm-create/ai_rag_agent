package org.agent.memory;

import org.agent.memory.model.AnswerDecision;
import org.agent.memory.model.CitedChunkRef;
import org.agent.memory.model.TurnFacts;
import org.agent.permission.SessionEnvelope;
import org.agent.rag.retrieve.RetrievalAdvisor;
import org.agent.support.Texts;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 把一次问答的"事实"抽出来，供落库（纯函数，无状态）。
 *
 * <h2>① 为什么入参是"原始 request"</h2>
 * 传 {@code originalRequest}（{@code MemoryAdvisor} 收到的那一份），而不是注入历史后重建的那一份。
 * {@code MemoryAdvisor} 的 order 比 {@code RetrievalAdvisor} 小，所以这一份里的用户消息
 * <b>还没被 {@code augment()} 增强过</b> —— 这就是"绝不把检索原文写进历史"在代码里的落点。
 * <p>
 * 已核实 {@code Prompt.augmentUserMessage()} 返回的是<b>新</b> Prompt（内部复制了消息列表），
 * 所以无论本轮有没有注入历史，{@code originalRequest.prompt()} 始终是未增强的那一份。
 * 一旦有人把 {@code MemoryAdvisor} 挪到 {@code RetrievalAdvisor} 之后，这里拿到的就是
 * 带检索原文的增强消息（RAG-07 §一 的 order 表）。
 *
 * <h2>② 为什么"检索结果 / 拒答原因"从 response 读，而不是从 request 读</h2>
 * 这是个容易写错、且错了会静默失效的地方：
 * {@code ChatClientRequest.mutate()} 内部是 {@code new HashMap<>(this.context)}，
 * 即<b>复制</b>了一份 context。{@code MemoryAdvisor} 注入历史时走了 {@code mutate()}，
 * 于是 {@code RetrievalAdvisor} 的 {@code put(CONTEXT_RETRIEVED_DOCS, ...)} 落在
 * <b>复制后的那份</b> map 上 —— 原始 request 的 map 里根本没有这两个键。
 * <p>
 * 差别还会随轮次变化：本轮<b>没有</b>历史时 {@code injectHistory} 不重建 request，
 * 检索就写在原始 map 上。于是"从原始 request 读"在第一轮能读到、第二轮起读不到 ——
 * 表现为拒答被误判成放行、引文指针恒为空，且不报错。
 * <p>
 * 因此统一从 {@code response.context()} 读：三条路径（检索放行 / 检索拒答 / 数字校验拒答）
 * 都会把 context 带给响应。
 */
public final class TurnExtractor {

    private TurnExtractor() {
    }

    /**
     * @param originalRequest {@code MemoryAdvisor} 收到的原始请求（未增强，见类注释 ①）
     * @param envelope        本轮实况权限信封（归属与审计字段的唯一来源）
     * @param response        内层链返回的响应（检索结果与拒答原因的唯一来源，见类注释 ②）
     */
    public static TurnFacts extract(ChatClientRequest originalRequest, SessionEnvelope envelope,
                                    ChatClientResponse response) {
        String reason = refusalReason(response);
        return new TurnFacts(
                envelope.sessionId(),
                envelope.userId(),
                RetrievalAdvisor.bizDate(originalRequest),
                envelope.authVersion(),
                userMessage(originalRequest),
                Texts.answerText(response),
                reason == null ? AnswerDecision.PROCEED : AnswerDecision.REFUSED,
                reason,
                CitedChunkRef.fromDocuments(retrievedDocs(response)));
    }

    /**
     * 拒答原因。两种拒答源写的是<b>同一个</b> context 键：
     * {@code RetrievalAdvisor} 的四种（NO_HIT / PERMISSION_FILTERED / TEMPORAL_FILTERED /
     * KB_UNAVAILABLE）与 {@code NumericGuardrailAdvisor} 的 UNVERIFIED_NUMBER，
     * 所以这里一处读取就覆盖了全部拒答，不必逐个 advisor 判断。
     */
    private static String refusalReason(ChatClientResponse response) {
        Object raw = contextValue(response, RetrievalAdvisor.CONTEXT_REFUSAL_REASON);
        if (raw == null) {
            return null;
        }
        String reason = String.valueOf(raw);
        return reason.isBlank() ? null : reason;
    }

    private static List<Document> retrievedDocs(ChatClientResponse response) {
        Object raw = contextValue(response, RetrievalAdvisor.CONTEXT_RETRIEVED_DOCS);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Document.class::isInstance).map(Document.class::cast).toList();
    }

    private static String userMessage(ChatClientRequest request) {
        if (request == null || request.prompt() == null) {
            return null;
        }
        return request.prompt().getUserMessage().getText();
    }

    private static Object contextValue(ChatClientResponse response, String key) {
        if (response == null || response.context() == null) {
            return null;
        }
        return response.context().get(key);
    }
}
