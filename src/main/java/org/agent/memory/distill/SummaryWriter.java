package org.agent.memory.distill;

/**
 * 会话压缩的 <b>seam</b>：把"把若干轮压成一句定位摘要"抽象成接口。
 * 第二个接缝，理由见 {@link FactDistiller} 的类注释。
 *
 * <h2>⚠️ 这个接口的返回值里，{@code null} 是一个<b>有意义的结果</b></h2>
 * 不是"没写"、不是"出错了"，而是<b>"这份摘要不合格，不许落库"</b>。
 * <p>
 * 这看起来是件小事，其实是本层设计最重要的一处落实。RAG-07 附录 A1 把
 * 「把总结（摘要）当成解法」列为<b>已知偏差</b>，原文的校正结论是：
 * <blockquote>
 *   摘要只能用来定位，不能用来承载事实。摘要进上下文（"上一轮聊的是 XX 制度"），
 *   数字按指针回原文取。
 * </blockquote>
 * 而"在提示词里禁止模型记录数字"只是一句<b>承诺</b> —— 模型可能不听话。
 * 让这句承诺变成机制的，正是这个 {@code null}：
 * <b>压缩器在 Java 侧检查产出，不合格就整份丢掉，宁可让窗口滑走。</b>
 * <p>
 * 所以实现类<b>必须</b>把"模型没听话"翻译成 {@code null}，而不是翻译成
 * "就那么用吧"或"截一下凑合用"。有一条全链路测试
 * （{@code SummaryContainsNumberChainTest}）专门盯着这件事：让打桩的压缩器
 * 返回一句带数字的摘要，断言它<b>没有</b>落库、也<b>没有</b>出现在下一轮的 prompt 里。
 *
 * <h2>⚠️ 实现契约：<b>绝不抛异常</b></h2>
 * 同 {@link FactDistiller}：调用方在异步线程上，异常会被线程池吞掉。
 * 失败一律返回 {@code null}（= 丢弃），并在每个分支留下带异常栈的 WARN。
 */
public interface SummaryWriter {

    /**
     * 压缩一组轮次。
     *
     * @param request 待压缩的轮次 + 上一版摘要 + 字符上限
     * @return 可落库的摘要文本；<b>{@code null} 表示放弃这次压缩</b>
     *         （模型失败、或两次都违反"不许出现事实性数字"）
     */
    String summarize(CompactionRequest request);
}
