package org.agent.memory.model;

import java.util.List;

/**
 * 一个历史轮次（读取侧模型，用于注入 prompt）。
 * <p>
 * 刻意<b>不依赖 Spring AI</b> —— 将来做审计/回看接口时可以直接复用，不必把模型层的类型拖进来。
 * 与 Spring AI 消息的转换只在 {@code HistoryMessageMapper} 一个地方发生。
 * <p>
 * 文本是<b>完整</b>的，不在这里截断：截断是"注入侧"的事（见 {@code HistoryMessageMapper}）。
 * 若在读取时就截断，将来做回看/审计接口会拿到残缺内容，而那个接口本该看到原文。
 *
 * @param turnNo      会话内轮号
 * @param userMessage 用户原话（完整）
 * @param answerText  当时的最终答案（完整）
 * @param citedRefs   当时的引文指针；将来做"按当前权限重取原文"时用它
 */
public record ChatTurn(
        int turnNo,
        String userMessage,
        String answerText,
        List<CitedChunkRef> citedRefs
) {
}
