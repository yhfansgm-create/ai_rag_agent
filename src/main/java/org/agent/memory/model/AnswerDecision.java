package org.agent.memory.model;

/**
 * 一轮对话的粗粒度结论。
 * <p>
 * 只分两档：{@link #PROCEED}（检索放行、答案可用）与 {@link #REFUSED}（任一层拒答）。
 * 细粒度的拒答原因（NO_HIT / PERMISSION_FILTERED / TEMPORAL_FILTERED / KB_UNAVAILABLE /
 * UNVERIFIED_NUMBER）归 {@code refusal_reason} 列，不在这里再列一遍 ——
 * 一个字段一个职责，避免两个真相源各自漂移。
 */
public enum AnswerDecision {

    /** 检索放行，答案是正常回答 */
    PROCEED,

    /** 被拒答（检索层四种原因或输出侧数字校验），{@code answer_text} 存的是拒答文案 */
    REFUSED
}
