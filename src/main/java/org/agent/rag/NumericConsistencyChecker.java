package org.agent.rag;

import lombok.extern.slf4j.Slf4j;
import org.agent.rag.NumericExtractor.Mention;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数字一致性校验：答案里出现的每个金额／比例／期限，必须能在检索到的原文里找到出处。
 * <p>
 * 纯函数组件，形态与 {@link TemporalGate} 一致 —— 无状态、可直接 {@code new} 出来测。
 * 提取与归一化在 {@link NumericExtractor}，这里只做集合差。
 *
 * <h2>比较的是归一化后的 key，不是字面量</h2>
 * 模型把原文的「5 万元」复述成「50000元」是**允许**的（同值同量纲），
 * 所以两边都先归一化再比。反过来，「5万元」被写成「5元」会被抓住 —— 这正是要拦的。
 *
 * <h2>不做跨单位换算</h2>
 * 「3 个工作日」与「3 个自然日」在银行是两个概念，{@link NumericExtractor} 不做换算，
 * 这里也就不会把它们判等。宁可漏过"其实等价但换了单位"的表述，也不能放过换了口径的数字。
 */
@Slf4j
@Component
public class NumericConsistencyChecker {

    /**
     * 校验结论。
     *
     * @param violations 答案里出现、但原文中找不到的数值（按 key 去重，保留首次出现的写法）
     * @param checked    答案里一共提取到多少个数值，用于区分"全对"和"根本没数字"
     */
    public record Result(List<Mention> violations, int checked) {

        public Result {
            violations = List.copyOf(violations);
        }

        public boolean consistent() {
            return violations.isEmpty();
        }

        /** 违规数值的可读描述，用于日志与审计 context */
        public List<String> descriptions() {
            List<String> out = new ArrayList<>(violations.size());
            for (Mention m : violations) {
                out.add(describe(m));
            }
            return out;
        }
    }

    private final NumericExtractor extractor;

    @Autowired
    public NumericConsistencyChecker(NumericExtractor extractor) {
        this.extractor = extractor;
    }

    /** 测试便捷构造：无 Spring 容器时自建提取器（提取器本身无依赖） */
    public NumericConsistencyChecker() {
        this(new NumericExtractor());
    }

    /**
     * 校验答案与原文的数值一致性。
     *
     * @param answer      模型生成的回答，可为 null
     * @param sourceTexts 本次注入 prompt 的原文片段；为空表示无可比对的依据
     */
    public Result check(String answer, List<String> sourceTexts) {
        List<Mention> answerMentions = extractor.extract(answer);
        if (answerMentions.isEmpty()) {
            return new Result(List.of(), 0);
        }

        Set<String> sourceKeys = new HashSet<>();
        if (sourceTexts != null) {
            for (String text : sourceTexts) {
                sourceKeys.addAll(extractor.keys(text));
            }
        }

        // LinkedHashMap 去重：同一句话里重复出现的同一个数字只记一条，但报的是首次出现的原文写法
        Map<String, Mention> violations = new LinkedHashMap<>();
        for (Mention m : answerMentions) {
            if (!sourceKeys.contains(m.key())) {
                violations.putIfAbsent(m.key(), m);
            }
        }
        return new Result(List.copyOf(violations.values()), answerMentions.size());
    }

    /** 人类可读的归一化结果，例如 {@code 5 万元 → 50000元}、{@code 30% → 30%} */
    static String describe(Mention m) {
        return m.raw() + " → " + m.value().stripTrailingZeros().toPlainString() + m.unit();
    }
}
