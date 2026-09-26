package org.agent.memory.dao;

import org.agent.memory.model.FactScope;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code user_memory} 的 MyBatis 注解式 DAO（无 XML，与 {@code ChatMessageDao} 同风格）。
 * <p>
 * 列名一律显式 {@code AS camelCase} —— 本项目<b>没有</b>开下划线自动映射。
 *
 * <h2>⚠️ 本 DAO 的接口形状就是一条安全约束</h2>
 * <b>不提供任何"只按 status 筛"的通用查询</b>，也不提供"按任意条件拼"的查询。
 * 理由：{@code status} <b>不</b>表达"是否为当前有效"（见 {@code MemoryStatus} 类注释），
 * 被取代的历史行仍是 {@code ACTIVE}。所以一个 {@code WHERE status='ACTIVE'} 的通用查询
 * 会把被取代的旧值一起捞回来，让"改口"失效。本 DAO 里<b>每一处</b>出现 {@code status}
 * 的语句都同时带着 {@code valid_to IS NULL}。
 * <p>
 * 这是 {@code ChatMessageDao}「不提供按权限字段过滤的查询 —— 从接口形状上就堵死了误用」
 * 的同一个手法：与其在注释里写"别这么用"，不如让那个方法不存在。
 *
 * <h2>⚠️ 读侧把密级条件<b>下推进 SQL</b> —— 这与 L1 刻意相反，是本项目唯一一处这样的偏离</h2>
 * L1 把密级判定放在 {@code LIMIT} <b>之后</b>，因为 L1 的 {@code LIMIT N} 语义是
 * "最近 N 轮"，塞进 {@code WHERE} 会让降级用户<b>跳过最近几轮去捞更早的</b>
 * —— 那等于绕过了"最近"这条安全边界（见 {@code ChatMemoryService}）。
 * <p>
 * <b>这个推理在 L2 上不成立</b>：
 * <ul>
 *   <li>L2 的 {@code LIMIT} 是"该用户最多返回 N 条事实"的<b>展示预算</b>，
 *       没有"最近 vs 更早"这条安全边界</li>
 *   <li>{@code uk_owner_active} 保证同一 {@code (owner, scope, fact_key)} 至多一条有效行
 *       ⇒ <b>不存在"同一事实的更早版本"可回捞</b>，所以下推不会让谁多看到东西</li>
 * </ul>
 * 于是下推的收益（{@code LIMIT N} 真的返回 N 条<b>可读</b>事实，而不是返回 10 条被滤掉 8 条）
 * 大于风险。<b>但 Java 侧仍然再复核一遍</b>（{@code SemanticMemoryService}）——
 * 那才是"一条权限策略一个出口"在 L2 的落点，也让单测能喂进"不该返回的行"并断言被丢掉。
 */
@Mapper
public interface UserMemoryDao {

    /**
     * 召回本人的有效事实（{@code scope='USER'}）。
     * <p>
     * ⚠️ 三个查询条件<b>全部来自本轮实况信封</b>，没有一个来自行里的快照列：
     * {@code ownerId} 与 {@code envelopeClearance} 都由调用方从信封取。
     * 特别是 {@code clearance_level <= envelopeClearance} —— 比的是"<b>本轮</b>用户能看多高"，
     * 不是"写入时用户能看多高"（{@code perm_auth_version} 只留痕，不参与判定）。
     * <p>
     * {@code scope='USER'} 写死在 SQL 里而不是做参数：这个方法<b>只能</b>查本人范围，
     * 传不进别的 scope（接口形状防误用）。
     * <p>
     * {@code now} 由调用方传进来（不用 {@code NOW()}）—— 过期判定要能用固定时钟测。
     *
     * @param now 判定过期用的"当下"（由调用方提供，便于测试）
     */
    @Select("""
            SELECT fact_uid          AS factUid,
                   owner_id          AS ownerId,
                   scope             AS scope,
                   fact_key          AS factKey,
                   fact_value        AS factValue,
                   clearance_level   AS clearanceLevel,
                   org_path          AS orgPath,
                   source_session_id AS sourceSessionId,
                   source_turn_no    AS sourceTurnNo,
                   ref_doc_id        AS refDocId,
                   valid_from        AS validFrom,
                   valid_to          AS validTo,
                   superseded_by     AS supersededBy,
                   expires_at        AS expiresAt,
                   status            AS status,
                   last_hit_at       AS lastHitAt,
                   perm_auth_version AS permAuthVersion,
                   created_at        AS createdAt
            FROM user_memory
            WHERE owner_id = #{ownerId}
              AND scope = 'USER'
              AND valid_to IS NULL
              AND status = 'ACTIVE'
              AND clearance_level <= #{envelopeClearance}
              AND (expires_at IS NULL OR expires_at > #{now})
            ORDER BY COALESCE(last_hit_at, created_at) DESC
            LIMIT #{limit}
            """)
    List<UserMemoryRow> selectUserFacts(@Param("ownerId") String ownerId,
                                        @Param("envelopeClearance") int envelopeClearance,
                                        @Param("now") LocalDateTime now,
                                        @Param("limit") int limit);

