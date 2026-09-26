package org.agent.memory;

import org.agent.memory.model.FactKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复述验证的单测 —— <b>本阶段最重要的一组断言</b>。
 * <p>
 * 这组测试要证明的不是"正常的能过"，而是<b>"该拒的真的会被拒"</b>。
 * 一个"永远不会失败"的验证就是装饰品，而它在代码里长得和真的一样 ——
 * 阶段 1 的 {@code min(写入者密级, 资料密级)} 就是这么一处空操作
 * （在干净数据上恒等于另一个输入，看着像防护，实际只在一个方向起作用）。
 * 所以这里的核心用例是<b>对抗性</b>的：拿最像"成功案例"的失败案例来试。
 */
class FactGroundingTest {

    @Nested
    @DisplayName("通过：用户确实自述了一条持久事实")
    class Passes {

        @Test
        @DisplayName("正例：「我主要做对公业务，帮我看看对公开户流程」→ 业务条线=对公")
        void selfNarrationIsGrounded() {
            FactGrounding.Verdict v = FactGrounding.check(FactKey.BUSINESS_LINE, "对公",
                    "我主要做对公业务，帮我看看对公开户流程");

            assertThat(v.grounded()).isTrue();
            assertThat(v.failedAt()).isNull();
        }

        @Test
        @DisplayName("反向语序也能过：「平时我都跑 0100 网点」→ 常用机构=0100")
        void reverseWordOrderIsGrounded() {
            FactGrounding.Verdict v = FactGrounding.check(FactKey.PREFERRED_ORG, "0100",
                    "平时我都跑 0100 网点，那边业务量大");

            assertThat(v.grounded()).isTrue();
        }

        @Test
        @DisplayName("多值：值按 / 切分，每段都在原话里 → 过")
        void multiValueAllElementsPresent() {
            FactGrounding.Verdict v = FactGrounding.check(FactKey.BUSINESS_LINE, "对公/对私",
                    "我主要是做对公和对私两块业务的");

            assertThat(v.grounded()).isTrue();
        }

        @Test
        @DisplayName("写法差异不影响判定：全角标点 + 中间隔了几个字 → 仍过")
        void punctuationAndGapAreTolerated() {
            FactGrounding.Verdict v = FactGrounding.check(FactKey.BUSINESS_LINE, "对公",
                    "我这个人呢，其实平时主要是做对公的");

            assertThat(v.grounded()).isTrue();
        }

        @Test
        @DisplayName("「我们」算自述：「我们组主要做对公」→ 过（我们包含说话人本人）")
        void pluralPronounCountsAsSelfNarration() {
            FactGrounding.Verdict v = FactGrounding.check(FactKey.BUSINESS_LINE, "对公",
                    "我们组主要做对公");

            assertThat(v.grounded()).isTrue();
        }
    }

    @Nested
    @DisplayName("拒绝：这一步必须真的能失败")
    class Rejects {

        @Test
        @DisplayName("★ 对抗判决：一次提问不构成关注 —— 「大额存单利率多少」≠ 偏好产品=大额存单")
        void aQuestionIsNotAFact() {
            // 这是本类的核心用例，也是「模型推测」最真实的形态：
            // 用户只是问了一句，模型推出"此人关注大额存单"，而这个推测一旦落库，
            // 就会进入**未来所有会话**的上下文，还带着"用户自述"的可信度。
            //
            // ⚠️ 注意：值「大额存单」**确实是原话的子串** —— 第 4 步（值要素）会放过它。
            //    拦住它的是第 3 步（自述信号）。把这里换成只查子串的实现，测试就会变红。
            FactGrounding.Verdict v = FactGrounding.check(FactKey.PREFERRED_PRODUCT, "大额存单",
                    "大额存单利率多少");

            assertThat(v.grounded()).isFalse();
            assertThat(v.failedAt())
                    .as("原话里没有自述信号，而值本身是原话的子串 —— "
                            + "所以只有「自述信号」这一步能拦住它")
                    .isEqualTo(FactGrounding.Step.SELF_NARRATION);
        }

        @Test
        @DisplayName("说的是别人不是自己：「我同事主要做对公」→ 拒")
        void thirdPartyIsNotSelf() {
            FactGrounding.Verdict v = FactGrounding.check(FactKey.BUSINESS_LINE, "对公",
                    "我同事主要做对公，我不太熟");

            assertThat(v.grounded()).isFalse();
            assertThat(v.failedAt()).isEqualTo(FactGrounding.Step.SELF_NARRATION);
        }

        @Test
        @DisplayName("「我们客户」也是别人：正则回溯不能把它放进来 → 拒")
        void pluralPlusThirdPartyIsNotSelf() {
            // 这一条专门锁「我」与「我们」两个分支的回溯行为：若排除项只写「客户」不写「们?客户」，
            // 那么匹配「我」之后可以把「们客户」当普通字符吃掉，从而误判为通过
            FactGrounding.Verdict v = FactGrounding.check(FactKey.BUSINESS_LINE, "对公",
                    "我们客户主要做对公");

            assertThat(v.grounded()).isFalse();
            assertThat(v.failedAt()).isEqualTo(FactGrounding.Step.SELF_NARRATION);
        }

