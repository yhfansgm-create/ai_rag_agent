package org.agent.memory;

/**
 * L2 语义记忆配置（{@code rag.memory.semantic.*}），纯 record，不依赖 Spring。
 * <p>
 * 与 {@code MemoryProperties} 同一套写法（不可变值对象 + 紧凑构造夹取），
 * 但做成 {@code @Bean} 而不是在 Advisor 里现造：本阶段有<b>四个</b>组件要用它
 * （{@code MemoryAdvisor}、{@code SemanticMemoryService}、{@code MemoryFactWriter}、
 * {@code LlmFactDistiller}），再造四遍就会出现"四处默认值不一致"这种最难查的问题。
 *
 * @param enabled              L2 总开关。{@code false} 时既不召回也不蒸馏 ——
 *                             与 L1 的 {@code rag.memory.enabled} <b>互相独立</b>：
 *                             可以只留 L1（关掉 L2 就是阶段 1 的行为），
 *                             也可以只留 L2（演示"跨会话记得住、但会话内不记"的对比）
 * @param groundingEnabled     ⚠️ <b>复述验证开关，默认必须为 true</b>。
 *                             关掉它 = 模型推测出的假事实也会落库 ——
 *                             这是留给<b>复现漏洞现场</b>的对比开关（照
 *                             {@code rag.memory.clearance-filter-enabled} 的先例：
 *                             能关掉一个防护，才证明那个防护真的在起作用）
 * @param quotaPerUser         每个用户的有效事实上限。到顶后<b>只拒新增</b>，
 *                             取代/续期不受限（否则"改口也改不了"）
 * @param recallLimit          每轮最多从库里取回几条候选。注意它<b>不是</b>最终注入条数 ——
 *                             Java 侧复核（信封密级、过期）还会丢掉一部分
 * @param maxFactsPerBlock     注入区块里最多渲染几条
 * @param maxLineChars         单条事实的行长上限（含前缀与标签）。超了<b>整条丢</b>
 * @param maxBlockChars        事实块整块的字符上限（含声明）。超了从尾部整条丢
 * @param distillThreads       蒸馏线程数。1 也完全够用（一次蒸馏是秒级 LLM 往返，
 *                             但不是高频操作）；配大只是为了演示时并发几轮不排队
 * @param distillQueueCapacity 蒸馏队列容量。满了按 <b>拒绝并打 WARN</b> 处理，
 *                             不用静默丢弃 —— 静默丢弃会让"记忆偶尔不写"变成一个
 *                             查不出来的现象（这是本阶段最容易踩的运维坑）
 */
public record SemanticMemoryProperties(
        boolean enabled,
        boolean groundingEnabled,
        int quotaPerUser,
        int recallLimit,
        int maxFactsPerBlock,
        int maxLineChars,
        int maxBlockChars,
        int distillThreads,
        int distillQueueCapacity
) {

    public static final int DEFAULT_QUOTA_PER_USER = 200;
    public static final int DEFAULT_RECALL_LIMIT = 10;
    public static final int DEFAULT_MAX_FACTS_PER_BLOCK = 10;
    public static final int DEFAULT_MAX_LINE_CHARS = 80;
    public static final int DEFAULT_MAX_BLOCK_CHARS = 800;
    public static final int DEFAULT_DISTILL_THREADS = 2;
    public static final int DEFAULT_DISTILL_QUEUE_CAPACITY = 200;

    /**
     * 紧凑构造：非法值一律夹回默认（照 {@code MemoryProperties} 的写法）。
     * <p>
     * ⚠️ {@code groundingEnabled} <b>不在这里做任何"安全兜底"</b> ——
     * 它是个显式的演示开关，不是配错了要救回来的东西。给它加兜底只会让
     * "关掉验证"这件事变得不可预期（配置写了 false、行为却是 true，
     * 那这条配置就成了一个骗人的开关）。
     */
    public SemanticMemoryProperties {
        if (quotaPerUser <= 0) {
            quotaPerUser = DEFAULT_QUOTA_PER_USER;
        }
        if (recallLimit <= 0) {
            recallLimit = DEFAULT_RECALL_LIMIT;
        }
        if (maxFactsPerBlock <= 0) {
            maxFactsPerBlock = DEFAULT_MAX_FACTS_PER_BLOCK;
        }
        if (maxLineChars <= 0) {
            maxLineChars = DEFAULT_MAX_LINE_CHARS;
        }
        if (maxBlockChars <= 0) {
            maxBlockChars = DEFAULT_MAX_BLOCK_CHARS;
        }
        if (distillThreads <= 0) {
            distillThreads = DEFAULT_DISTILL_THREADS;
        }
        if (distillQueueCapacity <= 0) {
            distillQueueCapacity = DEFAULT_DISTILL_QUEUE_CAPACITY;
        }
    }

    public static SemanticMemoryProperties defaults() {
        return new SemanticMemoryProperties(true, true, DEFAULT_QUOTA_PER_USER,
                DEFAULT_RECALL_LIMIT, DEFAULT_MAX_FACTS_PER_BLOCK, DEFAULT_MAX_LINE_CHARS,
                DEFAULT_MAX_BLOCK_CHARS, DEFAULT_DISTILL_THREADS, DEFAULT_DISTILL_QUEUE_CAPACITY);
    }

    /** 关掉 L2（测试里表示"这一层不参与"） */
    public static SemanticMemoryProperties disabled() {
        return new SemanticMemoryProperties(false, true, DEFAULT_QUOTA_PER_USER,
                DEFAULT_RECALL_LIMIT, DEFAULT_MAX_FACTS_PER_BLOCK, DEFAULT_MAX_LINE_CHARS,
                DEFAULT_MAX_BLOCK_CHARS, DEFAULT_DISTILL_THREADS, DEFAULT_DISTILL_QUEUE_CAPACITY);
    }
}
