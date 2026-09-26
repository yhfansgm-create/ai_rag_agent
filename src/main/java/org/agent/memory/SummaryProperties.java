package org.agent.memory;

/**
 * 会话摘要/压缩配置（{@code rag.memory.summary.*}），纯 record，不依赖 Spring。
 * <p>
 * 单独一个 record 而不是塞进 {@code SemanticMemoryProperties}：两者是<b>两件事</b> ——
 * L2 存<b>跨会话的稳定事实</b>，摘要存<b>会话内的近期上下文</b>。合并的话，
 * 「关掉摘要只留 L2」这种对比演示就做不出来了，而这个对比正是说明
 * 「摘要负责找得到、原文负责答得对」的最直接方式。
 *
 * @param enabled             摘要总开关。{@code false} 时：不压缩、不注入摘要块 ——
 *                            此时 L1 就是一条硬滑窗（阶段 1 的行为），
 *                            于是「第 3 轮以前整段消失」这个现象<b>可以现场复现</b>
 * @param maxSummaryChars     一份摘要最多多少字符（= 注入区块的预算）。
 *                            超预算的策略是<b>丢弃整份</b>而不是截断 ——
 *                            被腰斩的摘要会变成一条错误的定位信息
 * @param budgetChars         上下文预算（字符数）。见 {@code ContextBudget}：
 *                            classpath 上没有 tokenizer，用字符数当预算
 *                            （中文约 1 字 ≈ 1 token，字符数是保守的偏大估计）。
 *                            ⚠️ 它是<b>预算</b>不是计费数字
 * @param triggerRatio        {@link #budgetChars} 的触发比例，取值必须落在 (0,1) 开区间。
 *                            超了才对已有摘要做一次更紧的再压缩（§5.5 的 token 规则）
 * @param sourceTurnMaxChars  单轮送进压缩器的文本上限（用户原话 + 答案）。
 *                            超长轮次要截断 —— 摘要是<b>有损</b>的，这是设计前提不是缺陷
 * @param retentionDays       摘要的保质期（天）。与 L1 的 {@code retention-days} 同口径，
 *                            但<b>互相独立</b>：摘要承载的内容比 L1 窗口宽，且被长期携带
 */
public record SummaryProperties(
        boolean enabled,
        int maxSummaryChars,
        int budgetChars,
        double triggerRatio,
        int sourceTurnMaxChars,
        int retentionDays
) {

    public static final int DEFAULT_MAX_SUMMARY_CHARS = 600;
    public static final int DEFAULT_BUDGET_CHARS = 8000;
    public static final double DEFAULT_TRIGGER_RATIO = 0.7;
    public static final int DEFAULT_SOURCE_TURN_MAX_CHARS = 500;
    public static final int DEFAULT_RETENTION_DAYS = 30;

    /**
     * 紧凑构造：非法值夹回默认。
     * <p>
     * ⚠️ {@code triggerRatio} 的夹取是<b>开区间</b> (0,1)：
     * <ul>
     *   <li>{@code >= 1} ⇒ 永远不触发（预算都超了才压缩，那压缩也没意义了）——
     *       等于把这个功能悄悄关掉，比配错值更坏</li>
     *   <li>{@code <= 0} ⇒ 每轮都触发，把压缩变成了每轮一次 LLM 调用</li>
     * </ul>
     * 这两头都不是"想配的值"，是笔误，所以夹回默认而不是拒绝启动 ——
     * 与 {@code MemoryProperties} 处理 {@code max-chars-per-message == 0} 的取舍一致：
     * 一个配错的数不该让整个助手起不来。
     */
    public SummaryProperties {
        if (maxSummaryChars <= 0) {
            maxSummaryChars = DEFAULT_MAX_SUMMARY_CHARS;
        }
        if (budgetChars <= 0) {
            budgetChars = DEFAULT_BUDGET_CHARS;
        }
        if (!(triggerRatio > 0.0) || !(triggerRatio < 1.0)) {
            // 写成 !(...) 而不是 <= / >= ：NaN 与两个比较都返回 false，
            // 于是 NaN 会静默通过。用取反的写法，NaN 也落进夹取这一支
            triggerRatio = DEFAULT_TRIGGER_RATIO;
        }
        if (sourceTurnMaxChars <= 0) {
            sourceTurnMaxChars = DEFAULT_SOURCE_TURN_MAX_CHARS;
        }
        if (retentionDays <= 0) {
            retentionDays = DEFAULT_RETENTION_DAYS;
        }
    }

    public static SummaryProperties defaults() {
        return new SummaryProperties(true, DEFAULT_MAX_SUMMARY_CHARS, DEFAULT_BUDGET_CHARS,
                DEFAULT_TRIGGER_RATIO, DEFAULT_SOURCE_TURN_MAX_CHARS, DEFAULT_RETENTION_DAYS);
    }

    /** 关掉摘要（复现"第 3 轮以前整段消失"） */
    public static SummaryProperties disabled() {
        return new SummaryProperties(false, DEFAULT_MAX_SUMMARY_CHARS, DEFAULT_BUDGET_CHARS,
                DEFAULT_TRIGGER_RATIO, DEFAULT_SOURCE_TURN_MAX_CHARS, DEFAULT_RETENTION_DAYS);
    }
}
