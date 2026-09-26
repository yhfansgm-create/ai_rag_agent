package org.agent.memory.config;

import lombok.extern.slf4j.Slf4j;
import org.agent.memory.SemanticMemoryProperties;
import org.agent.memory.SummaryProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * L2 语义记忆 + 会话摘要的装配（配置对象 + 蒸馏线程池）。
 *
 * <h2>为什么配置在这里做成 {@code @Bean}，而 L1 的 {@code MemoryProperties} 不是</h2>
 * {@code MemoryProperties} 只有 {@code MemoryAdvisor} 一个消费方，在构造里现造就够了。
 * 本阶段有<b>四个</b>组件要用 {@link SemanticMemoryProperties}
 * （{@code MemoryAdvisor}、{@code SemanticMemoryService}、{@code MemoryFactWriter}、
 * {@code LlmFactDistiller}）—— 如果各自用 {@code @Value} 现造，同一个默认值要在四处
 * 分别写一遍，而"四处不一致"是最难查的一类问题（每一处单看都对，行为却取决于谁构造的）。
 * <b>一个配置一个出口</b>，与权限判定那条纪律同源。
 *
 * <h2>⚠️ 这个线程池是"承诺的兑现"，不是新的架构选择</h2>
 * {@code MemoryAdvisor} 的类注释里早就写下了这个承诺：
 * <blockquote>
 *   等写入变成跨表多条（<b>阶段 2 的 L2 蒸馏</b>）时再上有界队列 + 丢弃策略。
 * </blockquote>
 * 本类就是那句话说的时刻。之所以必须异步：蒸馏是一次<b>完整的 LLM 往返</b>（秒级），
 * 同步会让每一次 {@code .call()} 都多等一个 RTT —— 而用户等的是回答，不是记忆的写入。
 *
 * <h2>⚠️ 队列必须有界，且必须"拒绝时打 WARN"</h2>
 * <ul>
 *   <li><b>有界</b>：{@code newFixedThreadPool} 用的是无界 {@code LinkedBlockingQueue}，
 *       队列会一直涨到 OOM —— 在"LLM 变慢"这种最常见的故障下，无界队列把一次性能问题
 *       变成了内存问题</li>
 *   <li><b>打 WARN 而不是静默丢弃</b>：{@code DiscardPolicy} 的后果是"记忆偶尔不写"，
 *       一个<b>查不出来</b>的现象 —— 没有异常、没有日志、行为只是"有时候记得住有时候记不住"。
 *       有界队列的代价本来就是"忙碌时丢任务"，那是个<b>可以接受的取舍</b>；
 *       但它必须<b>可见</b>，否则取舍就变成了 bug</li>
 * </ul>
 *
 * <h2>⚠️ 线程是 daemon</h2>
 * 非 daemon 的工作线程会<b>阻止 JVM 退出</b>。这个项目已经有过一次"进程没死透、
 * 悄悄占着端口"的教训（见 {@code CLAUDE.md}），不要让记忆模块再制造一次：
 * 即使 Spring 的关闭钩子没跑到（kill -9、调试器直接终止），daemon 线程也不会吊住进程。
 * <p>
 * （{@code ThreadPoolExecutor} 这个 bean 会被 Spring 推断出 {@code shutdown()} 作为
 * 销毁方法，正常关闭时队列里的任务还能跑完 —— 这是我们要的：不要 interrupt 一个
 * 正在写库的任务，那会让事务半途而废。）
 */
@Slf4j
@Configuration
public class MemoryDistillConfig {

    /** 线程名前缀。⚠️ 必须能一眼看出是记忆模块起的线程 —— 排查"这个线程是谁的"时省半小时 */
    private static final String THREAD_NAME_PREFIX = "memory-distill-";

    /**
     * 池里的线程是否随 JVM 退出而结束。见类注释：必须是 daemon
     */
    private static final boolean DAEMON_THREADS = true;

    /**
     * L2 语义记忆配置。{@code rag.memory.semantic.*}
     * <p>
     * ⚠️ 每个 {@code @Value} 都带默认值：这些属性在 {@code application-local.yml} 里，
     * 但"配置文件里漏了一条"不该让整个助手起不来（与 {@code MemoryAdvisor} 处理
     * 五个 {@code rag.memory.*} 属性同一取舍）。非法值的夹取在 record 的紧凑构造里做，
     * <b>这里不做第二遍</b> —— 否则同一件事有两个地方管，就会出现"改了这边没改那边"。
     */
    @Bean
    public SemanticMemoryProperties semanticMemoryProperties(
            @Value("${rag.memory.semantic.enabled:true}") boolean enabled,
            @Value("${rag.memory.semantic.grounding-enabled:true}") boolean groundingEnabled,
            @Value("${rag.memory.semantic.quota-per-user:200}") int quotaPerUser,
            @Value("${rag.memory.semantic.recall-limit:10}") int recallLimit,
            @Value("${rag.memory.semantic.max-facts-per-block:10}") int maxFactsPerBlock,
            @Value("${rag.memory.semantic.max-line-chars:80}") int maxLineChars,
            @Value("${rag.memory.semantic.max-block-chars:800}") int maxBlockChars,
            @Value("${rag.memory.semantic.distill-threads:2}") int distillThreads,
            @Value("${rag.memory.semantic.distill-queue-capacity:200}") int distillQueueCapacity) {
        return new SemanticMemoryProperties(enabled, groundingEnabled, quotaPerUser, recallLimit,
                maxFactsPerBlock, maxLineChars, maxBlockChars, distillThreads, distillQueueCapacity);
    }

