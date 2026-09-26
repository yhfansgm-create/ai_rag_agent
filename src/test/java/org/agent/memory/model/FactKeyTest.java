package org.agent.memory.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 受控词表的单测。
 * <p>
 * 重点不是"能查到一个键"，而是<b>词表的边界行为</b>：不认识的不猜、别名不包含匹配、
 * 以及 {@code FORBIDDEN} 的键<b>仍然留在词表里</b>（那是一条刻意的设计决定，见下面 ★ 用例）。
 */
class FactKeyTest {

    @Nested
    @DisplayName("resolve：模型可能给的三种写法都要认")
    class Resolve {

        @Test
        @DisplayName("按 code 解析")
        void byCode() {
            assertThat(FactKey.resolve("business_line")).contains(FactKey.BUSINESS_LINE);
        }

        @Test
        @DisplayName("按 code 解析时大小写不敏感（模型抄出 Business_Line 很常见）")
        void codeIsCaseInsensitive() {
            assertThat(FactKey.resolve("Business_Line")).contains(FactKey.BUSINESS_LINE);
        }

        @Test
        @DisplayName("★ 按中文 label 解析 —— 提示词里渲染给模型看的就是 label，只认 code 会导致「永远不写」")
        void byLabel() {
            assertThat(FactKey.resolve("业务条线")).contains(FactKey.BUSINESS_LINE);
            assertThat(FactKey.resolve(" 常用机构 ")).contains(FactKey.PREFERRED_ORG);
        }

        @Test
        @DisplayName("按别名解析")
        void byAlias() {
            assertThat(FactKey.resolve("常用网点")).contains(FactKey.PREFERRED_ORG);
        }

        @Test
        @DisplayName("不认识就返回空，绝不猜「最接近的一个」—— 猜错的键会静默污染 supersede")
        void unknownIsEmpty() {
            assertThat(FactKey.resolve("user_favourite_color")).isEmpty();
            assertThat(FactKey.resolve("业务线条")).isEmpty();
        }

        @Test
        @DisplayName("null / 空白 → 空")
        void nullAndBlankAreEmpty() {
            assertThat(FactKey.resolve(null)).isEmpty();
            assertThat(FactKey.resolve("   ")).isEmpty();
        }

        @Test
        @DisplayName("别名只做整串匹配，不做包含匹配")
        void aliasDoesNotMatchSubstring() {
            // "业务线" 是 BUSINESS_LINE 的别名，但它不该命中一个更长的、语义不同的串
            assertThat(FactKey.resolve("业务线相关的事")).isEmpty();
        }
    }

    @Nested
    @DisplayName("tier：档位决定能不能自动写")
    class TierRules {

        @Test
        @DisplayName("四个 AUTO 键可自动写，且 TTL 全部 > 0（真正永久稳定的事实在 L5 权限画像里，不在这）")
        void autoKeysAreWritableAndHaveTtl() {
            for (FactKey key : FactKey.values()) {
                if (key.tier() == FactKey.Tier.AUTO) {
                    assertThat(key.autoWritable()).as(key.code()).isTrue();
                    assertThat(key.ttlDays()).as(key.code()).isPositive();
                }
            }
        }

        @Test
        @DisplayName("CONFIRM 与 FORBIDDEN 都不可自动写（本阶段无确认流程 ⇒ 两者的结果一样，只有理由不同）")
        void nonAutoKeysAreNotWritable() {
            assertThat(FactKey.WATCHED_CLIENT.autoWritable()).isFalse();
            assertThat(FactKey.RATING_CONCLUSION.autoWritable()).isFalse();
            assertThat(FactKey.AMOUNT_THRESHOLD.autoWritable()).isFalse();
        }

        @Test
        @DisplayName("★ FORBIDDEN 的键必须能被 resolve 到 —— 否则它会退化成 UNKNOWN_KEY，两种截然不同的含义被混为一谈")
        void forbiddenKeysStayInTheVocabulary() {
            // 这条锁的是 FactKey 类注释里那段设计理由：
            //   删掉 FORBIDDEN 的键 ⇒ 模型提"金额阈值"时被判「不认识这个词」；
            //   留着 ⇒ 被判「这个类别我不该记」。
            // 两者对模型的约束力、对阶段 4 的分析价值都完全不同。
            assertThat(FactKey.resolve("amount_threshold")).contains(FactKey.AMOUNT_THRESHOLD);
            assertThat(FactKey.resolve("金额阈值")).contains(FactKey.AMOUNT_THRESHOLD);
        }

        @Test
        @DisplayName("只有 FOCUS_TOPIC 允许挂 ref_doc_id 指针（主题天然对应某份制度）")
        void onlyFocusTopicAllowsRefDoc() {
            assertThat(FactKey.FOCUS_TOPIC.refDocAllowed()).isTrue();
            assertThat(FactKey.BUSINESS_LINE.refDocAllowed()).isFalse();
            assertThat(FactKey.PREFERRED_PRODUCT.refDocAllowed()).isFalse();
        }
    }

    @Nested
    @DisplayName("renderForPrompt：词表进提示词时不可能与枚举漂移")
    class Rendering {

        @Test
        @DisplayName("AUTO 档渲染出 code + label + 线索词")
        void autoRendering() {
            String rendered = FactKey.renderForPrompt(FactKey.Tier.AUTO);

            assertThat(rendered).contains("business_line").contains("业务条线").contains("对公");
        }

        @Test
        @DisplayName("只渲染指定的那一档（AUTO 档里不该混进 FORBIDDEN 的键）")
        void renderingIsScopedToOneTier() {
            assertThat(FactKey.renderForPrompt(FactKey.Tier.AUTO))
                    .doesNotContain("amount_threshold");
            assertThat(FactKey.renderForPrompt(FactKey.Tier.FORBIDDEN))
                    .contains("amount_threshold")
                    .doesNotContain("business_line");
        }

        @Test
        @DisplayName("每个键都至少有一个许可词，否则它永远不可能通过复述验证（等于死键）")
        void everyKeyHasLicenseTerms() {
            for (FactKey key : FactKey.values()) {
                assertThat(key.licenseTerms()).as(key.code()).isNotEmpty();
                assertThat(Arrays.stream(key.licenseTerms()).noneMatch(t -> t.isBlank()))
                        .as(key.code() + " 的许可词里有空白项")
                        .isTrue();
            }
        }

        @Test
        @DisplayName("licenseTerms() 返回副本 —— 调用方改不动词表（枚举是不可变的，别从返回的数组上开口子）")
        void licenseTermsAreDefensiveCopy() {
            String[] first = FactKey.BUSINESS_LINE.licenseTerms();
            first[0] = "被改掉了";

            assertThat(FactKey.BUSINESS_LINE.licenseTerms()).contains("对公");
        }
    }
}
