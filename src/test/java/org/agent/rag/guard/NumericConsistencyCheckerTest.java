package org.agent.rag.guard;

import org.agent.rag.guard.NumericConsistencyChecker.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数字一致性比对测试。
 * <p>
 * 核心是两类断言：**同值不同写法要放过**（否则正常回答被误拦），
 * **不同值或不同口径要抓住**（否则这道防线没有意义）。
 */
class NumericConsistencyCheckerTest {

    private final NumericConsistencyChecker checker = new NumericConsistencyChecker();

    /** 原文取自 kb-test-data/valid/teller-柜面大额存取款业务规范-密级2.md 的写法 */
    private static final String SOURCE =
            "三、授权流程\n"
                    + "3.1 单笔取现 5 万元（含）以上、单笔存款 20 万元（含）以上的，须经网点主管授权。\n"
                    + "3.2 授权后应在 3 个工作日内完成复核。\n"
                    + "四、利率\n"
                    + "智存宝 3个月档年利率 1.25%。";

    private Result check(String answer) {
        return checker.check(answer, List.of(SOURCE));
    }

    @Test
    @DisplayName("原文里的数字原样复述 → 通过")
    void verbatimPasses() {
        Result r = check("单笔取现 5 万元（含）以上须经网点主管授权[1]。");

        assertThat(r.consistent()).isTrue();
        assertThat(r.checked()).isEqualTo(1);
    }

    @Test
    @DisplayName("同值换个单位写法仍通过 —— 5 万元 与 50000元 是一个数")
    void equivalentRewritingPasses() {
        assertThat(check("取现 50000 元（含）以上需主管授权").consistent()).isTrue();
        assertThat(check("取现 5万元（含）以上需主管授权").consistent()).isTrue();
        // 比例同理：百分之三十 = 30%
        assertThat(checker.check("年利率 百分之三十", List.of("年利率 30%")).consistent()).isTrue();
    }

    @Test
    @DisplayName("数字被改 → 判违规（这是这个功能存在的全部理由）")
    void alteredNumberIsCaught() {
        Result r = check("单笔取现 10 万元（含）以上须经主管授权。");

        assertThat(r.consistent()).isFalse();
        assertThat(r.violations()).singleElement()
                .satisfies(m -> assertThat(m.key()).isEqualTo("MONEY|100000|元"));
    }

    @Test
    @DisplayName("改写口径也判违规 —— 3 个工作日 不是 3 个自然日")
    void changedUnitIsCaught() {
        Result r = check("授权后应在 3 个自然日内完成复核。");

        assertThat(r.consistent()).isFalse();
        assertThat(r.violations()).singleElement()
                .satisfies(m -> assertThat(m.unit()).isEqualTo("自然日"));
    }

    @Test
    @DisplayName("凭空编造的数字被抓住，原文里的数字不受牵连")
    void fabricatedNumberIsCaught() {
        Result r = check("单笔取现 5 万元（含）以上须经主管授权，限额最高 8 万元。");

        assertThat(r.violations()).singleElement()
                .satisfies(m -> assertThat(m.key()).isEqualTo("MONEY|80000|元"));
        assertThat(r.checked()).isEqualTo(2);
    }

    @Test
    @DisplayName("同一个数字在答案里重复出现，只记一条违规（保留首次出现的写法）")
    void duplicatesAreDeduped() {
        Result r = check("限额 8 万元。再次强调：限额 8 万元。另见 80000 元。");

        assertThat(r.violations()).hasSize(1);
    }

    @Test
    @DisplayName("多个原文片段合并成一个出处池")
    void multipleSourcesAreMerged() {
        Result r = checker.check("限额 5 万元，比例 30%",
                List.of("限额 5 万元", "比例 30%"));

        assertThat(r.consistent()).isTrue();
    }

    @Test
    @DisplayName("原文为空 → 答案里的数值全部无出处")
    void emptySourcesMeansEverythingIsUnverified() {
        Result r = checker.check("限额 5 万元", List.of());

        assertThat(r.consistent()).isFalse();
        assertThat(r.violations()).hasSize(1);
    }

    @Test
    @DisplayName("答案里没有可核对数值 → 通过，且 checked=0 便于区分「全对」与「无数字」")
    void answerWithoutNumbersPasses() {
        Result r = check("请携带本人有效身份证件前往网点办理。");

        assertThat(r.consistent()).isTrue();
        assertThat(r.checked()).isZero();
    }

    @Test
    @DisplayName("答案为空/为 null 不抛异常")
    void blankAnswer() {
        assertThat(check(null).consistent()).isTrue();
        assertThat(check("").consistent()).isTrue();
    }

    @Test
    @DisplayName("违规描述是人类可读的「原文 → 归一化值」，供日志与审计回显")
    void descriptionsAreReadable() {
        Result r = check("利率按 30% 执行，期限 3 个自然日。");

        assertThat(r.descriptions()).containsExactlyInAnyOrder(
                "30% → 30%", "3 个自然日 → 3自然日");
    }

    @Test
    @DisplayName("模型复述提问里的数字但原文没有 → 判违规（不附和用户给出的错误前提）")
    void echoingUserPremiseIsCaught() {
        // 用户问「50 万元取现要授权吗」，原文只规定到 5 万元 / 20 万元两档；
        // 模型顺着用户的说法答了 50 万 → 这个数在原文里没有出处
        Result r = check("50 万元取现需要网点主管授权。");

        assertThat(r.consistent()).isFalse();
        assertThat(r.violations()).singleElement()
                .satisfies(m -> assertThat(m.key()).isEqualTo("MONEY|500000|元"));
    }
}
