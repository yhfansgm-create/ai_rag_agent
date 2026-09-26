package org.agent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内容红线的单测。
 * <p>
 * 除了"该拦的拦住了"，这里还有两类别的测试：
 * <ul>
 *   <li><b>顺序</b>：判定取第一个命中的模式，所以模式表的排列本身是逻辑的一部分</li>
 *   <li><b>返回值形状</b>：{@code Hit} 里<b>不允许</b>出现原值 —— 用反射把这条钉死，
 *       因为它是"凭证不会被抄进日志/审计/异常"这个承诺的<b>唯一</b>实现依据</li>
 * </ul>
 */
class FactRedlinesTest {

    /** 一个结构上像样的 18 位身份证号（含出生日期段，能通过 ID_CARD 的结构校验） */
    private static final String ID_CARD = "110101199003078515";
    /** 19 位银行卡号 */
    private static final String BANK_CARD = "6222021234567890123";
    private static final String MOBILE = "13812345678";

    @Nested
    @DisplayName("命中：五类凭证各一条")
    class Hits {

        @Test
        @DisplayName("身份证号")
        void idCard() {
            assertThat(scanPattern(ID_CARD)).contains("ID_CARD");
        }

        @Test
        @DisplayName("银行卡号（16 位与 19 位两端各测一次）")
        void bankCard() {
            assertThat(scanPattern("6222021234567890")).contains("BANK_CARD");
            assertThat(scanPattern(BANK_CARD)).contains("BANK_CARD");
        }

        @Test
        @DisplayName("手机号")
        void mobile() {
            assertThat(scanPattern(MOBILE)).contains("MOBILE");
        }

        @Test
        @DisplayName("口令类关键词（不需要跟着具体值 —— 值里出现这个词本身就说明这条事实在谈口令）")
        void credentialKeyword() {
            assertThat(scanPattern("取款密码")).contains("CREDENTIAL_KEYWORD");
            assertThat(scanPattern("my password is 1234")).contains("CREDENTIAL_KEYWORD");
            assertThat(scanPattern("登录密码问题")).contains("CREDENTIAL_KEYWORD");
        }

        @Test
        @DisplayName("API Key / 令牌（字面量前缀与英文关键词两种形态）")
        void apiKey() {
            assertThat(scanPattern("sk-abcdefgh12345678")).contains("API_KEY");
            assertThat(scanPattern("api_key")).contains("API_KEY");
            assertThat(scanPattern("bearer")).contains("API_KEY");
        }

        @Test
        @DisplayName("值里混着文字也能命中（find 而不是 matches，不要求整串是数字）")
        void embeddedInText() {
            assertThat(scanPattern("这个客户经理的号是 " + MOBILE)).contains("MOBILE");
        }
    }

    @Nested
    @DisplayName("顺序：模式表「先具体后宽泛」是逻辑的一部分")
    class Ordering {

        @Test
        @DisplayName("★ 18 位身份证号同时满足「16~19 位连续数字」，靠排列顺序才能报对模式名")
        void idCardWinsOverBankCard() {
            // 结论（拒写）两种顺序下都一样，但**审计里的 pattern 字段**不一样：
            // 报成 BANK_CARD 会让人以为用户给的是卡号，那是错误的分诊信息。
            assertThat(scanPattern(ID_CARD)).contains("ID_CARD");
        }

        @Test
        @DisplayName("枚举声明顺序就是这个优先级（顺序是承重的，改动必须是有意识的）")
        void enumOrderIsDeclaredSpecificFirst() {
            assertThat(FactRedlines.Rule.values())
                    .startsWith(FactRedlines.Rule.ID_CARD, FactRedlines.Rule.BANK_CARD);
        }
    }

    @Nested
    @DisplayName("不命中：不能把正常业务值吃掉")
    class Misses {

        @Test
        @DisplayName("普通事实值放行")
        void normalValues() {
            assertThat(FactRedlines.scan("对公")).isEmpty();
            assertThat(FactRedlines.scan("大额存单")).isEmpty();
            assertThat(FactRedlines.scan("客户经理")).isEmpty();
        }

        @Test
        @DisplayName("★ 机构号「0100」不该被误判成银行卡号 —— 业务值是数字不等于它是凭证")
        void orgCodeIsNotABankCard() {
            assertThat(FactRedlines.scan("0100")).isEmpty();
            assertThat(FactRedlines.scan("0100/0200")).isEmpty();
        }

        @Test
        @DisplayName("空值放行（空是「值形态」那一步的事，不是红线的事）")
        void emptyValues() {
            assertThat(FactRedlines.scan(null)).isEmpty();
            assertThat(FactRedlines.scan("")).isEmpty();
        }
    }

    @Nested
    @DisplayName("★ 返回值形状本身是安全设计")
    class ResultShape {

        @Test
        @DisplayName("Hit 里只有 pattern 与 valueLength —— 把「看不到」做成类型上就取不到")
        void hitCarriesNoMatchedText() {
            // 这条用反射锁住字段集合。将来若有人为了"方便排查"往 Hit 里加一个 matchedText，
            // 这条测试会变红 —— 而那个改动的后果是三处同时开始泄露凭证：
            // 拒写日志、审计表的 after_json、异常信息。
            List<String> componentNames = Arrays.stream(FactRedlines.Hit.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(componentNames).containsExactly("pattern", "valueLength");
        }

        @Test
        @DisplayName("Hit.toString() 里也不含原值（record 的 toString 由字段拼出，字段里没有它就不会有）")
        void toStringDoesNotLeakValue() {
            Optional<FactRedlines.Hit> hit = FactRedlines.scan("卡号 " + BANK_CARD);

            assertThat(hit).isPresent();
            assertThat(hit.get().toString()).doesNotContain(BANK_CARD);
        }

        @Test
        @DisplayName("valueLength 是整条值的长度，不是命中片段的长度（用途是分诊，不是定位）")
        void valueLengthIsTheWholeValue() {
            String value = "负责人 " + MOBILE;

            FactRedlines.Hit hit = FactRedlines.scan(value).orElseThrow();

            assertThat(hit.valueLength())
                    .as("整条值：负责人(3) + 空格(1) + 手机号(11)")
                    .isEqualTo(15);
            assertThat(hit.valueLength())
                    .as("不是命中片段（11 位数字）的长度 —— 两者必须能区分开，"
                            + "否则这个字段既暴露了「命中了多长的数字」，又做不了分诊")
                    .isNotEqualTo(11);
        }
    }

    /** 只关心"命中了哪个模式"时的简写 —— 那正是绝大多数断言的关注点 */
    private static Optional<String> scanPattern(String value) {
        return FactRedlines.scan(value).map(FactRedlines.Hit::pattern);
    }
}