    /**
     * 会话摘要/压缩配置。{@code rag.memory.summary.*}
     */
    @Bean
    public SummaryProperties summaryProperties(
            @Value("${rag.memory.summary.enabled:true}") boolean enabled,
            @Value("${rag.memory.summary.max-summary-chars:600}") int maxSummaryChars,
            @Value("${rag.memory.summary.budget-chars:8000}") int budgetChars,
            @Value("${rag.memory.summary.trigger-ratio:0.7}") double triggerRatio,
            @Value("${rag.memory.summary.source-turn-max-chars:500}") int sourceTurnMaxChars,
            @Value("${rag.memory.summary.retention-days:30}") int retentionDays) {
        return new SummaryProperties(enabled, maxSummaryChars, budgetChars, triggerRatio,
                sourceTurnMaxChars, retentionDays);
    }

    /**
     * 蒸馏线程池：L2 事实抽取与会话压缩**共用**这一个池。
     * <p>
     * 之所以共用而不各起一个：两者都是"轮末的异步 LLM 往返"，同一时刻通常只有一个在跑，
     * 分成两个池会让"总共最多几个并发 LLM 调用"变成一个要跨两个配置项相乘才算得出来的数。
     * 而"我们一共会并发打几个模型"是一眼就该看明白的事。
     * <p>
     * ⚠️ 池的容量取自 {@link SemanticMemoryProperties}（{@code rag.memory.semantic.*}），
     * 而不是摘要那组 —— 一个池只有一个容量，取谁都会让另一边"配了不生效"，
     * 所以<b>在 yml 里注释写明</b>这两个池参数属于语义记忆那一段。
     * <p>
     * ⚠️ 使用 {@code ArrayBlockingQueue}（有界）而不是 {@code LinkedBlockingQueue}：
     * 见类注释。队列满时的行为见 {@link WarnAndDiscardPolicy}。
     */
    @Bean("memoryDistillExecutor")
    public ExecutorService memoryDistillExecutor(SemanticMemoryProperties props) {
        ThreadFactory threadFactory = new NamedDaemonThreadFactory();

        return new ThreadPoolExecutor(
                props.distillThreads(),
                props.distillThreads(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(props.distillQueueCapacity()),
                threadFactory,
                new WarnAndDiscardPolicy());
    }

    /**
     * 队列满时的拒绝策略：<b>打一条 WARN，然后丢弃这个任务</b>。
     *
     * <h2>为什么不用 {@code DiscardPolicy}</h2>
     * 它把"任务被丢了"这件事抹掉了。于是"记忆偶尔不写"会表现为一个<b>无法归因</b>的现象 ——
     * 用户报"它有时候记得住有时候记不住"，而日志里干干净净。
     *
     * <h2>为什么不用 {@code CallerRunsPolicy}</h2>
     * 这一条更容易被误选（"优雅降级"听起来很好）：它让<b>提交任务的线程</b>去跑这个任务。
     * 而我们提交任务的地方正是 {@code MemoryAdvisor} —— 那意味着<b>用户这一轮的响应
     * 要等一次完整的 LLM 往返</b>，而且是在"系统已经忙不过来"的时候最可能发生。
     * 它会把这个池的过载<b>转嫁到用户等待上</b>，是本项目最不该做的事。
     *
     * <h2>为什么不用 {@code AbortPolicy}（默认）</h2>
     * 它抛 {@code RejectedExecutionException}。而此时调用方正在
     * {@code appendTurnSafely} 的 try/catch 里写会话历史 —— 于是一个"记忆写不下"
     * 的小问题会变成"本轮历史也写不成"的大问题。丢弃是这里唯一不牵连别的动作的选择。
     *
     * <h2>⚠️ 丢弃是<b>有损</b>的，必须承认</h2>
     * 被丢掉的那一轮事实<b>不会补回来</b>（没有重放队列，见计划的缺口 #9）。
     * 这是可接受的：记忆是"锦上添花"，而它的失败绝不能影响回答本身。
     */
    static final class WarnAndDiscardPolicy implements RejectedExecutionHandler {

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            // ⚠️ 日志里带池的实况：只写"队列满了"没法判断是"模型变慢"还是"线程数配小了"
            log.warn("记忆蒸馏队列已满，这一轮的 L2 蒸馏被丢弃（本轮记忆不会写入，不影响回答）。"
                            + "activeCount={}, queueSize={}, poolSize={}, completedTasks={}",
                    executor.getActiveCount(), executor.getQueue().size(),
                    executor.getPoolSize(), executor.getCompletedTaskCount());
        }
    }

    /**
     * 起名的 daemon 线程工厂。
     * <p>
     * 名字形如 {@code memory-distill-1}：排查线程泄漏时，{@code jstack} 里一眼能认出来。
     * 匿名工厂（{@code Executors.defaultThreadFactory()}）给的是 {@code pool-1-thread-1}，
     * 在多池的应用里等于没有信息。
     */
    static final class NamedDaemonThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, THREAD_NAME_PREFIX + counter.incrementAndGet());
            thread.setDaemon(DAEMON_THREADS);
            return thread;
        }
    }
}
