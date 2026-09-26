package org.agent.memory;

import org.agent.memory.model.FactKey;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * 复述验证：判断一条候选事实是不是<b>用户在这一轮原话里真的说过</b>的（纯函数）。
 *
 * <h2>它挡的是什么</h2>
 * "让模型自动记住用户说的话"这句话里，<b>危险的不是"记"，是"让模型决定记什么"</b>。
 * 模型的失败模式不是"漏记"，而是<b>合理地推测</b>：用户问了一句"大额存单利率多少"，
 * 模型完全可以推出"此人关注大额存单"，然后把它当事实记下来 —— 于是这个推测
 * 会进入<b>未来所有会话</b>的上下文，还带着"用户自述"的可信度。
 * 所以本类只要一件事：<b>要么找到原话里的证据，要么扔掉。</b>
 *
 * <h2>判据锚在"用户原话"上，不在答案上</h2>
 * 与 {@code NumericGuardrailAdvisor}「数字必须有原文出处」是同一套引用归因方法论。
 * 更严的一步在 {@code DistillRequest} 那一层：蒸馏的输入里<b>根本不放答案</b>，
 * 只有用户原话 + 业务日期 + 已知事实。模型看不见答案，就没有"把答案记下来"这条路 ——
 * 本类只是第二道锁。
 *
 * <h2>四步，全过才算过（顺序即优先级，先便宜的、先确定的）</h2>
 * <ol>
 *   <li><b>有原话</b> —— 空原话直接不过（否则下一步的 substring 判定会退化成永远为真）</li>
 *   <li><b>键许可</b> —— 原话须含该键 {@link FactKey#licenseTerms()} 中至少一个词。
 *       挡的是"模型换了个说法硬塞"：用户没提过任何与"网点"有关的词，就不该冒出"常用机构"</li>
 *   <li><b>自述信号</b> —— <b>让这个验证真能失败的那一刀</b>，见下</li>
 *   <li><b>值要素</b> —— {@code value} 按 {@code /} 切分后，每个元素都须是归一化原话的子串</li>
 * </ol>
 *
 * <h2>⚠️ 第 3 步才是那条判据，第 4 步不是</h2>
 * 对抗判决（本类的核心用例）：
 * <pre>
 *   用户原话：「大额存单利率多少」
 *   模型候选：偏好产品 = 大额存单
 *   值要素：是子串 ⇒ 第 4 步<b>过</b>
 *   自述信号：原话里没有"我" ⇒ 第 3 步<b>不过</b> ⇒ 整体拒
 * </pre>
 * 只看第 4 步（"值出现过"）是一条<b>看起来像验证、实际不做任何事</b>的检查 ——
 * 值当然出现过，它就是从原话里抄的。它只挡得住"模型凭空编一个原话里没有的值"，
 * 而那种失败模式模型几乎不会犯。<b>真正的失败模式是"用户提了一句 ≠ 用户自述了一条事实"</b>，
 * 那正是第 3 步在管的事：<b>事实必须是关于这个人的持久事实，所以它天然要求自述，
 * 而不是询问。</b>
 *
 * <h2>⚠️ 代价（明确记下，不是疏漏）</h2>
 * 助手问「对公还是对私？」，用户答「对公」—— 极短的回答里没有自述信号，会被拒。
 * <b>少记</b>。这是保守方向的损失，接受（RAG-07 缺口 5）。
 */
public final class FactGrounding {

    /** 主语到自述信号之间的最大距离（字符）。见类注释里"我这个人呢，其实平时…"那个例子 */
    private static final int MAX_GAP = 20;

    private static final String PRONOUN = "(我|本人|咱|我们)";
    /**
     * 排除"我"后面紧跟别人的情形 —— 这是把"我同事主要做对公"判掉的那一处。
     * <p>
     * ⚠️ 那个 {@code 们?} 不是可有可无的：{@code 我} 这个分支会<b>先</b>匹配上
     * "我们客户"里的"我"，于是回溯时可以把"们客户"当成普通的 {@code .} 吃掉，
     * 让"我们客户主要做对公"蒙混过关。把"们"并进排除项之后，
     * 两个分支（"我" 与 "我们"）在"我们客户"上<b>都</b>被挡住 —— 正则的分支顺序
     * 挡不住回溯，只有把两种写法写进同一个 lookahead 才挡得住。
     */
    private static final String NOT_OTHER_PERSON = "(?!们?(?:同事|朋友|客户|他|她|领导|师傅))";
    private static final String SIGNAL = "(主要|平时|一般|经常|一直|长期|通常|负责|关注|常查|偏好|喜欢|习惯)";

    /**
     * P2 专用的信号：<b>只收时间/频率副词</b>，比 P1 的那张表窄得多。
     * <p>
     * ⚠️ 这里刻意<b>不</b>复用 {@link #SIGNAL}，否则会开一个大洞。汉语里能出现在主语
     * <b>之前</b>的状语基本只有时间/频率副词（"平时我都跑 0100"）。若把"主要/负责/关注"
     * 也放进来，P2 就会匹配上<b>跨小句</b>的组合：
     * <pre>
     *   「我同事<b>主要</b>做对公，<b>我</b>不太熟」  ← 关于同事的一句话，却被判成自述
     * </pre>
     * 更糟的是这类假阳性很难在代码上堵住：{@code forComparison} 会把逗号抹掉，
     * 小句边界在那时就没了。所以正确的做法不是"加一条排除规则"，而是
     * <b>把 P2 限制在它真正要覆盖的那一种句式上</b> —— 这样跨小句的匹配根本没有机会成立。
     */
    private static final String PRE_SUBJECT_ADVERB = "(平时|一般|通常|一直|长期|经常)";

    /** 计划里的 P1：主语在前、自述信号在后（"我主要做对公"） */
    private static final Pattern PRONOUN_THEN_SIGNAL =
            Pattern.compile(PRONOUN + NOT_OTHER_PERSON + ".{0," + MAX_GAP + "}?" + SIGNAL);

    /** 计划里的 P2（反向同款）：时间状语在前、主语在后（"平时我都跑 0100 网点"） */
    private static final Pattern SIGNAL_THEN_PRONOUN =
            Pattern.compile(PRE_SUBJECT_ADVERB + ".{0," + MAX_GAP + "}?" + PRONOUN + NOT_OTHER_PERSON);

    /** 值要素的最小长度：单个字符太容易"碰巧是子串"，不构成证据 */
    private static final int MIN_ELEMENT_CHARS = 2;

    private FactGrounding() {
    }

    /** 没过的那一步。用于日志与测试定位 —— 对外只有"过/不过"，细节不上审计（见 {@code MemoryChangeAction}） */
    public enum Step {
        /** 用户原话为空 */
        NO_UTTERANCE,
        /** 原话里没有任何该键的许可词 */
        LICENSE_TERM,
        /** 原话里没有自述信号（这是最常命中的一步） */
        SELF_NARRATION,
        /** 值里有元素没在原话里出现过 */
        VALUE_ELEMENT
    }

    /**
     * @param grounded 是否通过
     * @param failedAt 失败的那一步；通过时为 {@code null}
     */
    public record Verdict(boolean grounded, Step failedAt) {

        static Verdict pass() {
            return new Verdict(true, null);
        }

        static Verdict fail(Step step) {
            return new Verdict(false, step);
        }
    }

    /**
     * 四步全过才算通过。
     *
     * @param key         已解析出的受控词表键（调用方负责 {@code FactKey.resolve}）
     * @param value       <b>存储形态</b>的值（已过 {@code FactValueNormalizer.forStorage}），
     *                    因为第 4 步要按 {@code /} 切分
     * @param userMessage 该轮用户原话
     */
    public static Verdict check(FactKey key, String value, String userMessage) {
        // ① 有原话
        String utterance = FactValueNormalizer.forComparison(userMessage);
        if (utterance.isEmpty()) {
            return Verdict.fail(Step.NO_UTTERANCE);
        }

        // ② 键许可：原话里得有"允许把这个键归给他"的词
        if (Arrays.stream(key.licenseTerms())
                .noneMatch(term -> utterance.contains(FactValueNormalizer.forComparison(term)))) {
            return Verdict.fail(Step.LICENSE_TERM);
        }

        // ③ 自述信号：这一刀让验证真的能失败，见类注释
        if (!PRONOUN_THEN_SIGNAL.matcher(utterance).find()
                && !SIGNAL_THEN_PRONOUN.matcher(utterance).find()) {
            return Verdict.fail(Step.SELF_NARRATION);
        }

        // ④ 值要素逐项出现在原话里
        //    注意顺序：先按 / 切分，再逐段归一化。反过来的话分隔符本身会被
        //    forComparison 抹掉，就切不开了
        boolean checkedAny = false;
        for (String element : value.split(String.valueOf(FactValueNormalizer.MULTI_SEPARATOR), -1)) {
            String normalized = FactValueNormalizer.forComparison(element);
            if (FactValueNormalizer.charCount(normalized) < MIN_ELEMENT_CHARS) {
                continue;
            }
            checkedAny = true;
            if (!utterance.contains(normalized)) {
                return Verdict.fail(Step.VALUE_ELEMENT);
            }
        }
        // 所有元素都太短（例如值只有单个字）⇒ 等于什么都没验 ⇒ 不算过。
        // 否则"值 = 是"这种候选会靠"没有可检的元素"蒙混过关
        return checkedAny ? Verdict.pass() : Verdict.fail(Step.VALUE_ELEMENT);
    }
}
