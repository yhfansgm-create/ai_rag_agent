package org.agent.memory;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 事实值的归一化（纯函数，无状态，无 IO）。
 *
 * <h2>⚠️ 这里有两套<b>用途完全不同</b>的归一化，别混</h2>
 * <table border="1">
 *   <caption>两套归一化</caption>
 *   <tr><th>方法</th><th>用途</th><th>形态</th></tr>
 *   <tr><td>{@link #forStorage(String)}</td>
 *       <td>写进 {@code fact_value} 之前</td>
 *       <td>保内容：压缩空白、统一多值分隔为 {@code /}、去首尾空白。<b>不</b>去标点、<b>不</b>转小写</td></tr>
 *   <tr><td>{@link #forComparison(String)}</td>
 *       <td>{@code FactGrounding} 复述验证时，把"用户原话"和"模型给的值"放到同一把尺子上</td>
 *       <td>丢内容：NFKC + 去所有空白 + 去所有标点与符号 + 转小写</td></tr>
 * </table>
 * 之所以必须<b>同一套比较形态用在两边</b>，是因为这一步的判据是"值是不是原话的子串"——
 * 一边全角一边半角、一边有逗号一边没有，都会让正确的候选被冤判。这与
 * {@code NumericExtractor} 先 mask 掉日期再比对是同一个手法：
 * <b>比对之前先把两边的"写法差异"抹平，剩下的才是真正的语义差异</b>。
 *
 * <h2>{@link #forComparison(String)} 去掉标点会放松判据 —— 这是刻意的</h2>
 * 去了标点之后，{@code "a.b"} 与 {@code "ab"} 会变得不可区分。对复述验证来说这不危险：
 * 这一步是<b>必要不充分</b>条件，真正的闸门是"自述信号"那一步（见 {@code FactGrounding}）。
 * 反过来如果保留标点，用户随手打的句号就能把正确的候选冤判掉 —— 那才是真的坏。
 */
public final class FactValueNormalizer {

    /** 单条事实值的长度上限（字符数，非字节）。见 {@link #charCount(String)} */
    public static final int MAX_VALUE_CHARS = 64;

    /** 多值的分隔符（存储形态） */
    public static final char MULTI_SEPARATOR = '/';

    private FactValueNormalizer() {
    }

    /**
     * 存储形态的归一化。步骤刻意是这个顺序：
     * <ol>
     *   <li>NFKC —— 先把全角字符折成半角，否则后面的分隔符判断要写两份</li>
     *   <li>把枚举类分隔符（{@code 、，,;；}）统一成 {@code /} —— 模型给"对公、对私"时
     *       要变成 {@code 对公/对私}，否则 {@code FactGrounding} 没法按 {@code /} 切分</li>
     *   <li>压缩空白：内部连续空白折成一个空格、首尾去掉</li>
     *   <li>去掉空段：{@code a//b} → {@code a/b}（模型偶尔会多打一个分隔符）</li>
     * </ol>
     * ⚠️ <b>不做</b>去标点、<b>不做</b>转小写：这两个是"比较形态"的事。存进库里的应当是
     * 用户看得懂的原文写法，而不是被削平的比对键 —— 否则将来做"记忆可查"接口时，
     * 用户看到的是一条被机器改过的字符串。
     *
     * @return 归一化后的值；输入为 null/空白时返回空串（调用方据此拒写，别在这里抛异常）
     */
    public static String forStorage(String raw) {
        if (raw == null) {
            return "";
        }
        String nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        // 枚举类分隔符统一成 /
        String unified = nfkc.replaceAll("[、，,;；]", String.valueOf(MULTI_SEPARATOR));
        // 压缩空白（\s 已覆盖半角空白；U+3000 已被 NFKC 折成空格）
        String collapsed = unified.strip().replaceAll("\\s+", " ");
        // 去掉空段与段内首尾空格
        List<String> parts = new ArrayList<>();
        for (String part : collapsed.split(String.valueOf(MULTI_SEPARATOR), -1)) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return String.join(String.valueOf(MULTI_SEPARATOR), parts);
    }

    /**
     * 比较形态的归一化（{@code FactGrounding} 用）。
     * <p>
     * NFKC → 去所有空白 → 去所有标点与符号 → 转小写。
     * <p>
     * ⚠️ 用 {@code Character.getType} 逐码点判类别，而不是正则里的 {@code \p{P}}：
     * Java 的 {@code \p{P}} <b>不含</b> {@code \p{S}（符号），而 {@code ～} 这类全角标点
     * 在 Unicode 里归符号不归标点，只写 {@code \p{P}} 会漏掉它们。
     * （{@code Character} 没有接收 {@code int} 码点的 {@code isPunctuation}，
     * 只有 {@code char} 重载 —— 用 {@code char} 会漏掉增补平面的字符，所以走 getType。）
     */
    public static String forComparison(String text) {
        if (text == null) {
            return "";
        }
        String nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(nfkc.length());
        nfkc.codePoints()
                .filter(cp -> !Character.isWhitespace(cp))
                .filter(cp -> !isPunctuationOrSymbol(cp))
                .forEach(sb::appendCodePoint);
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** Unicode 的 P*（标点）与 S*（符号）两大类。见 {@link #forComparison(String)} */
    private static boolean isPunctuationOrSymbol(int cp) {
        return switch (Character.getType(cp)) {
            case Character.CONNECTOR_PUNCTUATION,
                 Character.DASH_PUNCTUATION,
                 Character.START_PUNCTUATION,
                 Character.END_PUNCTUATION,
                 Character.INITIAL_QUOTE_PUNCTUATION,
                 Character.FINAL_QUOTE_PUNCTUATION,
                 Character.OTHER_PUNCTUATION,
                 Character.MATH_SYMBOL,
                 Character.CURRENCY_SYMBOL,
                 Character.MODIFIER_SYMBOL,
                 Character.OTHER_SYMBOL -> true;
            default -> false;
        };
    }

    /**
     * 字符数（<b>码点</b>数，不是 {@code String.length()}）。
     * <p>
     * 用码点而不是 UTF-16 单元：{@code length()} 会把一个 emoji 数成 2，
     * 于是"最多 64 个字符"对不同输入松紧不一。这个数是全项目"字符预算"的唯一出口
     * （{@code MemoryBlockRenderer} 与 {@code ContextBudget} 都用它），
     * 所以定义必须只有一处。
     * <p>
     * ⚠️ 它<b>不是 token 数</b>：classpath 上没有 tokenizer，中文约 1 字 ≈ 1 token，
     * 所以字符数是保守（偏大）的估计。它的身份是<b>预算</b>，不是计费数字。
     */
    public static int charCount(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }

    /**
     * 是否含换行或其它控制字符。
     * <p>
     * ⚠️ 这是<b>安全件</b>，不是格式洁癖：值会被拼进注入 prompt 的事实块里，
     * 而事实块与它上下的指令之间只靠"换行 + 区块声明"分隔。一个带换行的值
     * 足以在视觉上把自己伪装成块外的指令（"【以下是系统记住的…】"这类声明会被绕过）。
     * 所以换行必须<b>在写入前</b>就拒掉 —— 这是本项目里"记忆是第二条注入面"
     * 那条纵深防线最具体的一环。
     */
    public static boolean hasLineBreakOrControl(String text) {
        if (text == null) {
            return false;
        }
        return text.codePoints().anyMatch(cp -> cp == '\n' || cp == '\r'
                || (Character.isISOControl(cp) && cp != '\t'));
    }

    /**
     * 字符白名单：中文、英数、常见的中英文标点与空格。
     * <p>
     * 白名单而不是黑名单：黑名单永远列不全（还有各种零宽字符、双向控制符
     * {@code U+202E} 之类能把显示顺序搅乱的东西）。白名单的代价是"合法的事实值写不进去"，
     * 而那个方向的损失只是少记一条。
     * <p>
     * 允许的中文标点限定在 {@code 、，。；：（）} 这几个：它们能表达事实值里的列举关系，
     * 又不足以拼出格式化的指令文本。
     */
    public static boolean hasOnlyAllowedChars(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return text.codePoints().allMatch(FactValueNormalizer::isAllowed);
    }

    private static boolean isAllowed(int cp) {
        if (cp == ' ' || cp == MULTI_SEPARATOR) {
            return true;
        }
        // 中日韩统一表意文字（含扩展 A）与常用兼容区
        if (Character.isIdeographic(cp) || (cp >= 0x4E00 && cp <= 0x9FFF)) {
            return true;
        }
        if ((cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z') || (cp >= '0' && cp <= '9')) {
            return true;
        }
        return switch (cp) {
            case '、', '，', '。', '；', '：', '（', '）', '-', '_', '·' -> true;
            default -> false;
        };
    }

    /** 是否空白（null 也算） */
    public static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
