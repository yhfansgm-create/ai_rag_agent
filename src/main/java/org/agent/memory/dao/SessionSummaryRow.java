package org.agent.memory.dao;

import java.time.LocalDateTime;

/**
 * {@code session_summary} 行模型（MyBatis 结果映射用）。
 *
 * <h2>它与 L1 的关系</h2>
 * 摘要恰好覆盖<b>滑出 L1 窗口的那一段</b>（{@code [coversFromTurnNo, coversThroughTurnNo)}），
 * 与窗口<b>无缝且不重叠</b>。于是每一轮必然落在三者之一：
 * <b>窗口里 / 摘要里 / 被密级过滤掉</b>。不存在"两边都没有"的偶然丢失 ——
 * 这是覆盖区间必须存下来的理由，不是为了展示。
 *
 * <h2>⚠️ 两个必须知道的坑</h2>
 * <ol>
 *   <li>{@code clearanceLevel} = <b>被摘要轮次的最高密级</b>，由 Java 算，<b>模型无权输出</b>。
 *       摘要把高密级内容压缩后长期携带 —— 不继承密级就是一条比 L1 旁路<b>更宽</b>的路
 *       （一份摘要能顶好几轮）。读取时按本轮实况信封判定，<b>整份不过就整份丢弃</b></li>
 *   <li>{@code summaryText} 里<b>不该出现金额/比例/期限</b> —— 由
 *       {@code LlmSummaryWriter} 用现成的 {@code NumericExtractor} 机械校验，
 *       违规打回一次，仍违规就整份丢弃。这是 RAG-07 附录 A1 那条已知偏差的落实：
 *       <b>摘要只负责"找得到"，原文才负责"答得对"</b></li>
 * </ol>
 *
 * <h2>同样没有 {@code id} 组件</h2>
 * 与 {@code ChatMessageRow} / {@link UserMemoryRow} 一致：物理主键不进 record，
 * 业务键是 {@code summaryUid}，{@code supersededBy} 指向后继的它。
 *
 * @param summaryUid          业务键（Java 生成 UUID）
 * @param sessionId           所属会话
 * @param userId              归属人工号（归属复核用，与会话串号兜底同一手法）
 * @param coversFromTurnNo    覆盖起始轮次（含）
 * @param coversThroughTurnNo 已摘要到第几轮（<b>不含</b>；即"下一轮该从哪开始"）
 * @param clearanceLevel      被摘要轮次的最高密级（读取判定用，整份摘要按此过滤）
 * @param summaryText         摘要正文（只含定位信息）
 * @param sourceTurnCount     本次折叠了几轮（审计与"摘要膨胀"观察用）
 * @param validFrom           真值区间起点
 * @param validTo             真值区间终点（null = 当前有效）
 * @param supersededBy        被哪一版新摘要取代
 * @param permAuthVersion     写入时的权限版本（仅审计）
 * @param expiresAt           留存到期（与 {@code chat_message} 同一个 retention-days 口径）
 * @param createdAt           创建时间（只在读路径用）
 */
public record SessionSummaryRow(
        String summaryUid,
        String sessionId,
        String userId,
        int coversFromTurnNo,
        int coversThroughTurnNo,
        int clearanceLevel,
        String summaryText,
        int sourceTurnCount,
        LocalDateTime validFrom,
        LocalDateTime validTo,
        String supersededBy,
        long permAuthVersion,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
) {
}
