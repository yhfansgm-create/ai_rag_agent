package org.agent.rag;

import org.agent.rag.NumericExtractor.Mention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数字提取与归一化测试。
 * <p>
 * 这个类的用例分两半：**归一化要认得多**（同一金额的各种写法必须收敛到同一个 key），
 * **误报要压得住**（条款号/日期/引用锚点一个都不能提出来）。
 */
class NumericExtractorTest {

    private final NumericExtractor extractor = new NumericExtractor();

    private List<String> keys(String text) {
        return extractor.extract(text).stream().map(Mention::key).toList();
    }

    /** 断言只提取到一个数值，并把实际提取结果带进失败消息（提取器出问题时一眼看到抽歪了什么） */
    private String onlyKey(String text) {
        List<String> extracted = keys(text);
        assertThat(extracted).as("「%s」应恰好提取到一个数值，实际提取到: %s", text, extracted).hasSize(1);
        return extracted.get(0);
    }

    // ---------- 归一化：金额 ----------

    @Nested
    @DisplayName("金额归一化到「元」")
    class Money {

        @Test
        @DisplayName("万元 折算成元，且与直接写 50000元 得到完全相同的 key")
        void wanYuanEqualsYuan() {
            assertThat(onlyKey("单笔取现 5 万元（含）以上"))
                    .isEqualTo(onlyKey("单笔取现 50000 元（含）以上"))
                    .isEqualTo("MONEY|50000|元");
        }

        @Test
        @DisplayName("千分位逗号与空格都能容忍")
        void toleratesSeparators() {
            assertThat(onlyKey("限额 50,000 元")).isEqualTo("MONEY|50000|元");
        }

        @Test
        @DisplayName("亿元 / 千元 各自折算正确")
        void otherMagnitudes() {
            assertThat(onlyKey("规模 1 亿元")).isEqualTo("MONEY|100000000|元");
            assertThat(onlyKey("计提 3 千元")).isEqualTo("MONEY|3000|元");
        }

        @Test
        @DisplayName("不带「元」的裸 万/亿 也认（语料里写作「5000 万」）")
        void bareMagnitudeUnit() {
            assertThat(onlyKey("注册资本 5000 万")).isEqualTo("MONEY|50000000|元");
        }
    }

    // ---------- 归一化：比例 ----------

    @Nested
    @DisplayName("比例归一化到「百分数」")
    class Percent {

        @Test
        @DisplayName("百分号、千分号、中文「百分之/千分之」三种写法都收敛")
        void allForms() {
            assertThat(onlyKey("年利率 30%")).isEqualTo("PERCENT|30|%");
            assertThat(onlyKey("年利率 百分之三十")).isEqualTo("PERCENT|30|%");
            assertThat(onlyKey("费率 3‰")).isEqualTo("PERCENT|0.3|%");
            assertThat(onlyKey("费率 千分之三")).isEqualTo("PERCENT|0.3|%");
        }

        @Test
        @DisplayName("小数比例不丢精度，且期限与比例在同一句里各归各的维度")
        void decimal() {
            assertThat(onlyKey("年利率 1.25%")).isEqualTo("PERCENT|1.25|%");
            assertThat(keys("存期 3个月，年利率 1.25%"))
                    .containsExactly("DURATION|3|月", "PERCENT|1.25|%");
        }
    }

    // ---------- 归一化：期限 ----------

    @Nested
    @DisplayName("期限只统一别名，不做跨单位换算")
    class Duration {

        @Test
        @DisplayName("「天」和「日」是同一个单位，「个月」和「月」是同一个单位")
        void aliasesCollapse() {
            assertThat(onlyKey("回滚窗口 30 天")).isEqualTo("DURATION|30|日");
            assertThat(onlyKey("回滚窗口 30 日")).isEqualTo("DURATION|30|日");
            assertThat(onlyKey("存期 3 个月")).isEqualTo("DURATION|3|月");
            assertThat(onlyKey("存期 3个月")).isEqualTo("DURATION|3|月");
        }

        @Test
        @DisplayName("「工作日」与「日」**不**判等 —— 银行场景里这是两个概念")
        void workdayIsNotCalendarDay() {
            assertThat(onlyKey("T+3 个工作日")).isEqualTo("DURATION|3|工作日");
            assertThat(onlyKey("T+3 个工作日")).isNotEqualTo(onlyKey("T+3 日"));
        }

