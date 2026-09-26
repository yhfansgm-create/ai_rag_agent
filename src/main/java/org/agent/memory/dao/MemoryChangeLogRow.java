package org.agent.memory.dao;

import org.agent.audit.AuditHashChain;
import org.agent.memory.model.MemoryActorType;
import org.agent.memory.model.MemoryChangeAction;
import org.agent.memory.model.MemoryEntityType;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * {@code memory_change_log} 行模型（MyBatis 结果映射用）—— 记忆变更审计。
 *
 * <h2>⚠️ 插入时<b>必须</b>由 Java 提供 {@code createdAt} 与 {@code hash}</h2>
 * 表上有 {@code DEFAULT CURRENT_TIMESTAMP}，但哈希算的是 <b>Java 传进去的那个时间</b>。
 * 若图省事让 DB 填时间，哈希就保护不到时间戳 —— 攻击者可以随意改"何时记录的"
 * 而不破坏链。列上仍留 DEFAULT 只是为了手工插数据方便。
 *
 * <h2>⚠️ {@code beforeJson} / {@code afterJson} 是用来<b>算哈希</b>的，不是给人看的</h2>
 * 哈希前必须先经 {@code AuditHashChain.canonicalJson} 规范化：MySQL 的 JSON 列会
 * <b>重排对象键、抹掉空白</b>，按"写入时那个字符串"算哈希，事后 SELECT 回来重新校验会
 * <b>假失败</b>。（这是 decision 7 里最容易踩的一条，有专门一条集成测试锁死。）
 *
 * <h2>⚠️ {@code GUARD_REJECT} 的 {@code afterJson} 绝不能回写被拒的值</h2>
 * 只放 {@code {factKey, pattern, valueLength}} —— 否则等于把疑似凭证又抄了一遍，
 * <b>审计本身成了泄露面</b>。
 *
 * @param entityType 被变更的实体类型（FACT / SUMMARY）
 * @param factUid    被变更的记忆业务键；<b>{@code GUARD_REJECT} 时为 null</b>
 *                   —— 那次候选从未成为一条记忆，没有键可指；在 NOT NULL 约束下
 *                   只能编一个假 id，那比留空更糟
 * @param action     变更动作
 * @param beforeJson 变更前快照（WRITE 时为 null）
 * @param afterJson  变更后快照
 * @param actorType  发起方（模型自动 / 用户 / 巡检 / 管理员）
 * @param actorId    操作人工号（actorType 为 USER/ADMIN 时）
 * @param reason     变更原因（如 {@code REFRESH_SAME_VALUE} / 具名拒绝理由）
 * @param prevHash   上一条的 hash（null = 链首）
 * @param hash       本条内容的 SHA-256（含 prevHash）
 * @param createdAt  记录时间。<b>哈希用的是这个值，不是 DB 的默认值</b>
 */
public record MemoryChangeLogRow(
        MemoryEntityType entityType,
        String factUid,
        MemoryChangeAction action,
        String beforeJson,
        String afterJson,
        MemoryActorType actorType,
        String actorId,
        String reason,
        String prevHash,
        String hash,
        LocalDateTime createdAt
) {

    /**
     * 本行进入哈希链时的有序字段。
     * <p>
     * ⚠️ <b>顺序是哈希的一部分</b>：这里的排列顺序必须与 {@link AuditHashChain} 类注释里
     * 那个公式、以及 {@code memory_change_log.sql} 头部注释逐项对应。改动顺序（哪怕只是
     * 把两个字段调个个儿）会让<b>全部历史行同时假失败</b> —— 校验器会说链被篡改了，
     * 而实际上只是有人重排了代码。所以这个方法<b>刻意放在行模型上</b>：
     * 加一列就必须来这个文件看一眼，顺序漂移没有机会发生。
     * <p>
     * ⚠️ 用 {@link Arrays#asList} 而不是 {@code List.of}：{@code List.of} 不接受 null 元素，
     * 而本表的 {@code before_json}（WRITE 时）、{@code reason}、{@code fact_uid}
     * （GUARD_REJECT 时）<b>都是正常的 null</b>。null 由 {@link AuditHashChain#hash}
     * 渲染成专用记号，与空串区分开。
     * <p>
     * ⚠️ <b>不含 {@code id}</b>：物理主键插入前拿不到（本项目不回填主键），
     * 行序由 {@code prev_hash} 显式给出。
     * <p>
     * ⚠️ <b>{@code entityType} 在公式里，这是对 RAG-07 §A.3 的一处补漏</b>：
     * A.3 给的哈希输入列表里没有它，而它在本阶段被加成了判别列
     * （{@code FACT}/{@code SUMMARY}）。不把它算进去的话，"把一条摘要的审计记录
     * 改成事实的"这种改动不会破坏链 —— 一处很小的、但确实是真实存在的完整性漏洞。
     */
    public List<String> payload() {
        return Arrays.asList(
                name(entityType),
                factUid,
                name(action),
                // JSON 必须先规范化：数据库会重排对象键、抹空白，见 AuditHashChain 类注释
                AuditHashChain.canonicalJson(beforeJson),
                AuditHashChain.canonicalJson(afterJson),
                name(actorType),
                actorId,
                reason,
                hashableCreatedAt() == null ? null : hashableCreatedAt().toString());
    }

    /** 组装成校验器认的形状 */
    public AuditHashChain.AuditEntry toAuditEntry() {
        return new AuditHashChain.AuditEntry(prevHash, hash, payload());
    }

    /**
     * ⚠️ 写库前把 {@code createdAt} 的纳秒截掉（{@code .withNano(0)}）。
     * <p>
     * 理由：表的列是 {@code DATETIME}（= {@code DATETIME(0)}），<b>不存小数秒</b>。
     * 若拿一个带纳秒的 {@code LocalDateTime} 去算哈希、数据库却把它截断后存下，
     * 那么读回来重算时用的是截断后的值 —— <b>而 {@code toString()} 把纳秒也写进哈希输入，
     * 于是校验必然假失败</b>，且失败原因看起来像"有人改了时间戳"。
     * <p>
     * 放在行模型上、而不是让每个调用方自己记得：这个坑只需要一个人忘一次。
     * <p>
     * {@link #payload()} 一律走这里取值 —— 于是"哈希用的时间"与"存进库的时间"
     * 在类型层面就是同一个值，调用方就算忘了截断也不会造成假失败。
     */
    public LocalDateTime hashableCreatedAt() {
        return createdAt == null ? null : createdAt.withNano(0);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