        @Test
        @DisplayName("反向模式不得跨小句：「我同事负责对公，我不太熟」→ 拒（回归）")
        void reversePatternDoesNotSpanClauses() {
            // 回归用例：反向模式（状语在前）曾把 SIGNAL 整表都用上，于是它匹配到了
            // 「…负责对公，我不太熟」这种**跨小句**的组合 —— 而那是关于同事的一句话。
            // 逗号在 forComparison 里被抹掉了，所以小句边界在正则那一层已经不存在，
            // 只能靠「P2 只收时间副词」这条限制来杜绝。删掉那条限制，本条会变红。
            FactGrounding.Verdict v = FactGrounding.check(FactKey.BUSINESS_LINE, "对公",
                    "我同事负责对公，我不太熟");

            assertThat(v.grounded()).isFalse();
            assertThat(v.failedAt()).isEqualTo(FactGrounding.Step.SELF_NARRATION);
        }

        @Test
        @DisplayName("转述朋友的说法：「我朋友说对公好做」→ 拒")
        void hearsayIsNotSelf() {
            FactGrounding.Verdict v = FactGrounding.check(FactKey.BUSINESS_LINE, "对公",
                    "我朋友说对公好做");

            assertThat(v.grounded()).isFalse();
        }

        @Test
        @DisplayName("原话里没有该键的许可词 → 拒在许可那一步（模型换了个说法硬塞）")
        void missingLicenseTermIsRejected() {
            FactGrounding.Verdict v = FactGrounding.check(FactKey.BUSINESS_LINE, "对公",
                    "我平时挺忙的，你先帮我查查这个");

            assertThat(v.grounded()).isFalse();
            assertThat(v.failedAt()).isEqualTo(FactGrounding.Step.LICENSE_TERM);
        }

        @Test
        @DisplayName("值是模型编的（原话里没有）→ 拒在值要素那一步")
        void fabricatedValueIsRejected() {
            FactGrounding.Verdict v = FactGrounding.check(FactKey.BUSINESS_LINE, "对私",
                    "我主要做对公业务");

            assertThat(v.grounded()).isFalse();
            assertThat(v.failedAt()).isEqualTo(FactGrounding.Step.VALUE_ELEMENT);
        }

        @Test
        @DisplayName("多值里只有一段出现过 → 仍然拒（不能只验一部分）")
        void partiallyPresentMultiValueIsRejected() {
            FactGrounding.Verdict v = FactGrounding.check(FactKey.BUSINESS_LINE, "对公/同业",
                    "我主要是做对公业务的");

            assertThat(v.grounded()).isFalse();
            assertThat(v.failedAt()).isEqualTo(FactGrounding.Step.VALUE_ELEMENT);
        }

        @Test
        @DisplayName("空原话 → 拒（否则子串判定会退化成永远为真）")
        void emptyUtteranceIsRejected() {
            assertThat(FactGrounding.check(FactKey.BUSINESS_LINE, "对公", "").failedAt())
                    .isEqualTo(FactGrounding.Step.NO_UTTERANCE);
            assertThat(FactGrounding.check(FactKey.BUSINESS_LINE, "对公", null).failedAt())
                    .isEqualTo(FactGrounding.Step.NO_UTTERANCE);
        }

        @Test
        @DisplayName("值是单字 → 拒（没有可检的元素等于什么都没验，不能靠它蒙混过关）")
        void singleCharValueIsRejected() {
            FactGrounding.Verdict v = FactGrounding.check(FactKey.BUSINESS_LINE, "是",
                    "我主要做对公业务");

            assertThat(v.grounded()).isFalse();
            assertThat(v.failedAt()).isEqualTo(FactGrounding.Step.VALUE_ELEMENT);
        }

        @Test
        @DisplayName("代价（明确的取舍）：助手问「对公还是对私」、用户答「对公」→ 拒")
        void shortAnswerWithoutSelfNarrationIsRejectedAsAcceptedCost() {
            // 这不是 bug，是被接受的代价（RAG-07 缺口 5）：极短回答里没有自述信号。
            // 把它写成一条测试，是为了让"少记"这个副作用**看得见** ——
            // 否则将来有人发现"用户明明说了却记不住"会以为是实现错误，
            // 然后"顺手"把自述信号那一步放松掉。
            FactGrounding.Verdict v = FactGrounding.check(FactKey.BUSINESS_LINE, "对公",
                    "对公");

            assertThat(v.grounded()).isFalse();
            assertThat(v.failedAt()).isEqualTo(FactGrounding.Step.SELF_NARRATION);
        }
    }
}
