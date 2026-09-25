package org.agent.memory.dao;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * chat_message 的 MyBatis 注解式 DAO（无 XML，与 {@code SessionDao} 同风格）。
 * <p>
 * 列名一律显式 {@code AS camelCase} —— 本项目<b>没有</b>开下划线自动映射。
 * <p>
 * <b>本 DAO 不提供任何按权限字段过滤的查询</b>：{@code perm_auth_version} 只用于审计，
 * 绝不参与读取判定（RAG-07 §三 D3.3 / §A.1 的列注释）。所以这里连"按权限版本筛"的参数都没有，
 * 从接口形状上就堵死了误用。
 */
@Mapper
public interface ChatMessageDao {

    /**
     * 会话内已有的最大轮号；空会话返回 0，下一轮取返回值 + 1。
     * <p>
     * 并发代价：同一 session 并发两次对话可能算出同一个 turn_no，第二条会被
     * {@code uk_session_turn} 拒掉 → {@code DuplicateKeyException} → 被 MemoryAdvisor
     * 的失败隔离吞掉，那一轮不进历史。阶段 0 接受这个代价（会话有配额熔断，并发很罕见），
     * 修法是阶段 3b 的幂等键。
     */
    @Select("SELECT COALESCE(MAX(turn_no), 0) FROM chat_message WHERE session_id = #{sessionId}")
    int selectMaxTurnNo(@Param("sessionId") String sessionId);

    /**
     * 取最近若干行（一轮 = 两行，所以调用方传 {@code windowTurns * 2}）。
     * <p>
     * 排序是两列，各有用途：
     * <ul>
     *   <li>{@code turn_no DESC} —— 最近的在最前，{@code LIMIT} 才取得到"最近 N 轮"</li>
     *   <li>{@code id ASC} —— 同一轮内 user 行先插（id 更小），取回时天然是 user 在 assistant 之前</li>
     * </ul>
     * ⚠️ 调用方<b>不要</b>对结果整体 {@code reverse()}：那会把轮内顺序也翻掉。
     * 正确做法是先按 turn_no 分组、再反转<b>轮序</b>（见 {@code ChatMemoryService}）。
     */
    @Select("""
            SELECT session_id        AS sessionId,
                   user_id           AS userId,
                   turn_no           AS turnNo,
                   role              AS role,
                   user_message      AS userMessage,
                   answer_text       AS answerText,
                   cited_chunk_refs  AS citedChunkRefs,
                   answer_decision   AS answerDecision,
                   refusal_reason    AS refusalReason,
                   biz_date          AS bizDate,
                   perm_auth_version AS permAuthVersion,
                   created_at        AS createdAt,
                   expires_at        AS expiresAt
            FROM chat_message
            WHERE session_id = #{sessionId}
            ORDER BY turn_no DESC, id ASC
            LIMIT #{rowLimit}
            """)
    List<ChatMessageRow> selectRecentTurns(@Param("sessionId") String sessionId,
                                           @Param("rowLimit") int rowLimit);

    /**
     * 一轮两行<b>一条语句</b>写入。
     * <p>
     * 为什么不是两次单行 insert：InnoDB 单语句是原子的，所以不会出现"user 行写了、
     * assistant 行没写"的半轮。拆成两条就必须要 {@code @Transactional} 兜，否则半轮是可能的。
     * <p>
     * 不需要主键回填：与会话表一致，物理主键 {@code id} 不进 record，
     * 业务主键是 {@code session_id + turn_no}。
     */
    @Insert("""
            <script>
            INSERT INTO chat_message
                (session_id, user_id, turn_no, role, user_message, answer_text,
                 cited_chunk_refs, answer_decision, refusal_reason, biz_date, perm_auth_version, expires_at)
            VALUES
            <foreach collection="rows" item="r" separator=",">
                (#{r.sessionId}, #{r.userId}, #{r.turnNo}, #{r.role}, #{r.userMessage}, #{r.answerText},
                 #{r.citedChunkRefs}, #{r.answerDecision}, #{r.refusalReason}, #{r.bizDate},
                 #{r.permAuthVersion}, #{r.expiresAt})
            </foreach>
            </script>
            """)
    int insertTurn(@Param("rows") List<ChatMessageRow> rows);
}
