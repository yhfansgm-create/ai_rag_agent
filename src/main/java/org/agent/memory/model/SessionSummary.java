package org.agent.memory.model;

import java.time.Instant;

/**
 * 会话摘要 —— <b>它只负责"找得到"，不负责"答得对"</b>。
 *
 * <h2>⚠️ 这个类的定位是 RAG-07 附录 A1 的校正结论，不是我们自己的取舍</h2>
 * A1 把「把总结（摘要）当成解法」列为<b>已知偏差</b>，原文：
 * <pre>
 *   摘要<b>有损，且破坏引用归因</b>。上一轮「年利率 1.35%」被摘要成「讨论了存款利率」，
 *   下一轮用户问「刚才那个利率」，模型要么答不出，要么编一个被 NumericGuardrailAdvisor 拦掉。
 * </pre>
 * 校正：<b>摘要只能用来定位，不能用来承载事实</b> —— 摘要进上下文（"上一轮聊的是 XX 制度"），
 * 数字按指针回原文取。
 * <p>
 * 一句话钩子：<b>摘要负责"找得到"，原文负责"答得对"。</b>
 * <p>
 * 这条约束<b>由别人给出、也由别人检验</b>，所以它不能只写在提示词里 —— 提示词里的
 * "禁止记录数字"是一句承诺，模型没听话时它什么也不做。机械化的落实在
 * {@code LlmSummaryWriter}：摘要文本必须过 {@code NumericExtractor}，违规即打回重写一次，
 * 仍违规则<b>丢弃这份摘要</b>（宁可让窗口滑走，也不让"1.35% → 讨论了存款利率"这种损失
 * 进入未来所有轮次的上下文）。
 *
 * <h2>为什么摘要必须继承密级</h2>
 * 摘要把高密级轮次的内容<b>压缩后长期携带</b>。若不继承密级，它是一条比 L1 旁路
 * <b>更宽</b>的路 —— <b>一份摘要能顶好几轮</b>（L1 漏一轮只漏一轮）。
 * 所以 {@code clearanceLevel} 由 Java 取被压缩轮次的 <b>max</b> 算，模型无权输出；
 * 读取时按<b>本轮实况信封</b>判定，<b>整份不过就整份丢弃</b>（与 L1"残轮整轮丢弃"同一手法，
 * 绝不出现"半份摘要"）。
 *
 * @param summaryUid            业务键。用途与 {@code factUid} 相同：被取代时要有东西可指
 * @param sessionId             所属会话。摘要是<b>会话级</b>的，与 L2 的跨会话事实分属两层
 * @param summaryText           摘要正文。<b>定位性描述</b>，不含事实性数字（见类注释）
 * @param coversFromTurnNo      覆盖区间起点（含）
 * @param coversThroughTurnNo   覆盖区间终点：<b>开区间上界（不含）</b>。
 *                              ⚠️ 语义是"<b>下一轮从哪开始</b>"，不是"覆盖到第几轮" ——
 *                              这样 {@code [起点, 上界)} 与 L1 的窗口区间拼接起来
 *                              恰好无缝且不重叠：<b>每一轮要么在窗口里、要么在摘要里、
 *                              要么被密级过滤掉，不存在"两边都没有"的偶然丢失</b>。
 *                              少一个"-1"的歧义，就少一处差一错误
 * @param clearanceLevel        密级快照（被压缩轮次的 max）。⚠️ 同 {@code RecalledFact}：
 *                              它<b>不是</b>"能不能看"的判据，判据是本轮实况信封
 * @param sourceTurnCount       折叠了几轮。审计用：它让"摘要变小了但覆盖变宽了"这件事可核对
 * @param expiresAt             到期时间；到期不删行、只是不再注入（与事实记忆同款）
 */
public record SessionSummary(
        String summaryUid,
        String sessionId,
        String summaryText,
        int coversFromTurnNo,
        int coversThroughTurnNo,
        int clearanceLevel,
        int sourceTurnCount,
        Instant expiresAt
) {

    /**
     * 第 {@code turnNo} 轮是否已被这份摘要覆盖（左闭右开）。
     * <p>
     * 放在模型上是因为它是<b>唯一的区间语义出口</b>：压缩触发要问它（"这段有没有被盖过"），
     * 注入时也要问它。若两处各自写一遍 {@code turnNo >= from && turnNo < through}，
     * 迟早会有一处写成 {@code <=} —— 而那个差一错误的表现是"某几轮既不在窗口也不在摘要里"，
     * 静默丢内容，极难发现。
     */
    public boolean covers(int turnNo) {
        return turnNo >= coversFromTurnNo && turnNo < coversThroughTurnNo;
    }
}
