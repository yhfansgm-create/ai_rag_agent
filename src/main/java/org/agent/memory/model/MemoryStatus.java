package org.agent.memory.model;

/**
 * 语义记忆的生命周期/健康度。
 *
 * <h2>⚠️ 这个枚举<b>不</b>表达"是否为当前有效" —— 那是 {@code valid_to} 的职责</h2>
 * 两处文档合起来指向同一结论：
 * <ul>
 *   <li>RAG-07 §A.2 的 {@link #STALE} 注释写的是「<b>所依制度已废止</b>」</li>
 *   <li>§四 巡检②的判据写的是「同 {@code fact_key} 下多条 <b>{@code valid_to IS NULL}</b> 的记录」</li>
 * </ul>
 * 于是：<b>被新事实取代的行仍然是 {@link #ACTIVE}</b> —— 它只是"不再当前"
 * （{@code valid_to} 非空），不是"坏"。给 {@link #STALE} 塞第二个含义
 * （"被取代"）会直接破坏阶段 4 的巡检①。
 * <p>
 * <b>风险与对策</b>：将来有人写 {@code WHERE status = 'ACTIVE'} 会把被取代的历史行一起捞回来。
 * 对策是 DAO 刻意<b>不提供</b>任何"只按 status 筛"的通用查询（接口形状防误用，
 * 与 {@code ChatMessageDao}「不提供按权限字段过滤的查询」是同一个手法），
 * 且任何用到 status 的查询都必须同时带上 {@code valid_to IS NULL}。
 */
public enum MemoryStatus {

    /** 正常状态。<b>包含被取代的历史行</b>（见类注释） */
    ACTIVE,

    /** 待人工确认。⚠️ 本阶段<b>不产生</b>这个状态：需确认类目直接不写，没有 PENDING 状态机 */
    PENDING,

    /** 所依制度已废止（阶段 4 巡检①置位）。⚠️ 与"被取代"无关 */
    STALE,

    /** 用户主动删除（阶段 4 的可删接口）。物理删除永不 —— 软删只换状态、留行 */
    USER_DELETED,

    /** 已过 {@code expires_at}。⚠️ 本阶段<b>不产生</b>这个状态：读路径已在 SQL 里排除过期行，
     *  但没有 sweeper 去改状态列（缺口 2：过期行 status 仍是 ACTIVE，审计口径不一致） */
    EXPIRED
}