    /**
     * 召回同组织可见的事实（{@code scope='DEPT'}）。
     * <p>
     * {@code org_path = ?} <b>精确匹配</b>，不做前缀匹配 —— 与
     * {@code SessionAuthService.buildAccessFilter} 的 {@code org_codes in [...]} 同口径。
     * 调用方传进来的 {@code orgPath} 若为 null/空白，本查询<b>恒不匹配任何行</b>
     * （SQL 里 {@code = NULL} 永不为真），这正是想要的 fail-closed 行为。
     * <p>
     * ⚠️ 本阶段<b>没有</b> DEPT 的自动写入路径（RAG-07 把部门级晋升写成"需人工确认"，
     * 与"只做两档"的决策冲突，归阶段 4）。这个方法存在是为了让读分支被实现并<b>被集成测试
     * 证明可读</b> —— 否则权限分析不完整，也没法证明"精确匹配"这条口径是对的。
     * <p>
     * ⚠️ DEPT 行<b>不</b>按 {@code owner_id} 过滤（那就是 USER 语义了）。
     * 注意它也没有把 {@code owner_id} 传进来 —— 又一次接口形状防误用。
     */
    @Select("""
            SELECT fact_uid          AS factUid,
                   owner_id          AS ownerId,
                   scope             AS scope,
                   fact_key          AS factKey,
                   fact_value        AS factValue,
                   clearance_level   AS clearanceLevel,
                   org_path          AS orgPath,
                   source_session_id AS sourceSessionId,
                   source_turn_no    AS sourceTurnNo,
                   ref_doc_id        AS refDocId,
                   valid_from        AS validFrom,
                   valid_to          AS validTo,
                   superseded_by     AS supersededBy,
                   expires_at        AS expiresAt,
                   status            AS status,
                   last_hit_at       AS lastHitAt,
                   perm_auth_version AS permAuthVersion,
                   created_at        AS createdAt
            FROM user_memory
            WHERE scope = 'DEPT'
              AND org_path = #{orgPath}
              AND valid_to IS NULL
              AND status = 'ACTIVE'
              AND clearance_level <= #{envelopeClearance}
              AND (expires_at IS NULL OR expires_at > #{now})
            ORDER BY COALESCE(last_hit_at, created_at) DESC
            LIMIT #{limit}
            """)
    List<UserMemoryRow> selectDeptFacts(@Param("orgPath") String orgPath,
                                        @Param("envelopeClearance") int envelopeClearance,
                                        @Param("now") LocalDateTime now,
                                        @Param("limit") int limit);

