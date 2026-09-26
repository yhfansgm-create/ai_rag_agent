package org.agent.memory.model;

/**
 * 记忆变更日志里"被变更的是哪一类实体"的判别列。
 * <p>
 * 存在理由是<b>被审计的实体有两种</b>：L2 的稳定事实（{@code user_memory}）与会话摘要
 * （{@code session_summary}）。两者都要审计，但它们是不同的实体。
 * <b>一张审计表带判别列，好过两张几乎相同的表</b> —— 这也让 RAG-04 #13
 * 「审计表 + 哈希链」将来能直接复用这张表。
 * <p>
 * 与 {@code action} 的关系：{@code entity_type} 说"改的是什么"，
 * {@code action} 说"怎么改的"，两者正交，组合起来才是一次完整的变更语义。
 */
public enum MemoryEntityType {

    /** L2 语义记忆（{@code user_memory} 的一行事实） */
    FACT,

    /** 会话摘要（{@code session_summary} 的一版摘要） */
    SUMMARY
}
