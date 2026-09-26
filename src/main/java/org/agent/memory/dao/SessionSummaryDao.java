package org.agent.memory.dao;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * {@code session_summary} 的 MyBatis 注解式 DAO（无 XML，与 {@code ChatMessageDao} 同风格）。
 * <p>
 * 列名一律显式 {@code AS camelCase} —— 本项目<b>没有</b>开下划线自动映射。
 *
 * <h2>⚠️ 本 DAO 只有三个方法，这是刻意的</h2>
 * 摘要的读取入口只有"<b>取本会话当前有效的那一版</b>"这一个语义，所以这里
 * <b>不提供</b>：按 {@code summary_uid} 查历史的、按时间范围查的、按 {@code user_id}
 * 查别人会话的、绕过 {@code valid_to} 查历史版本的。将来阶段 4 的审计接口要读历史版本时
 * 再加，且应当在<b>审计</b>的语义下加（那时它需要的是"某个时间点模型看到了什么"，
 * 与本 DAO 的"现在该注入什么"是两件事）。
 * <p>
 * 与 {@code MemoryStatus} 那套不同的是：本表<b>没有</b> {@code status} 列 ——
 * 摘要的生命周期只有"当前有效 / 已被取代"两态，由 {@code valid_to} 完全表达。
 * 少一个字段就少一处两个真相源漂移的机会。
 */
@Mapper
public interface SessionSummaryDao {

    /**
     * 取本会话<b>当前有效</b>的那一版摘要（没有则返回 null）。
     * <p>
     * ⚠️ <b>不加 {@code LIMIT 1}</b>：唯一键 {@code uk_session_active}（建在生成列
     * {@code active_session_id} 上）已经保证至多一条。万一真出现两条，说明不变量已破 ——
     * <b>宁可让 MyBatis 抛 {@code TooManyResultsException} 也不要静默取一条</b>。
     * 静默取一条会把一个数据损坏问题变成一个永远查不出来的偶发 bug：
     * 摘要会随机地"时有时无"，而没有任何日志指向原因。
     * <p>
     * 抛出去之后由 {@code MemoryAdvisor} 的 fail-open 边界接住（记忆降级、对话照常）——
     * 这正是"记忆坏了只是体验差"的落点。
     * <p>
     * 过期行不返回：{@code expires_at} 与 {@code chat_message} 同一个 retention-days 口径。
     * {@code now} 由调用方传进来（不用 {@code NOW()}）—— 过期判定要能用固定时钟测。
     */
    @Select("""
            SELECT summary_uid           AS summaryUid,
                   session_id            AS sessionId,
                   user_id               AS userId,
                   covers_from_turn_no   AS coversFromTurnNo,
                   covers_through_turn_no AS coversThroughTurnNo,
                   clearance_level       AS clearanceLevel,
                   summary_text          AS summaryText,
                   source_turn_count     AS sourceTurnCount,
                   valid_from            AS validFrom,
                   valid_to              AS validTo,
                   superseded_by         AS supersededBy,
                   perm_auth_version     AS permAuthVersion,
                   expires_at            AS expiresAt,
                   created_at            AS createdAt
            FROM session_summary
            WHERE session_id = #{sessionId}
              AND valid_to IS NULL
              AND (expires_at IS NULL OR expires_at > #{now})
            """)
    SessionSummaryRow selectActive(@Param("sessionId") String sessionId,
                                   @Param("now") LocalDateTime now);

    /**
     * 插入一版新摘要。
     * <p>
     * 不需要主键回填：物理主键 {@code id} 不进 record，业务主键是 {@code summaryUid}
     * （与 {@code chat_message} / {@code user_memory} 一致）。
     * <p>
     * ⚠️ {@code clearanceLevel} 必须由 Java 算好带进来 = <b>被摘要轮次的最高密级</b>，
     * <b>模型无权输出</b>。漏写会落到 DB 的 {@code DEFAULT 5}（最高密级，fail-closed）——
     * 那是安全的失败方向，但也意味着这条摘要对谁都不可读，是 bug 不是漏洞。
     * <p>
     * {@code valid_from} 与 {@code expires_at} 由 Java 传（理由同 {@code UserMemoryDao.insert}：
     * 要能用固定时钟测）。
     */
    @Insert("""
            INSERT INTO session_summary
                (summary_uid, session_id, user_id,
                 covers_from_turn_no, covers_through_turn_no, clearance_level,
                 summary_text, source_turn_count,
                 valid_from, expires_at, perm_auth_version)
            VALUES
                (#{summaryUid}, #{sessionId}, #{userId},
                 #{coversFromTurnNo}, #{coversThroughTurnNo}, #{clearanceLevel},
                 #{summaryText}, #{sourceTurnCount},
                 #{validFrom}, #{expiresAt}, #{permAuthVersion})
            """)
    int insert(SessionSummaryRow row);

    /**
     * 取代旧版本：旧版<b>不删</b>，标 {@code valid_to} + {@code supersededBy}。
     * <p>
     * 为什么不原地 UPDATE 覆盖：{@code L0}「不落库」的代价原文是
     * "<b>无法事后复现『当时模型看到了什么』</b>"，而摘要正是<b>被注入模型的东西</b> ——
     * 版本化的摘要就是这条缺口在记忆层的补法，直接服务"审计"这根评价轴。
     * <p>
     * {@code WHERE ... AND valid_to IS NULL} 是乐观并发保护，含义同
     * {@code UserMemoryDao.supersede}。
     *
     * @return 影响行数；0 表示该版本已被并发取代
     */
    @Update("""
            UPDATE session_summary
               SET valid_to = #{validTo},
                   superseded_by = #{supersededBy}
             WHERE summary_uid = #{summaryUid}
               AND valid_to IS NULL
            """)
    int supersede(@Param("summaryUid") String summaryUid,
                  @Param("validTo") LocalDateTime validTo,
                  @Param("supersededBy") String supersededBy);

    // -------------------------------------------------------------------------
    // 阶段 4 的挂载点（本阶段刻意不写）：
    //   · selectVersions(sessionId)  —— 审计接口要"某时间点模型看到了什么"时按 valid_from/
    //     valid_to 反查历史版本。它属于审计语义，不属于本 DAO 的"现在该注入什么"。
    //   · 摘要坏数据巡检（摘要覆盖区间与 L1 窗口出现缝隙 —— 理论不可能，但要能查）
    // -------------------------------------------------------------------------
}