        @Test
        @DisplayName("工作年限这类「年」正常识别")
        void years() {
            assertThat(onlyKey("工作年限满 5 年")).isEqualTo("DURATION|5|年");
        }
    }

    // ---------- 中文数字 ----------

    @Nested
    @DisplayName("中文数字（含大写）")
    class Chinese {

        @Test
        @DisplayName("大写金额「伍万元整」等价于 50000 元")
        void uppercaseMoney() {
            assertThat(onlyKey("授权限额伍万元整")).isEqualTo("MONEY|50000|元");
        }

        @Test
        @DisplayName("「三十个工作日」等价于 30 个工作日")
        void chineseDuration() {
            assertThat(onlyKey("审批时限三十个工作日")).isEqualTo("DURATION|30|工作日");
        }

        @Test
        @DisplayName("「三万亿元」切成 三万 + 亿元，不会因量级进位算错")
        void largeMagnitude() {
            assertThat(onlyKey("资产规模三万亿元")).isEqualTo("MONEY|3000000000000|元");
        }

        @Test
        @DisplayName("裸量级词不算数字 —— 「按千元计」「单位为百万元」都不是数值")
        void bareUnitIsNotANumber() {
            assertThat(keys("金额单位：万元")).isEmpty();
            assertThat(keys("费用按千元计")).isEmpty();
            assertThat(keys("金额单位为百万元")).isEmpty();
        }

        @Test
        @DisplayName("「十」开头的量级词仍认：十万 = 100000")
        void shiPrefixedMagnitudeIsANumber() {
            assertThat(onlyKey("授权限额十万元")).isEqualTo("MONEY|100000|元");
        }

        @Test
        @DisplayName("阿拉伯数字的单位里那截中文不会被再认一遍（回归：曾凭空多出 1000 元）")
        void unitInsideArabicMatchIsNotExtractedTwice() {
            assertThat(keys("计提 3 千元")).containsExactly("MONEY|3000|元");
            assertThat(keys("限额 3 千万元")).containsExactly("MONEY|30000000|元");
        }

        @Test
        @DisplayName("「十分」「万一」这类词不会误判成数值")
        void commonWordsAreNotNumbers() {
            assertThat(keys("十分重要，万一出错需立即上报")).isEmpty();
        }
    }

    // ---------- 误报防线 ----------

    @Nested
    @DisplayName("白名单之外的数字一律不提")
    class Noise {

        @Test
        @DisplayName("引用锚点 [1]、条款号、章节号、列表序号、文号、计数")
        void allNoiseForms() {
            assertThat(keys("见资料[1]和[12]")).isEmpty();
            assertThat(keys("依据本办法第五条第2款办理")).isEmpty();
            assertThat(keys("详见 3.2.1 节")).isEmpty();
            assertThat(keys("1. 起存金额由系统控制")).isEmpty();
            assertThat(keys("银保监发〔2019〕23号文")).isEmpty();
            assertThat(keys("按风险程度分为3类")).isEmpty();
        }

        @Test
        @DisplayName("日期整体剔除：带年月日、只带月日、以及裸年份")
        void datesAreDropped() {
            assertThat(keys("自2024年1月1日起施行")).isEmpty();
            assertThat(keys("有效期至 1月1日")).isEmpty();
            assertThat(keys("2024年颁布")).isEmpty();
        }

        @Test
        @DisplayName("日期剔除后不影响同一句里的真实数值")
        void dateAndNumberCoexist() {
            assertThat(keys("自2026年1月1日起，起存金额为 100 元"))
                    .containsExactly("MONEY|100|元");
        }
    }

    // ---------- 边界 ----------

    @Test
    @DisplayName("空输入返回空列表而不是 null（调用方无需判空）")
    void blankInput() {
        assertThat(extractor.extract(null)).isEmpty();
        assertThat(extractor.extract("")).isEmpty();
        assertThat(extractor.extract("   ")).isEmpty();
        assertThat(extractor.extract("本办法无任何数值")).isEmpty();
    }

    @Test
    @DisplayName("raw 保留原文写法，供日志回显")
    void rawIsPreserved() {
        assertThat(extractor.extract("限额 5 万元"))
                .singleElement()
                .satisfies(m -> assertThat(m.raw()).isEqualTo("5 万元"));
    }
}
