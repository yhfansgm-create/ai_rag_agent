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
        long permAuthVersion,
        LocalDateTime createdAt,
        LocalDateTime expiresAt
) {
}
