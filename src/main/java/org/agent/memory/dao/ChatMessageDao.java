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
     * {@code chat_message} 的完整列清单（含 {@code SELECT} 关键字，显式 {@code AS camelCase}）。
     * <p>
     * ⚠️ 抽成常量是因为<b>下面有两个查询都要它</b>。本项目没开下划线自动映射，
     * 所以别名写错的下场是<b>那一列静默变成 null</b>（不是报错）——
     * 而"摘要里没有答案文本"这种症状没人会联想到是别名的问题。
     * 一份列清单、两个消费方，就没有"改了这边忘了那边"这种状态。
     * <p>
     * ⚠️ {@code SELECT} 也放在这个常量里，不是为了好看：<b>文本块会剥掉每行行尾的空白</b>，
     * 所以 {@code """SELECT """} 里那个用来分隔的空格会消失，拼出来是
     * {@code SELECTsession_id} —— 一个语法错误，而且只在真连数据库时才炸
     * （编译期看不出来，纯 Mockito 的单测也看不出来）。
     * 把关键字和列清单放进同一个块里，这个坑就不存在了。
     * <p>
     * （注解的值必须是编译期常量，文本块常量做字符串拼接满足这一条。）
     */
    String SELECT_COLUMNS = """
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
                   clearance_level   AS clearanceLevel,
                   perm_auth_version AS permAuthVersion,
                   created_at        AS createdAt,
                   expires_at        AS expiresAt
            """;

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
    @Select(SELECT_COLUMNS + """
            FROM chat_message
            WHERE session_id = #{sessionId}
            ORDER BY turn_no DESC, id ASC
            LIMIT #{rowLimit}
            """)
    List<ChatMessageRow> selectRecentTurns(@Param("sessionId") String sessionId,
                                           @Param("rowLimit") int rowLimit);

    /**
     * 取某个<b>轮号区间</b>内的全部行，按轮号<b>升序</b>返回（轮内仍是 {@code id ASC}，
     * 即 user 在 assistant 之前）。
     *
     * <h2>它是给会话压缩用的，不是 L1 读窗口的第二个入口</h2>
     * L1 的读窗口走 {@link #selectRecentTurns} + Java 侧过滤（拒答轮、密级、残轮），
     * <b>口径不要混用</b>：压缩要折叠的是"已经滑出窗口的那几轮"，
     * 而它的过滤规则与"进不进 prompt"本来就不是一回事 ——
     * 拒答轮<b>要</b>进压缩（"用户问了 XX 未获答复"是下一轮唯一的定位线索），
     * 只是不给它答案文案；密级也<b>不</b>在这里滤（一份摘要的密级取被折叠轮次的 max，
     * 由读取侧对<b>整份</b>摘要做判定）。把两套规则合并成一个方法，
     * 早晚会出现"为了让摘要能读到，把 L1 的过滤放松了"这种事。
     *
     * <h2>⚠️ 刻意不做的事</h2>
     * <ul>
     *   <li><b>不滤 {@code expires_at}</b> —— 与 {@link #selectRecentTurns} 同口径。
     *       若这里滤掉过期行，摘要的覆盖区间就会出现"它声称覆盖了、其实没读到"的洞，
     *       而"每一轮要么在窗口里、要么在摘要里"这条完备性正是靠覆盖区间成立的</li>
     *   <li><b>不滤 {@code answer_decision}</b> —— 见上</li>
     *   <li><b>没有 {@code LIMIT}</b> —— 区间由轮号框定，静默截断会让覆盖区间失真。
     *       ⚠️ 所以<b>调用方有义务把区间框住</b>（正常情况下一次只折叠刚滑出的那一轮）；
     *       积压很多轮时要自己决定是分批折叠还是缩小覆盖区间，不能让这里悄悄截断</li>
     * </ul>
     *
     * @param fromTurnNo    区间起点（含）
     * @param throughTurnNo 区间终点（含）。{@code from > through} 时返回空列表
     *                      （{@code BETWEEN} 的语义），调用方不必自己判 ——
     *                      但这意味着"区间为空"和"区间内没有行"返回同样的结果，
     *                      调用方若要区分，得自己看 {@code selectMaxTurnNo}
     */
    @Select(SELECT_COLUMNS + """
            FROM chat_message
            WHERE session_id = #{sessionId}
              AND turn_no BETWEEN #{fromTurnNo} AND #{throughTurnNo}
            ORDER BY turn_no ASC, id ASC
            """)
    List<ChatMessageRow> selectTurnRange(@Param("sessionId") String sessionId,
                                         @Param("fromTurnNo") int fromTurnNo,
                                         @Param("throughTurnNo") int throughTurnNo);

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
                 cited_chunk_refs, answer_decision, refusal_reason, biz_date,
                 clearance_level, perm_auth_version, expires_at)
            VALUES
            <foreach collection="rows" item="r" separator=",">
                (#{r.sessionId}, #{r.userId}, #{r.turnNo}, #{r.role}, #{r.userMessage}, #{r.answerText},
                 #{r.citedChunkRefs}, #{r.answerDecision}, #{r.refusalReason}, #{r.bizDate},
                 #{r.clearanceLevel}, #{r.permAuthVersion}, #{r.expiresAt})
            </foreach>
            </script>
            """)
    int insertTurn(@Param("rows") List<ChatMessageRow> rows);
}
