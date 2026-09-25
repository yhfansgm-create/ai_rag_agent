package org.agent.memory;

import org.agent.memory.model.ChatTurn;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 把历史轮次拼进当前消息流水（纯函数，无状态）。
 * <p>
 * 拼装结果是：{@code [前导 system 消息] + [历史 user/assistant 交替] + [其余当前消息（含本轮用户消息）]}。
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
     * @param history             历史轮次，时间正序；空列表时原样返回 {@code currentInstructions} 的内容
     * @return 可直接交给 {@code Prompt.mutate().messages(...)} 的新列表
     */
    public static List<Message> merge(List<Message> currentInstructions, List<ChatTurn> history,
                                      MemoryProperties props) {
        List<Message> merged = new ArrayList<>(currentInstructions.size() + history.size() * 2);

        // 1) 前导 system 消息原样保留在最前
        int index = 0;
        while (index < currentInstructions.size()
                && currentInstructions.get(index) instanceof SystemMessage) {
            merged.add(currentInstructions.get(index));
            index++;
        }

        // 2) 历史：时间正序，每轮 user → assistant
        for (ChatTurn turn : history) {
            if (turn.userMessage() != null) {
                merged.add(new UserMessage(truncate(turn.userMessage(), props)));
            }
            if (turn.answerText() != null) {
                merged.add(new AssistantMessage(truncate(turn.answerText(), props)));
            }
        }

        // 3) 其余当前消息（含本轮用户消息），不截断
        for (; index < currentInstructions.size(); index++) {
            merged.add(currentInstructions.get(index));
        }
        return merged;
    }

    /** 超长则截断并加省略标记；{@code maxCharsPerMessage == 0} 表示不截断 */
    static String truncate(String text, MemoryProperties props) {
        if (text == null || !props.truncateEnabled() || text.length() <= props.maxCharsPerMessage()) {
            return text;
        }
        return text.substring(0, props.maxCharsPerMessage()) + TRUNCATION_SUFFIX;
    }
}
