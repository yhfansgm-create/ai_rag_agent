package org.agent.memory;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 内容红线：事实值里一旦出现凭证类内容，整条候选直接拒写（纯函数）。
 *
 * <h2>⚠️ 本类的返回值形状是<b>安全设计</b>，不是接口洁癖</h2>
 * {@link #scan(String)} 返回的是"命中了哪个模式 + 值有多长"，<b>永远不返回命中的文本</b>。
 * 这样设计是因为它下面三处都得用到这个结果，而三处都不该看到原值：
 * <ul>
 *   <li>拒写日志 —— 记下被拒的内容等于把凭证又抄进日志文件</li>
 *   <li>审计表 {@code after_json} —— 同样是持久化的泄露面</li>
 *   <li>异常信息 —— 会顺着栈打到日志里</li>
 * </ul>
 * 把"看不到"做成<b>类型上就取不到</b>（返回值里根本没有那个字段），比在四处各写一遍
 * "记得别打日志"可靠得多。这与 {@code ChatMessageDao}「不提供按权限字段过滤的查询」
 * 是同一个手法：<b>与其在注释里写"别这么用"，不如让那个用法不存在</b>。
 *
 * <h2>⚠️ 不复用 {@code CodeSniffer}</h2>
 * 项目里已有一个 {@code CodeSniffer}（输出侧拦代码片段）。<b>不能复用它</b>：
 * 它认的是<b>代码</b>（{@code import}、{@code SELECT *}、控制流）而不是<b>凭证</b>，
 * 两者的模式表没有交集。复用的是它的<b>思路</b> —— 小正则表 + fail-closed + 拒绝即审计。
 *
 * <h2>⚠️ 误报的方向是刻意选的</h2>
 * 模式表偏宽（例如值里只要出现"密码"两个字就拒）。代价是<b>少记一条合法事实</b>，
 * 收益是<b>一条凭证不会被长期携带进未来所有会话的 prompt</b>。
 * 这条取舍与 {@code NumericExtractor} 恰好相反 —— 那个宁可漏报也不能误报，
 * 因为它拦的是<b>答案</b>（误报会让正确回答被撤回，用户直接可见）；
 * 本类拦的是<b>记忆</b>（误报只是少记一条，用户不可见）。<b>同一个项目里两种方向，
 * 是因为被拦的对象不同，不是因为标准不一致。</b>
 */
public final class FactRedlines {

    /**
     * 红线模式。<b>按"先具体后宽泛"的顺序排列</b> —— 判定用的是第一个命中的模式，
     * 所以 {@link #ID_CARD} 必须排在 {@link #BANK_CARD} 前面：18 位身份证号
     * 也满足"16~19 位连续数字"，先跑宽的那个会把身份证据报成银行卡号，
     * 于是审计里的 {@code pattern} 字段就是错的（虽然拒写的结论一样）。
     */
    public enum Rule {

        /**
         * 身份证号（18 位，含末位校验位 X）。含出生日期的结构校验，
         * 免得把任意 18 位数字都当身份证。
         */
        ID_CARD("(?<!\\d)[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx](?!\\d)"),

        /** 银行卡号（16~19 位连续数字） */
        BANK_CARD("(?<!\\d)\\d{16,19}(?!\\d)"),

        /** 手机号（11 位，1 开头） */
        MOBILE("(?<!\\d)1[3-9]\\d{9}(?!\\d)"),

        /**
         * 口令类关键词。不需要跟着具体值 —— 值里出现这些词本身就说明这条事实在谈口令，
         * 而口令不该进记忆（用户想说也说得含糊，记下来的多半是残片）。
         */
        CREDENTIAL_KEYWORD("(密码|口令|passwd|password|取款密码|登录密码|支付密码)"),

        /**
         * API Key / 令牌类。两种形态：明显的 key 字面量前缀，或英文关键词。
         * ⚠️ 关键词单独成模式会偏宽（值里出现 "token" 就拒），这是刻意的，见类注释。
         */
        API_KEY("(sk-[A-Za-z0-9_\\-]{8,}|(?:api[_-]?key|access[_-]?key|secret[_-]?key|token|bearer))");

        private final Pattern pattern;

        Rule(String regex) {
            this.pattern = Pattern.compile(regex);
        }
    }

    /**
     * 命中结果。<b>只有模式名与长度，没有命中的文本</b> —— 见类注释。
     *
     * @param pattern     命中的模式名（直接进审计的 {@code after_json.pattern}）
     * @param valueLength 整条事实值的字符数（不是命中片段的长度）。
     *                    用途是分诊：知道"被拦下的东西有多长"足够判断严重程度，
     *                    而这个数字<b>不携带内容</b>
     */
    public record Hit(String pattern, int valueLength) {
    }

    private FactRedlines() {
    }

    /**
     * 扫描事实值。
     *
     * @return 命中的第一个模式；没有命中则 {@code empty}
     */
    public static Optional<Hit> scan(String value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        for (Rule rule : Rule.values()) {
            if (rule.pattern.matcher(value).find()) {
                return Optional.of(new Hit(rule.name(), FactValueNormalizer.charCount(value)));
            }
        }
        return Optional.empty();
    }
}
