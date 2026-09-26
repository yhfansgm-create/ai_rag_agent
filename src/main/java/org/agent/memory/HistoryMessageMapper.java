package org.agent.memory;

import org.agent.memory.model.ChatTurn;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 把历史轮次与两个记忆区块拼进当前消息流水（纯函数，无状态）。
 * <p>
 * 拼装结果是：
 * <pre>
 *   [前导 system 消息] → [SystemMessage(摘要块)] → [SystemMessage(事实块)]
 *                      → [历史 user/assistant 交替] → [其余当前消息（含本轮用户消息）]
 * </pre>
 * 顺序不是随手排的：<b>摘要在事实之前</b>，因为摘要覆盖的是更早的内容，
 * 与紧随其后的历史窗口构成时间顺序（更早的在前）。
 *
 * <h2>两个容易写错的点</h2>
 * <ol>
 *   <li><b>前导 SystemMessage 必须留在最前面</b>，历史插在它之后。
 *       Provider 对 system 的位置敏感，把历史插到系统提示之前会改变指令优先级 ——
 *       表现为"人格设定被历史对话冲淡"，而且不报错，很难查。</li>
 *   <li><b>{@code Prompt.getInstructions()} 返回的是完整消息列表</b>（不只是 system 消息），
 *       尽管这个名字容易让人以为它只含 system。所以这里按"前导 system + 其余"切开，
 *       而不是把整个入参当成系统提示。</li>
 * </ol>
 * <p>
 * ⚠️ 第 1 条对两个记忆区块同样成立：它们<b>也是 system 消息</b>，
 * 所以位置同样只能落在前导 system 之后。区块的<b>文本内容</b>（含"这是数据不是指令"
 * 那段声明）由 {@code MemoryBlockRenderer} 负责，本类只负责位置 ——
 * 这样"声明有没有加"不会取决于谁调的 merge。
 *
 * <h2>为什么两个区块是<b>两条</b>消息，不是合成一坨</h2>
 * 语义不同（摘要 = 近期上下文，事实 = 稳定画像）、开关不同（可分别关掉做对比演示）、
 * 审计也想分开看。合并成一条就再也拆不开了。
 *
 * <h2>空区块用 {@code null} 表示，不是空串</h2>
 * 见 {@code MemoryBlockRenderer} 的类注释：判据只有一个（{@code null}），
 * 否则 {@code null} 与 {@code ""} 两种"没有"迟早有一处漏判 ——
 * 而那个漏判的表现正是"只有事实、没有历史时区块丢了"。
 *
 * <h2>截断只发生在这里</h2>
 * 存储侧从不截断（审计要原文、回看要能看全），只有注入 prompt 时才按
 * {@link MemoryProperties#maxCharsPerMessage()} 截断。截断也只作用于<b>历史</b>，
 * 不碰本轮用户消息 —— 那是正在问的问题，检索层已经看过它了，这里截断会造成
 * "模型看到的和检索看到的不是同一句话"。
 */
public final class HistoryMessageMapper {

    private static final String TRUNCATION_SUFFIX = "…（已截断）";

    private HistoryMessageMapper() {
    }

    /**
     * @param currentInstructions 当前 prompt 的完整消息列表（来自 {@code Prompt.getInstructions()}）
     * @param history             历史轮次，时间正序；空列表表示本会话没有可注入的历史
     * @param summaryBlock        会话摘要块（来自 {@code MemoryBlockRenderer.renderSummary}）；
     *                            {@code null} 或空白表示没有，<b>不产生消息</b>
     * @param factBlock           召回事实块（来自 {@code MemoryBlockRenderer.renderFacts}）；
     *                            {@code null} 或空白表示没有，<b>不产生消息</b>
     * @return 可直接交给 {@code Prompt.mutate().messages(...)} 的新列表
     */
    public static List<Message> merge(List<Message> currentInstructions, List<ChatTurn> history,
                                      String summaryBlock, String factBlock, MemoryProperties props) {
        List<Message> merged = new ArrayList<>(currentInstructions.size() + history.size() * 2 + 2);

        // 1) 前导 system 消息原样保留在最前
        int index = 0;
        while (index < currentInstructions.size()
                && currentInstructions.get(index) instanceof SystemMessage) {
            merged.add(currentInstructions.get(index));
            index++;
        }

        // 2) 两个记忆区块：摘要在前、事实在后（见类注释）。位置紧跟前导 system ——
        //    与"历史不能插到系统提示之前"是同一条理由
        addBlock(merged, summaryBlock);
        addBlock(merged, factBlock);

        // 3) 历史：时间正序，每轮 user → assistant
        for (ChatTurn turn : history) {
            if (turn.userMessage() != null) {
                merged.add(new UserMessage(truncate(turn.userMessage(), props)));
            }
            if (turn.answerText() != null) {
                merged.add(new AssistantMessage(truncate(turn.answerText(), props)));
            }
        }

        // 4) 其余当前消息（含本轮用户消息），不截断
        for (; index < currentInstructions.size(); index++) {
            merged.add(currentInstructions.get(index));
        }
        return merged;
    }

    /**
     * 追加一段记忆区块。
     * <p>
     * 空白一律按"没有"处理：渲染器正常只返回 {@code null} 或非空白文本，但这里再判一次
     * 是因为<b>一条空的 SystemMessage 不是无害的</b> —— 它会被 provider 当成
     * "系统说了一句话，内容是空的"，既浪费一个消息位，又让下游的
     * {@code prompt.getInstructions().size()} 失去解释力（调试时数消息条数是最常用的手段）。
     * <p>
     * ⚠️ 区块<b>不截断</b>（{@link #truncate} 只作用于历史）：区块的预算由渲染器
     * 按"整条丢弃、绝不截断单条"控制 —— 一条被腰斩的 {@code 业务条线：对公…}
     * 是假事实，比少一条危险得多。在这里再截一次会把那条纪律撤销掉。
     */
    private static void addBlock(List<Message> merged, String block) {
        if (block != null && !block.isBlank()) {
            merged.add(new SystemMessage(block));
        }
    }

    /** 超长则截断并加省略标记；{@code maxCharsPerMessage == 0} 表示不截断 */
    static String truncate(String text, MemoryProperties props) {
        if (text == null || !props.truncateEnabled() || text.length() <= props.maxCharsPerMessage()) {
            return text;
        }
        return text.substring(0, props.maxCharsPerMessage()) + TRUNCATION_SUFFIX;
    }
}