    /**
     * 按 {@code (owner, scope, fact_key)} 取<b>当前有效</b>的那一条（没有则返回 null）。
     * <p>
     * 这是写入路径的判据方法：拿到旧行才能决定"新增 / 同值续期 / 取代"三种形态，
     * 且取代时旧行就是审计 {@code before_json} 的来源。
     * <p>
     * <b>带 {@code FOR UPDATE}</b>：写路径必须在事务内调用（{@code MemoryFactWriter}），
     * 靠行锁把"读旧行 → 决定 → 写"这段串行化。在事务外调用不会报错，只是没有锁 ——
     * 那样就等于把并发不变量让给了 {@code uk_owner_active} 兜底（兜得住，但会变成
     * DuplicateKeyException 而不是干净的取代）。
     * <p>
     * 不加 {@code LIMIT 1}：唯一键已经保证至多一条。万一真有两条，
     * <b>宁可让 MyBatis 抛异常也不静默取一条</b> —— 那说明不变量已经破了，
     * 静默取一条会把一个数据损坏问题变成一个永远查不出来的偶发 bug。
     */
    @Select("""
            SELECT fact_uid          AS factUid,
                   owner_id          AS ownerId,
                   scope             AS scope,
                   fact_key          AS factKey,
                   fact_value        AS factValue,
                   clearance_level   AS clearanceLevel,
                   org_path          AS orgPath,
                   source_session_id AS sourceSessionId,
                   source_turn_no    AS sourceTurnNo,
                   ref_doc_id        AS refDocId,
                   valid_from        AS validFrom,
                   valid_to          AS validTo,
                   superseded_by     AS supersededBy,
                   expires_at        AS expiresAt,
                   status            AS status,
                   last_hit_at       AS lastHitAt,
                   perm_auth_version AS permAuthVersion,
                   created_at        AS createdAt
            FROM user_memory
            WHERE owner_id = #{ownerId}
              AND scope = #{scope}
              AND fact_key = #{factKey}
              AND valid_to IS NULL
            FOR UPDATE
            """)
    UserMemoryRow selectActiveByKey(@Param("ownerId") String ownerId,
                                    @Param("scope") FactScope scope,
                                    @Param("factKey") String factKey);

    /**
     * 该用户当前有效的事实条数（写配额用，走 {@code idx_owner_status}）。
     * <p>
     * ⚠️ <b>必须带 {@code valid_to IS NULL}</b>：被取代的历史行<b>不占配额</b>。
     * 漏了它，用户会在"200 条满了 → 改口也改不了"的地方卡死 ——
     * 因为 supersede 明明应该算"不新增"，却被历史行挤掉了配额。
     * <p>
     * 只数 {@code scope='USER'}：配额是"每人多少条事实"的概念，
     * 本阶段也不产生别的 scope。
     */
    @Select("""
            SELECT COUNT(*)
            FROM user_memory
            WHERE owner_id = #{ownerId}
              AND scope = 'USER'
              AND valid_to IS NULL
              AND status = 'ACTIVE'
            """)
    int countActive(@Param("ownerId") String ownerId);

    /**
     * 插入一条新的有效记忆。
     * <p>
     * 不需要主键回填：与会话表 / {@code chat_message} 一致，物理主键 {@code id} 不进 record，
     * 业务主键是 {@code fact_uid}。
     * <p>
     * 列清单里<b>没有</b> {@code valid_to} / {@code superseded_by} / {@code last_hit_at} /
     * {@code created_at} / {@code updated_at} / {@code active_key}：
     * 前两个是"被取代时才写"的列（新行插入时必为 null），{@code last_hit_at} 由命中续期写，
     * 三个时间列与生成列由 DB 负责。
     * <p>
     * {@code valid_from} 与 {@code expires_at} <b>由 Java 传</b>（不用 DB 默认值）：
     * 一是 {@code expires_at = valid_from + FactKey.ttlDays} 必须同步算出来，
     * 二是时间要能用固定时钟测。
     */
    @Insert("""
            INSERT INTO user_memory
                (fact_uid, owner_id, scope, fact_key, fact_value, clearance_level, org_path,
                 source_session_id, source_turn_no, ref_doc_id,
                 valid_from, expires_at, status, perm_auth_version)
            VALUES
                (#{factUid}, #{ownerId}, #{scope}, #{factKey}, #{factValue}, #{clearanceLevel}, #{orgPath},
                 #{sourceSessionId}, #{sourceTurnNo}, #{refDocId},
                 #{validFrom}, #{expiresAt}, #{status}, #{permAuthVersion})
            """)
    int insert(UserMemoryRow row);

