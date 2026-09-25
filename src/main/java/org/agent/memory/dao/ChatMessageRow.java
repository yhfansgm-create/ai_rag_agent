package org.agent.memory.dao;

import org.agent.memory.model.AnswerDecision;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * chat_message 行模型（MyBatis 结果映射用）。
 * <p>
 * citedChunkRefs 对应表里 cited_chunk_refs 列（JSON 文本），由 Service 层负责
 * 序列化/反序列化 —— 与 {@code SessionRow.accessScopeJson} 同样的处理方式。
 * <p>
 * createdAt 只用于读取（插入时由数据库 {@code DEFAULT CURRENT_TIMESTAMP} 填），
 * 所以 {@link ChatMessageDao#insertTurn} 的列清单里没有它。
 * <p>
 * 两个权限字段职责不同，别混用：
 * <ul>
 *   <li>{@code clearanceLevel} —— 该轮内容可能反映的最高密级，<b>读取判定用</b>。
 *       插入时由 {@code MemoryWriteGuard} 算好带进来</li>
 *   <li>{@code permAuthVersion} —— 该轮写入时的权限版本，<b>仅审计</b>，不参与读取过滤</li>
 * </ul>
 */
public record ChatMessageRow(
        String sessionId,
        String userId,
        int turnNo,
        String role,
        String userMessage,
        String answerText,
        String citedChunkRefs,
        AnswerDecision answerDecision,
        String refusalReason,
        LocalDate bizDate,
        int clearanceLevel,
        long permAuthVersion,
        LocalDateTime createdAt,
        LocalDateTime expiresAt
) {
}
