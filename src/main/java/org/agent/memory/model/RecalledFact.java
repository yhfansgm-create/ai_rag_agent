package org.agent.memory.model;

import java.time.Instant;

/**
 * 召回侧的一条事实 —— <b>它是"指针 + 结论"，不是"内容的搬运"</b>。
 *
 * <h2>为什么这个模型里带着 {@code sourceTurnNo} / {@code sourceRefDocId}</h2>
 * 记忆层最危险的一种退化是变成<b>第二个知识库</b>：把制度原文摘进 {@code fact_value}
 * 长期携带，于是那份内容既不再受检索层的密级/时效约束，也没有了引用归因
 * （{@code NumericGuardrailAdvisor} 校验的是"本轮检索到的原文"，而记忆块是另一个来源）。
 * <p>
 * 所以记忆行里存的是<b>关于用户的事实</b>（"他主要做对公"），并且始终留着一个指回
 * 出处的指针。本层只做到"留着指针"，"按指针回原文取"要在检索层闭环
 * （见 RAG-07 已知缺口 11）—— 但指针现在就写，将来才不用回填。
 * <p>
 * 不依赖 Spring AI 的任何类型（照 {@code ChatTurn} 先例）：这个模型要能被
 * {@code MemoryBlockRenderer} 直接渲染、被单测直接构造，不需要一个 ChatClient 在场。
 *
 * @param factUid           业务键（{@code fact_uid}）。
 *                          ⚠️ 唯一的用途是<b>命中续期</b>（{@code touchHit}）——
 *                          想标"这条被用过了"就得有它的业务键。物理 {@code id} 依旧不进 record
 * @param factKey           受控词表里的键（已解析，不是原始字符串）
 * @param factValue         归一化后的值（存储形态）
 * @param scope             生效范围。本阶段只会是 {@code USER}（{@code DEPT} 只从
 *                         集成测试里读得到，没有写入路径）
 * @param clearanceLevel    该行自己的密级快照。
 *                          ⚠️ <b>它不是"能不能给他看"的判据</b> —— 那是<b>本轮实况信封</b>的事
 *                          （{@code SemanticMemoryService} 的唯一出口负责）。带上它只是为了
 *                          让 Java 侧能复核"SQL 有没有照信封过滤"，以及给审计留痕
 * @param sourceTurnNo      这条事实来自哪一轮 —— 指针的一半
 * @param sourceRefDocId    该轮引用过的资料 id，没有则为 {@code null} —— 指针的另一半。
 *                          ⚠️ 由调用方从<b>该轮的 citedRefs</b> 取，<b>模型无权输出</b>：
 *                          让模型给出处等于让它编出处
 * @param expiresAt         到期时间。到期<b>不删行</b>，只是不再召回（见 DDL 文件头）
 */
public record RecalledFact(
        String factUid,
        FactKey factKey,
        String factValue,
        FactScope scope,
        int clearanceLevel,
        Integer sourceTurnNo,
        String sourceRefDocId,
        Instant expiresAt
) {

    /**
     * 渲染用的一行文本（{@code 业务条线：对公}）。
     * <p>
     * ⚠️ <b>渲染放在模型上，而不是让 renderer 自己拼</b>：渲染格式（标签用中文、
     * 分隔符用全角冒号）是"这个键怎么念给人听"的一部分，属于词表的职责。
     * 若 renderer 自己拿 {@code factKey.code()} 拼，就会在 prompt 里出现
     * {@code BUSINESS_LINE：对公} 这种既不像中文、又给了模型额外的英文关键词的东西。
     */
    public String renderLine() {
        return factKey.label() + "：" + factValue;
    }
}
