package org.agent.memory.model;

/**
 * 记忆变更的动作类型。
 *
 * <h2>本阶段只产生三种（其余属阶段 4）</h2>
 * <ul>
 *   <li>{@link #WRITE} —— 新增一条有效记忆。{@code before_json} 为 null。
 *       <b>同值重申（REFRESH）也记 WRITE</b>，靠 {@code reason='REFRESH_SAME_VALUE'} 区分 ——
 *       它没有产生新行，但"续了期"是真实发生的状态变化，必须留痕</li>
 *   <li>{@link #SUPERSEDE} —— 旧值被新值取代。一次取代写<b>两条</b>日志：旧行一条
 *       （before/after 是旧行变更前后）、新行一条（WRITE）</li>
 *   <li>{@link #GUARD_REJECT} —— 候选事实被拒写。<b>仅限内容红线</b>（{@code REDLINE_VALUE}）</li>
 * </ul>
 *
 * <h2>⚠️ 为什么"没复述上"不写日志</h2>
 * {@code NOT_GROUNDED} / {@code TIER_CONFIRM} 这类被拒<b>不</b>进本表：它们是<b>例行质量过滤</b>
 * （每轮都可能命中，且命中的候选中绝大多数本来就不该被记），且<b>它们没有对应的记忆条目</b> ——
 * 记进去只会把审计表灌满"从未存在过的记忆"，让真正需要审计的写/取代事件被淹没。
 * 这条例外必须写在这里，否则将来有人"补全"它，反而把审计表变成噪音。
 */
public enum MemoryChangeAction {

    /** 新增有效记忆（含同值重申的续期） */
    WRITE,

    /** 旧值被新值取代（新旧各一条日志） */
    SUPERSEDE,

    /** 内容红线拒写。⚠️ only {@code REDLINE_VALUE} —— 其余拒写理由不进审计表 */
    GUARD_REJECT
}
