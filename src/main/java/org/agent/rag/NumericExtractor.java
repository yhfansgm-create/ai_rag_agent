package org.agent.rag;

import cn.hutool.core.convert.NumberChineseFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数字提取与归一化：从一段文本里抽出「金额 / 比例 / 期限」三类可核对的数值。
 * <p>
 * 这是输出侧数字一致性校验的第一步（第二步见 {@link NumericConsistencyChecker}）。
 * 无状态纯函数，可 {@code new} 出来直接测，形态与 {@link TemporalGate} 一致。
 *
 * <h2>精度主杠杆：只认"数字后面紧跟白名单单位"</h2>
 * 这一条规则同时解决了一大类误报，不需要再写一堆白名单去压：
 * <ul>
 *   <li>引用锚点 {@code [1]} —— 方括号包裹，后面没有单位</li>
 *   <li>条款号 {@code 第五条第2款} —— "款"不是单位</li>
 *   <li>章节号 {@code 3.2.1} —— 后面没有单位</li>
 *   <li>行首列表序号 {@code 1.} —— 后面没有单位</li>
 *   <li>文号 {@code 银保监发〔2019〕23号} —— "号"不是单位</li>
 *   <li>计数 {@code 分为3类} —— "类"不是单位</li>
 * </ul>
 * 白名单之外的数字一律**不提**。宁可漏，不可误报 —— 漏了只是少一道防线，
 * 误报会把本来正确的答案拦掉，直接损害可用性。
 *
 * <h2>唯一需要显式剔除的：日期</h2>
 * 因为「年 / 月 / 日」既是日期成分又是期限单位，是唯一与白名单撞车的类别。
 * 本次校验范围不含日期，所以先把日期整体抹掉（见 {@link #DATE}），
 * 再对裸年份单独做值域判断（{@link #YEAR_MIN}~{@link #YEAR_MAX}）。
 *
 * <h2>归一化</h2>
 * <ul>
 *   <li>金额 → 一律折算到「元」（亿元 ×10⁸、万元 ×10⁴、千元 ×10³）</li>
 *   <li>比例 → 一律折算到「百分数」（{@code 30%}→30，{@code 3‰}→0.3）</li>
 *   <li>期限 → **不做跨单位换算**，只统一别名（天→日、个月→月）。
 *       理由是「3 个工作日」和「3 个自然日」在银行是两个概念，换算会把错误答案放过</li>
 * </ul>
 */
@Slf4j
@Component
public class NumericExtractor {

    /** 校验维度。只有这三类参与核对 —— 计数、序号、条款号不在其中 */
    public enum Dimension {
        MONEY, PERCENT, DURATION
    }

    /**
     * 一个被提取到的数值。
     * <p>
     * {@code value} 与 {@code unit} 是**归一化后**的值，不是原文的写法：
     * {@code 5 万元} 与 {@code 50000 元} 产出的 Mention 完全相同。
     *
     * @param raw   原文片段，仅用于日志和拒答说明，不参与比对
     * @param key   比对用的规范 key
     */
    public record Mention(String raw, Dimension dimension, BigDecimal value, String unit) {

        /**
         * 比对用的规范 key。
         * <p>
         * 用字符串而不是 {@code BigDecimal.equals}：后者把 scale 也算进相等性，
         * {@code 5万} 归一化出的 {@code 5E+4} 与 {@code 50000} 会被判为不等。
         */
        public String key() {
            return dimension + "|" + value.stripTrailingZeros().toPlainString() + "|" + unit;
        }
    }

    /** 单位白名单一项：属于哪个维度、折算倍数、归一化后的单位名 */
    private record UnitSpec(Dimension dimension, BigDecimal multiplier, String canonical) {}

    /** 单位白名单。key 按长度倒序参与正则，保证「万元」先于「万」被匹配到 */
    private static final Map<String, UnitSpec> UNITS = new LinkedHashMap<>();

    private static void money(String unit, String multiplier) {
        UNITS.put(unit, new UnitSpec(Dimension.MONEY, new BigDecimal(multiplier), "元"));
    }

    private static void duration(String unit, String canonical) {
        UNITS.put(unit, new UnitSpec(Dimension.DURATION, BigDecimal.ONE, canonical));
    }

    static {
        // ---- 金额：一律折算到「元」 ----
        money("亿元", "100000000");
        money("千万元", "10000000");
        money("百万元", "1000000");
        money("万元", "10000");
        money("千元", "1000");
        money("元", "1");
        money("圆", "1");
        money("块", "1");
        money("亿", "100000000");
        money("万", "10000");

        // ---- 比例：一律折算到「百分数」。千分号是 ÷10 ----
        UNITS.put("%", new UnitSpec(Dimension.PERCENT, BigDecimal.ONE, "%"));
        UNITS.put("％", new UnitSpec(Dimension.PERCENT, BigDecimal.ONE, "%"));
        UNITS.put("‰", new UnitSpec(Dimension.PERCENT, new BigDecimal("0.1"), "%"));

        // ---- 期限：只统一别名，不做跨单位换算 ----
        duration("个工作日", "工作日");
        duration("工作日", "工作日");
        duration("个自然日", "自然日");
        duration("自然日", "自然日");
        duration("个日历日", "日历日");
        duration("日历日", "日历日");
        duration("个季度", "季度");
        duration("季度", "季度");
        duration("个星期", "星期");
        duration("星期", "星期");
        duration("个月", "月");
        duration("月", "月");
        duration("小时", "小时");
        duration("分钟", "分钟");
        duration("周", "周");
        duration("天", "日");
        duration("日", "日");
        duration("年", "年");
        duration("秒", "秒");
    }

    /** 单位交替式，长的排前面（正则交替是"先匹配先赢"，`万元` 必须比 `万` 先试） */
    private static final String UNIT_ALT = UNITS.keySet().stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .reduce((a, b) -> a + "|" + b)
            .orElseThrow();

    /** 阿拉伯数字 + 单位，允许中间有空格（语料实际写作「5 万元」）*/
    private static final Pattern ARABIC_UNIT =
            Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)\\s*(" + UNIT_ALT + ")");

    /**
     * 中文数字 + 单位。
     * <p>
     * 字符类做了两处刻意收窄，都是为了配合 Hutool 的 {@code chineseToNumber}：
     * <ul>
     *   <li>**不含 亿** —— 留在单位侧，这样「三万亿元」会切成 run={@code 三万} + unit={@code 亿元}
     *       得到 3×10¹²；若把亿并入 run，Hutool 的节进位算法算不对这个量级</li>
     *   <li>**不含 两 / 萬 / 億** —— Hutool 的数字表只有「零一壹二贰…玖」，单位表只有「十拾百佰千仟万亿」，
     *       这三个字传进去会抛异常。不含它们只是少认几种写法，不会误报</li>
     * </ul>
     * 起始字符再排除「万」，避免把「金额单位为万元」里的裸「万」当成数字 1 万；
     * 单纯挂一个「千」「百」的情况由 {@link #looksNumeric} 兜住。
     */
    /** 真数字字（含大写）：量级词十百千万之外的"有值"字符 */
    private static final String DIGIT_CHARS = "零一壹二贰三叁四肆五伍六陆七柒八捌九玖";

    private static final Pattern CHINESE_UNIT = Pattern.compile(
            "([零一壹二贰三叁四肆五伍六陆七柒八捌九玖十拾百佰千仟]"
                    + "[零一壹二贰三叁四肆五伍六陆七柒八捌九玖十拾百佰千仟万]*)"
                    + "\\s*(" + UNIT_ALT + ")");

    /** 「百分之三十」这种前缀式，中文部分交给 {@link #CHINESE_UNIT}，中文数字要单独认 */
    private static final Pattern PERCENT_PREFIX_ARABIC =
            Pattern.compile("[百千]分之\\s*(\\d[\\d,]*(?:\\.\\d+)?)");

    private static final Pattern PERCENT_PREFIX_CHINESE = Pattern.compile(
            "[百千]分之\\s*([零一壹二贰三叁四肆五伍六陆七柒八捌九玖十拾百佰千仟]"
                    + "[零一壹二贰三叁四肆五伍六陆七柒八捌九玖十拾百佰千仟万]*)");

    /**
     * 日期整体剔除。本次校验范围**不含日期**，而「年 / 月 / 日」恰好也是期限单位，
     * 不先抹掉的话「2024年1月1日」会被切成「2024年」当成 2024 年的期限。
     */
    private static final Pattern DATE = Pattern.compile(
            "\\d{4}\\s*年\\s*\\d{1,2}\\s*月(?:\\s*\\d{1,2}\\s*[日号])?"
                    + "|\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号]"
                    + "|\\d{4}\\s*[-/]\\s*\\d{1,2}\\s*[-/]\\s*\\d{1,2}");

    /** 裸年份值域：落在这个区间且单位是「年」的，按日期处理而非期限 */
    private static final int YEAR_MIN = 1900;
    private static final int YEAR_MAX = 2100;

    /**
     * 抽取文本中所有可核对的数值。
     *
     * @return 归一化后的数值列表，按出现顺序；无命中返回空列表（不返回 null）
     */
    public List<Mention> extract(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String work = mask(text, DATE);

        List<Mention> found = new ArrayList<>();
        work = collectPrefix(work, PERCENT_PREFIX_ARABIC, NumericExtractor::parseNumber, found);
        work = collectPrefix(work, PERCENT_PREFIX_CHINESE, NumericExtractor::parseChineseNumber, found);
        // 阿拉伯数字先提取并抹掉，再跑中文规则。否则「3 千元」里的「千元」会被中文规则
        // 当成裸量级词再认一次，凭空多出一个 1000 元
        work = collectArabic(work, found);
        collectChinese(work, found);

        // 裸年份剔除：放在最后，因为日期模式只覆盖了"带月/带日"的写法
        found.removeIf(NumericExtractor::looksLikeBareYear);
        return List.copyOf(found);
    }

    /**
     * 抽取文本中所有数值的规范 key 集合，供 {@link NumericConsistencyChecker} 做集合差。
     */
    public Set<String> keys(String text) {
        Set<String> keys = new LinkedHashSet<>();
        for (Mention m : extract(text)) {
            keys.add(m.key());
        }
        return keys;
    }

    private static boolean looksLikeBareYear(Mention m) {
        return m.dimension() == Dimension.DURATION
                && "年".equals(m.unit())
                && m.value().stripTrailingZeros().scale() <= 0
                && m.value().intValue() >= YEAR_MIN
                && m.value().intValue() <= YEAR_MAX;
    }

    /** 提取阿拉伯数字+单位，并把命中片段抹掉后返回（给中文规则让路，避免同一段被认两遍） */
    private String collectArabic(String work, List<Mention> found) {
        Matcher m = ARABIC_UNIT.matcher(work);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            BigDecimal value = parseNumber(m.group(1));
            if (value != null) {
                add(found, m.group(), value, UNITS.get(m.group(2)));
            }
            m.appendReplacement(sb, " ".repeat(m.group().length()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private void collectChinese(String work, List<Mention> found) {
        Matcher m = CHINESE_UNIT.matcher(work);
        while (m.find()) {
            if (!looksNumeric(m.group(1))) {
                continue;
            }
            BigDecimal value = parseChineseNumber(m.group(1));
            if (value != null) {
                add(found, m.group(), value, UNITS.get(m.group(2)));
            }
        }
    }

    /**
     * 判断一个中文字符串是"数字"还是"裸量级词"。
     * <p>
     * 中文数字的字类里混着两类字：真数字（一二三…九）和量级词（十百千万）。
     * 「三十」「伍万」是数字，但光秃秃一个「千」「百」「百万」通常只是量级标签 ——
     * 「费用按千元计」「金额单位为百万元」里的那截都不该被读成数值。
     * <p>
     * 判据：
     * <ol>
     *   <li>含真数字字 → 是数（「三十」「伍万」）</li>
     *   <li>以「十」开头 → 是数。「十」是唯一能独立成数的量级词（「十万」＝100000）</li>
     *   <li>其余（千、百、百万、千万…）→ 判为量级标签，不是数</li>
     * </ol>
     * 代价是漏掉「百年」「千人」这类以百/千开头的写法。按"宁可漏不可误报"的原则可以接受 ——
     * 漏了只是少查一处，误报会把本来正确的回答拦掉。
     */
    private static boolean looksNumeric(String run) {
        for (int i = 0; i < run.length(); i++) {
            if (DIGIT_CHARS.indexOf(run.charAt(i)) >= 0) {
                return true;
            }
        }
        return run.startsWith("十");
    }

    /**
     * 收「百分之X / 千分之X」前缀式，并把命中片段抹掉后返回。
     * 抹掉是必需的：否则「百分之三十」里残留的中文数字会被后面的通用规则重复计入。
     */
    private String collectPrefix(String work, Pattern pattern,
                                 Function<String, BigDecimal> parser, List<Mention> found) {
        Matcher m = pattern.matcher(work);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            BigDecimal raw = parser.apply(m.group(1));
            if (raw != null) {
                // 千分之N = N/10 个百分点，与「‰」同一个折算；百分之N 原值即百分数
                BigDecimal value = m.group().startsWith("千")
                        ? raw.multiply(new BigDecimal("0.1"))
                        : raw;
                found.add(new Mention(m.group().strip(), Dimension.PERCENT, value, "%"));
            }
            m.appendReplacement(sb, " ".repeat(m.group().length()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static void add(List<Mention> found, String raw, BigDecimal rawValue, UnitSpec spec) {
        if (spec == null) {
            return;
        }
        found.add(new Mention(raw.strip(), spec.dimension(),
                rawValue.multiply(spec.multiplier()).stripTrailingZeros(), spec.canonical()));
    }

    /** 阿拉伯数字，容忍千分位逗号；解析失败返回 null（宁可不提，也不抛异常打断整条回答） */
    private static BigDecimal parseNumber(String s) {
        try {
            return new BigDecimal(s.replace(",", "").strip());
        } catch (NumberFormatException e) {
            log.debug("NumericExtractor: 无法解析数字 '{}'，跳过", s);
            return null;
        }
    }

    /**
     * 中文数字解析，交给 Hutool。它支持大写（壹贰叁…）也支持口语（十二 = 一十二）。
     * 非法输入会抛 {@code IllegalArgumentException}，这里吞掉 —— 少认一个数字不影响正确性。
     */
    private static BigDecimal parseChineseNumber(String s) {
        try {
            return BigDecimal.valueOf(NumberChineseFormatter.chineseToNumber(s.strip()));
        } catch (RuntimeException e) {
            log.debug("NumericExtractor: 无法解析中文数字 '{}'，跳过", s);
            return null;
        }
    }

    /** 把匹配到的片段替换成等长空格：保持其余部分的相对位置不变，又不产生新的相邻字符 */
    private static String mask(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, " ".repeat(m.group().length()));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
