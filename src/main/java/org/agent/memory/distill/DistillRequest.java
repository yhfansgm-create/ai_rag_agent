package org.agent.memory.distill;

import org.agent.memory.model.RecalledFact;

import java.time.LocalDate;
import java.util.List;

/**
 * L2 事实抽取的输入。
 *
 * <h2>⚠️ 这里<b>没有</b> {@code answerText}，这是本阶段最要紧的一处"缺失"</h2>
 * 蒸馏的输入刻意只有<b>用户本轮原话</b> + 业务日期 + 已知事实。
 * 理由：答案可能是模型编的、可能是拒答文案，把它放进输入就等于给了模型
 * "把答案抄成记忆"这条路 —— 而 L2 的记忆会被注入<b>未来所有轮次</b>的 prompt，
 * 一条从幻觉答案里抄来的"事实"会永远留在那里。
 * <p>
 * 这与 {@code NumericGuardrailAdvisor}「数字必须有原文出处」是<b>同一套引用归因方法论</b>：
 * 出处必须是可信的那一侧。对"用户是谁"这个问题，可信的一侧是用户自己说的话。
 * <p>
 * ⚠️ 也<b>没有</b> {@code sessionId}：抽取器不需要它，而一切进入提示词的东西都应当是
 * "生成这条事实所必需的"。会话标识对模型没有用处，带上它只会多一个泄露面。
 * 需要打日志时由调用方（{@code SemanticMemoryService}）打 —— 那里本来就有 sessionId。
 *
 * @param userMessage 本轮用户原话（<b>未被 {@code RetrievalAdvisor.augment()} 增强的那一份</b>，
 *                    否则检索原文会跟着一起被"复述"进记忆。执行顺序保证了这一点：
 *                    {@code MemoryAdvisor} 排在 {@code RetrievalAdvisor} 之前）
 * @param bizDate     业务日期。仅用于帮模型理解相对时间说法（"上个月"），
 *                    <b>不构成任何可记录的事实</b> —— 词表里没有日期型事实键
 * @param knownFacts  该用户当前已生效的事实。作用有二：① 让模型不要重复提出已经记住的东西
 *                    （省配额、少噪声）；② 给出上下文，帮它理解指代。
 *                    <b>安全性不依赖它</b>：即便这些值都是脏的，模型据此编出的候选
 *                    也要过 {@code FactGrounding}（必须在用户原话里找到要素）才会落库
 */
public record DistillRequest(
        String userMessage,
        LocalDate bizDate,
        List<RecalledFact> knownFacts
) {

    public DistillRequest {
        knownFacts = knownFacts == null ? List.of() : List.copyOf(knownFacts);
    }

    /** 方便调用方；同 {@link #DistillRequest(String, LocalDate, List)} */
    public static DistillRequest of(String userMessage, LocalDate bizDate, List<RecalledFact> knownFacts) {
        return new DistillRequest(userMessage, bizDate, knownFacts);
    }
}
