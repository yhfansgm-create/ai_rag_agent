package org.agent.memory.distill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 结构化输出目标：模型被要求产出的 JSON 形状。
 *
 * <h2>⚠️ 这个 record 的字段名<b>就是</b>提示词契约</h2>
 * {@code ChatClient.call().entity(DistillResult.class)} 会用反射把这个类型生成 JSON Schema
 * 附在提示词末尾（{@code jsonschema-generator}，已在 {@code pom.xml} 里，无需新增依赖）。
 * 所以<b>改一个字段名 = 改提示词</b>，而提示词改了而模型没跟上时，症状是
 * "L2 永远不写"且日志里只有一条解析失败 —— 改这个类之前请先看一眼 {@code LlmFactDistiller}
 * 内联的那段提示词，以及 {@code LlmFactDistillerTest} 里锁住 prompt 的断言。
 *
 * <h2>⚠️ 这里<b>不做</b>任何校验，是刻意的</h2>
 * 键是否认识、值是否合规、够不够格写入 —— 全部归 {@code FactWritePolicy} 一个出口。
 * 在这里多加一道"顺手检查一下"的 if，就会变成"策略有两处、改了一处忘了另一处"。
 * 本类型唯一的职责是<b>把 JSON 装下来</b>。
 * <p>
 * 唯一的例外是 {@code facts} 可能为 {@code null}（模型回了个 {@code {}}）——
 * 那不是策略判断，是空指针的防御，见 {@link #candidates()}。
 *
 * @param facts 候选事实。<b>可以为 null</b>（模型输出 {@code {}} 或 {@code {"facts":null}}）
 */
public record DistillResult(List<Candidate> facts) {

    /**
     * 一条候选。
     * <p>
     * ⚠️ 字段故意只用 {@code String} / {@code Double} 这类最朴素的类型：
     * 每个复杂类型都会在 JSON Schema 里变成一段模型要读的约束，而类型越"聪明"
     * （枚举、日期、嵌套），模型答错的概率越高。而这里答错一次的代价是
     * <b>整份结果被丢弃</b>（见 {@code LlmFactDistiller}），不是"退化成部分可用"。
     *
     * @param key        事实键。模型可能答 code（{@code business_line}）或中文标签
     *                   （{@code 业务条线}）—— 都交给 {@code FactKey.resolve} 容错，
     *                   不在这里挑一种
     * @param value      值。期望"照抄用户原话"，但它是否真的照抄了由
     *                   {@code FactGrounding} 在 Java 侧判定 —— <b>提示词里的"照抄"是要求，
     *                   不是保证</b>，这是本阶段全部安全性的立足点
     * @param confidence 模型自报置信度。⚠️ <b>只用于人工复核排序，不参与任何判定</b>：
     *                   模型自报的置信度不是校准信号，拿它当门禁是<b>装饰性的安全性</b>
     *                   （见 {@code FactDraft} 的类注释）。用 {@code Double} 而不是
     *                   {@code BigDecimal}：反正不用它做决策，而 {@code Double} 对
     *                   模型输出的 {@code 0.9} / {@code 1} / {@code null} 都容得下
     */
    public record Candidate(String key, String value, Double confidence) {
    }

    /**
     * 非空候选列表。
     * <p>
     * 只做"null → 空列表"这一件事，不做过滤：{@code null} 元素、空 key、空 value
     * 都会原样落到下游 —— 因为它们的处理方式是<b>策略</b>（{@code FactWritePolicy}
     * 会给出具名理由 {@code UNKNOWN_KEY} / {@code VALUE_EMPTY}），而策略只该有一个出口。
     * 在这里顺手滤掉，那些候选就永远不会出现在审计里，也就没人知道模型提过它们。
     * <p>
     * ⚠️ 用 {@code new ArrayList<>(...)} 而不是 {@code List.copyOf(...)}：后者
     * <b>不接受 null 元素</b>（直接 NPE）。而模型完全可能输出 {@code {"facts":[null]}}，
     * 那会让整个 {@code DistillResult} 因为一个元素而作废 —— 而它本该只是
     * 一条 {@code UNKNOWN_KEY} 的拒写记录。
     */
    public List<Candidate> candidates() {
        return facts == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(facts));
    }
}
