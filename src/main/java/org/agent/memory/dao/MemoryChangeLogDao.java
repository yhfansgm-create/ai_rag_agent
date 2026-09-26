package org.agent.memory.dao;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * {@code memory_change_log} 的 MyBatis 注解式 DAO（无 XML，与 {@code ChatMessageDao} 同风格）。
 * <p>
 * 本 DAO <b>只有"追加"与"读回"两件事</b>，刻意<b>不提供</b> UPDATE / DELETE ——
 * 审计表是 append-only 的：一条已经上链的记录如果可被改写，链本身就没有意义了。
 * 连"删一条"这个能力都不给，是让"不改审计"这句话在<b>接口形状</b>上成立
 * （同 {@code ChatMessageDao}「不提供按权限字段过滤的查询」的手法）。
 * <p>
 * 列名一律显式 {@code AS camelCase} —— 本项目<b>没有</b>开下划线自动映射。
 */
@Mapper
public interface MemoryChangeLogDao {

    /**
     * 追加一条审计记录。
     * <p>
     * ⚠️ {@code createdAt} <b>必须由 Java 传</b>（不是靠 DB 的 {@code DEFAULT CURRENT_TIMESTAMP}）：
     * 哈希算的就是这个值。让 DB 填时间的话，时间戳就在哈希的保护范围之外 ——
     * 攻击者可以随意改"何时记录的"而不破坏链，而"何时记录的"在审计里恰恰是关键的。
     * <p>
     * ⚠️ 列清单里<b>没有</b> {@code id}：物理主键不进 record（house style），
     * 链的行序由显式的 {@code prev_hash} 给出，全序 = {@code id ASC}。
     */
    @Insert("""
            INSERT INTO memory_change_log
                (entity_type, fact_uid, action, before_json, after_json,
                 actor_type, actor_id, reason, prev_hash, hash, created_at)
            VALUES
                (#{entityType}, #{factUid}, #{action}, #{beforeJson}, #{afterJson},
                 #{actorType}, #{actorId}, #{reason}, #{prevHash}, #{hash}, #{createdAt})
            """)
    int insert(MemoryChangeLogRow row);

    /**
     * 取链尾的 hash 作为下一条的 {@code prev_hash}（空链返回 null）。
     * <p>
     * <b>必须带 {@code FOR UPDATE}</b>：这是链的串行化点。两个并发事务若都读到同一个链尾，
     * 各自追加一条、各自指向同一个 {@code prev_hash} —— 链就<b>分叉</b>了，
     * 那正是哈希链最典型的失效形态。行锁把"读链尾 → 算 hash → 插入"这段串行化。
     * <p>
     * 走主键逆序取一条（{@code ORDER BY id DESC LIMIT 1}），代价极小。
     * <p>
     * ⚠️ <b>诚实边界</b>：这把锁只在<b>同一个数据库实例内</b>生效。有 DB 写权限的攻击者
     * 可以整条重写 —— 所以本机制是 <b>tamper-evident（可发现篡改）</b>，
     * <b>不是 tamper-proof（防篡改）</b>。见 {@code sql/memory_change_log.sql} 文件头。
     */
    @Select("""
            SELECT hash
            FROM memory_change_log
            ORDER BY id DESC
            LIMIT 1
            FOR UPDATE
            """)
    String selectHeadHashForUpdate();

    /**
     * 读回整条链（{@code id ASC}），供 {@code MemoryChangeLogVerifier} 校验。
     * <p>
     * 为什么"读回"这件事必须存在：<b>没有校验器的哈希链是装饰品</b>。
     * "可发现篡改"这个说法必须由一段能跑的代码定义，否则它只是一句承诺 ——
     * 与 {@code FactGrounding} 必须有"能真的失败"的测试是同一个道理。
     * <p>
     * 本阶段它只被测试与阶段 4 的审计接口使用。全序取全表（本阶段数据量小）；
     * 数据量上来后应改成分页 + 从检查点起校验，那时再谈。
     */
    @Select("""
            SELECT entity_type AS entityType,
                   fact_uid    AS factUid,
                   action      AS action,
                   before_json AS beforeJson,
                   after_json  AS afterJson,
                   actor_type  AS actorType,
                   actor_id    AS actorId,
                   reason      AS reason,
                   prev_hash   AS prevHash,
                   hash        AS hash,
                   created_at  AS createdAt
            FROM memory_change_log
            ORDER BY id ASC
            """)
    List<MemoryChangeLogRow> selectChain();
}
