package org.agent.memory;

import java.util.Collection;

/**
 * 上下文预算：估算组装后上下文的体积，并判定要不要做一次更紧的再压缩（纯函数）。
 *
 * <h2>⚠️ 用字符数，不用 token 数</h2>
 * classpath 上<b>没有 tokenizer</b>，所以这里数的是<b>字符码点数</b>
 * （{@link FactValueNormalizer#charCount(String)} —— 全项目"字符预算"的唯一出口）。
 * 中文场景下约 1 字 ≈ 1 token，所以字符数是<b>保守（偏大）</b>的估计：
 * 它会让压缩比真实需要略早触发，代价是多压一次，方向是安全的。
 * <p>
 * 它<b>不是计费数字</b>，是<b>预算</b>。这个区别要写清楚，否则将来有人拿它去对账，
 * 会发现和 provider 报的 token 数对不上 —— 然后"顺手"改成一个它本来就没打算做的事。
 * （同一取舍见 RAG-07 已知缺口 12。）
 *
 * <h2>⚠️ 本类是<b>唯一一处刻意 fail-open</b> 的判定</h2>
 * 预算算不出来、配置缺失、值不合理时，本类一律返回<b>不触发</b>。
 * 这与读取侧（{@code SemanticMemoryService.recall} / {@code SessionSummaryService.loadSummary}）
 * 的 fail-closed 恰好相反，而这个相反是有理由的：
 * <ul>
 *   <li>读取侧的失败形态是"给了不该给的人看" —— 必须往严的方向倒</li>
 *   <li>压缩触发的失败形态是"该压没压" —— 内容仍留在 L1 窗口里，只是没被摘要</li>
 * </ul>
 * <b>压缩是优化，不是闸门。</b>把一个优化做成 fail-closed，风险是"配置一写错就疯狂压缩"，
 * 而那个方向会真的丢内容。同一个项目里两种倒向，是因为被保护的东西不同。
 */
public final class ContextBudget {

    /** 触发比例的下界：≤ 0 说明配置写错了，按"不触发"处理（见类注释的 fail-open 说明） */
    private static final double MIN_RATIO = 0.0;

    private ContextBudget() {
    }

    /**
     * 估算一组文本的字符数（{@code null} 元素与 {@code null} 集合都按 0 处理）。
     * <p>
     * 只数正文，<b>不加</b>任何"消息条数 × 固定开销"的经验值：那种魔法系数一旦引入，
     * 预算数字就再也解释不清了。要调就在 {@code trigger-ratio} 上调，那里只有一个数。
     */
    public static int estimateChars(Collection<String> texts) {
        if (texts == null) {
            return 0;
        }
        int total = 0;
        for (String text : texts) {
            total += FactValueNormalizer.charCount(text);
        }
        return total;
    }

    /**
     * 是否该触发再压缩。
     *
     * @param estimatedChars 组装后的估算字符数
     * @param budgetChars    预算上限（≤ 0 视为未配置 ⇒ 不触发）
     * @param triggerRatio   触发比例（≤ 0 或 ≥ 1 视为未配置 ⇒ 不触发）
     * @return 严格超过阈值才算触发。<b>相等不算</b> —— 触发的语义是"已经越线"，
     *         刚好站在线上不是越线
     */
    public static boolean shouldCompact(int estimatedChars, int budgetChars, double triggerRatio) {
        if (estimatedChars <= 0 || budgetChars <= 0) {
            return false;
        }
        if (triggerRatio <= MIN_RATIO || triggerRatio >= 1.0) {
            return false;
        }
        return estimatedChars > budgetChars * triggerRatio;
    }
}
