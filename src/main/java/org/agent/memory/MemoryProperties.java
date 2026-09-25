package org.agent.memory;

/**
 * 记忆层配置（{@code rag.memory.*}），纯 record，不依赖 Spring。
 * <p>
 * 刻意做成不可变值对象而不是在 Advisor 里散落一堆 {@code @Value}：
 * <ul>
 *   <li>校验规则集中在紧凑构造里，能单测，不需要起 Spring 上下文</li>
 *   <li>Advisor 只依赖这一个对象，"关掉记忆"就是一个字段，不是散在五个地方的分支</li>
 * </ul>
 *
 * @param enabled              总开关。{@code false} 时 {@code MemoryAdvisor} 第一行就直通，
 *                             <b>不读库不写库</b>，逐字节等价于没有记忆功能时的行为
 * @param windowTurns          注入最近多少轮（一轮 = 2 条消息）
 * @param maxCharsPerMessage   <b>注入侧</b>单条消息的截断长度；{@code 0} = 不截断。
 *                             存储侧永远不截断 —— 审计要原文，回看要能看全
 * @param retentionDays        {@code expires_at = created_at + N 天}；{@code 0} = 不设过期。
 *                             真实留存期由合规定，这里只是个可改的默认值
 * @param clearanceFilterEnabled <b>读取侧</b>是否按本轮实况信封过滤历史（阶段 1）。
 *                             默认开 —— 关掉就是阶段 0 的行为：降级用户仍能读到自己旧轮
 *                             的高密级结论（RAG-07 §一 表 #2 的漏洞现场）。
 *                             留着这个开关正是为了<b>能复现那个漏洞</b>，便于对比演示。
 *                             <p>
 *                             <b>只关读取，不关写入</b>：写入侧算密级是记账，没有拒绝语义，
 *                             关掉它只会让库里出现"密级未知"的行，没有任何演示价值
 */
public record MemoryProperties(
        boolean enabled,
        int windowTurns,
        int maxCharsPerMessage,
        int retentionDays,
        boolean clearanceFilterEnabled
) {

    public static final int DEFAULT_WINDOW_TURNS = 3;
    public static final int DEFAULT_MAX_CHARS_PER_MESSAGE = 2000;
    public static final int DEFAULT_RETENTION_DAYS = 30;

    /**
     * 4 参便捷构造：密级过滤默认开（与 {@code application-local.yml} 的默认一致）。
     * <p>
     * 之所以留这么个构造而不是去改 18 处调用点：那批调用点里绝大多数（测试、
     * 其它模块）根本不关心密级过滤，让它们被迫多写一个 {@code true} 只是噪声。
     * 走的是同一个紧凑构造，所以校验规则一条都不会绕过。
     */
    public MemoryProperties(boolean enabled, int windowTurns, int maxCharsPerMessage,
                            int retentionDays) {
        this(enabled, windowTurns, maxCharsPerMessage, retentionDays, true);
    }

    /**
     * 紧凑构造：非法值一律夹回默认。
     * <p>
     * 放在这里而不是在 {@code @Value} 注入处，是为了堵死"绕过校验直接 new"这条路 ——
     * 不变量由构造保证，不存在非法实例。
     * <p>
     * 特别注意 {@code maxCharsPerMessage} 的语义：{@code 0} 是<b>合法值</b>（不截断），
     * 只有负数才是配错。所以夹取用的是 {@code < 0} 而不是 {@code <= 0}。
     */
    public MemoryProperties {
        if (windowTurns <= 0) {
            windowTurns = DEFAULT_WINDOW_TURNS;
        }
        if (maxCharsPerMessage < 0) {
            maxCharsPerMessage = DEFAULT_MAX_CHARS_PER_MESSAGE;
        }
        if (retentionDays < 0) {
            retentionDays = DEFAULT_RETENTION_DAYS;
        }
    }

    public static MemoryProperties defaults() {
        return new MemoryProperties(true, DEFAULT_WINDOW_TURNS,
                DEFAULT_MAX_CHARS_PER_MESSAGE, DEFAULT_RETENTION_DAYS);
    }

    /** 本轮最多从库里取回几行 —— 一轮两行（user + assistant） */
    public int promptRowLimit() {
        return windowTurns * 2;
    }

    /** 注入侧是否需要截断 */
    public boolean truncateEnabled() {
        return maxCharsPerMessage > 0;
    }

    /** 是否需要设留存到期时间 */
    public boolean retentionEnabled() {
        return retentionDays > 0;
    }
}
