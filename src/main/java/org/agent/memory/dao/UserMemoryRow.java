package org.agent.memory.dao;

import org.agent.memory.model.FactScope;
import org.agent.memory.model.MemoryStatus;

import java.time.LocalDateTime;

/**
 * {@code user_memory} 行模型（MyBatis 结果映射用）。
 *
 * <h2>⚠️ 这里<b>没有</b> {@code id} 组件 —— 这是刻意的，不是漏了</h2>
 * 与会话表、{@code chat_message} 一致：<b>物理主键不进 record</b>。原因是 record 不可变，
 * 自增主键插完才知道，回填不进来。所以本表有 Java 侧生成的业务键 {@code factUid}，
 * {@code superseded_by} 与审计日志都指向它（详见 {@code sql/user_memory.sql} 文件头）。
 *
 * <h2>字段职责提醒（三个最容易混的）</h2>
 * <ul>
 *   <li>{@code validTo} / {@code supersededBy} —— <b>真值区间</b>："历史上它何时为真"，
 *       被新事实取代时由系统写。回答的是"这条还当前吗"</li>
 *   <li>{@code expiresAt} —— <b>怀疑标记</b>："到什么时候我还可以不问就信"，
 *       写入时按 {@code FactKey.ttlDays} 预先算好。到期<b>不删</b>，只是不再召回</li>
 *   <li>{@code status} —— 生命周期/健康度。⚠️ <b>不</b>表达"是否为当前有效"，
 *       被取代的行仍是 {@link MemoryStatus#ACTIVE}（见 {@link MemoryStatus} 类注释）</li>
 * </ul>
 *
 * <h2>两个权限字段（与 {@code ChatMessageRow} 逐字同一套）</h2>
 * <ul>
 *   <li>{@code clearanceLevel} —— 该条记忆的密级上限，<b>读取判定用</b>。
 *       = 该轮引用资料的最高密级（由 {@code TurnFacts} 带进来，本层不重算）</li>
 *   <li>{@code permAuthVersion} —— 写入时的权限版本，<b>仅审计</b>，不参与读取过滤</li>
 * </ul>
 *
 * @param factUid        业务键（Java 生成 UUID）；{@code superseded_by} 指向后继的它
 * @param ownerId        归属人工号（从信封取，模型无权提供）
 * @param scope          可见范围；本阶段只产生 {@link FactScope#USER}
 * @param factKey        受控词表 code（<b>ASCII</b>，如 {@code business_line}；不是中文标签）
 * @param factValue      归一化后的事实值
 * @param clearanceLevel 密级上限（读取判定用）
 * @param orgPath        {@code scope=DEPT} 时的组织路径（精确匹配）
 * @param sourceSessionId 来源会话（非空 —— "无源不写"）
 * @param sourceTurnNo    来源轮次（非空 —— 指向真实存在的 L1 行）
 * @param refDocId        指向制度文档（存指针不存值；由 Java 从该轮 citedRefs 取）
 * @param validFrom       真值区间起点
 * @param validTo         真值区间终点（null = 当前有效）
 * @param supersededBy    被哪条新记忆取代（后继的 factUid）
 * @param expiresAt       怀疑标记到期（null = 本阶段不应出现；四个键全部带 TTL）
 * @param lastHitAt       最近一次真正被注入 prompt 的时间（阶段 4 巡检③ LRU 用）
 * @param createdAt       创建时间（只在读路径用；插入时由 DB 默认值填）
 */
public record UserMemoryRow(
        String factUid,
        String ownerId,
        FactScope scope,
        String factKey,
        String factValue,
        int clearanceLevel,
        String orgPath,
        String sourceSessionId,
        int sourceTurnNo,
        String refDocId,
        LocalDateTime validFrom,
        LocalDateTime validTo,
        String supersededBy,
        LocalDateTime expiresAt,
        MemoryStatus status,
        LocalDateTime lastHitAt,
        long permAuthVersion,
        LocalDateTime createdAt
) {
}