    /**
     * 取代：给旧行盖上终点章。旧行<b>不删</b>（双时态，历史可回溯），
     * 只是 {@code valid_to} 非空之后不再被任何召回查询捞到。
     * <p>
     * {@code WHERE ... AND valid_to IS NULL} 是<b>乐观并发保护</b>：
     * 若这行已经被别人取代过，本语句影响 0 行 —— 调用方据此判断"我拿到的那份旧行已经过期了"，
     * 而不是盲目覆盖别人的取代结果（那会把 {@code superseded_by} 指向一个不存在于链上的键）。
     * <p>
     * ⚠️ {@code status} <b>保持 ACTIVE 不动</b> —— 被取代是"不再当前"，不是"坏"。
     * 见 {@code MemoryStatus} 类注释（给 STALE 塞第二个含义会破坏阶段 4 的巡检①）。
     *
     * @return 影响行数；0 表示该行已被并发取代（或本来就不存在）
     */
    @Update("""
            UPDATE user_memory
               SET valid_to = #{validTo},
                   superseded_by = #{supersededBy}
             WHERE fact_uid = #{factUid}
               AND valid_to IS NULL
            """)
    int supersede(@Param("factUid") String factUid,
                  @Param("validTo") LocalDateTime validTo,
                  @Param("supersededBy") String supersededBy);

    /**
     * 同值重申的续期：不新增行、不改来源，只把保质期往后推、并把命中也记上。
     * <p>
     * 为什么"重申"值钱：用户<b>在新会话里又说了一遍同一件事</b>，是"它现在仍然为真"的
     * <b>最强证据</b> —— 而那正是 TTL 需要的信号。所以这不算"重复写入"，
     * 算一次有价值的状态更新。
     * <p>
     * ⚠️ 同时写 {@code last_hit_at}：排序用 {@code COALESCE(last_hit_at, created_at)}，
     * 续期后这条会被排到前面 —— 刚被重申的事实本来就该更靠前。
     *
     * @return 影响行数；0 表示该行已被并发取代
     */
    @Update("""
            UPDATE user_memory
               SET expires_at = #{expiresAt},
                   last_hit_at = #{now}
             WHERE fact_uid = #{factUid}
               AND valid_to IS NULL
            """)
    int refresh(@Param("factUid") String factUid,
                @Param("now") LocalDateTime now,
                @Param("expiresAt") LocalDateTime expiresAt);

    /**
     * 批量把命中时间推到现在 —— <b>只更新真正注入 prompt 的那些 factUid</b>。
     * <p>
     * ⚠️ miss 就是 miss：被 Java 侧复核丢掉的行<b>不算命中</b>。否则"降级用户看不到的事实"
     * 会被标成"最近常看"，污染阶段 4 巡检③的 LRU 判断。
     * <p>
     * 一条语句批量更新（毫秒级），且在<b>调用线程上同步执行</b>，不扔进蒸馏那个 Executor：
     * ① 单测能直接 {@code verify(dao).touchHit(...)}，不需要 await；
     * ② 那边可能正卡着一个 2 秒的 LLM 调用，会让"命中统计丢了"与"记忆没写上"两个
     *    完全不同的问题纠缠在一起，排查时看不出是谁。
     * <p>
     * 调用方须保证 {@code factUids} 非空（空集合会拼出 {@code IN ()} 语法错误）。
     *
     * @return 影响行数
     */
    @Update("""
            <script>
            UPDATE user_memory
               SET last_hit_at = #{now}
             WHERE fact_uid IN
             <foreach collection="factUids" item="uid" open="(" separator="," close=")">#{uid}</foreach>
            </script>
            """)
    int touchHit(@Param("factUids") List<String> factUids,
                 @Param("now") LocalDateTime now);

    // -------------------------------------------------------------------------
    // 阶段 4 的挂载点（本阶段刻意不写 —— 没有消费方之前是投机设计）：
    //   · selectOwnedBy(ownerId, ...)  —— 可查接口（GET /memory）的分页查询
    //   · markUserDeleted(factUid)     —— 可删接口，走 status='USER_DELETED' + USER_DELETE 日志
    //   · selectStaleCandidates(...)   —— 巡检① 制度废止
    // 刻意<b>没有</b>的（永远不该有）：任何只按 status 筛的通用查询，见类注释。
    // -------------------------------------------------------------------------
}
