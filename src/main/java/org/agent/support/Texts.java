package org.agent.support;

import org.springframework.ai.chat.client.ChatClientResponse;

/**
 * 跨模块共用的文本取值工具。
 *
 * <h2>为什么要有这个类</h2>
 * 这两个方法在整理前各有 2～3 份<b>逐字相同</b>的拷贝，散在 {@code RetrievalAdvisor}、
 * {@code NumericGuardrailAdvisor}、{@code TurnExtractor}、{@code TermDictionary} 里，
 * 而且每份拷贝的注释都写着"与 XX 里的写法相同"—— 注释在替代码认亲，说明它该被抽出来。
 * <p>
 * 抽取的判据是<b>语义完全一致</b>，不是"看起来像"：
 * <ul>
 *   <li>{@link #answerText} 三处逐字符相同，都是"逐层判空后取助手正文"</li>
 *   <li>{@link #trimToNull} 两处逐字符相同，都是"空串与 null 等价"</li>
 * </ul>
 * ⚠️ {@code DocMetadataParser.str(Map, String)} <b>不在</b>此列 —— 它多一个"从 map 里取键"
 * 的语义，签名和职责都不同，强行合并会把两种意图搅在一起。
 *
 * <h2>这里刻意没有做什么</h2>
 * <ul>
 *   <li><b>没有做成 Spring Bean</b>。纯静态无状态函数，做成 Bean 只会给每个用它的类
 *       增加一个构造参数，单测里还得多注入一次。</li>
 *   <li><b>没有抛异常</b>。两个方法都把"取不到"表达为 {@code null}，由调用方决定怎么处理 ——
 *       取值失败在检索层意味着"无从校验"，在数字校验层意味着"跳过"，两者都不该是异常。</li>
 * </ul>
 */
public final class Texts {

    private Texts() {
    }

    /**
     * 取响应里的助手答案正文。
     * <p>
     * 逐层判空是必需的，不是防御性编程：测试里的 mock 响应、拒答路径本地构造的响应，
     * 都可能没有 {@code chatResponse}。返回 {@code null} 表示"无从校验"，
     * 调用方据此跳过校验，而不是把 null 当空串继续走。
     */
    public static String answerText(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null
                || response.chatResponse().getResult() == null) {
            return null;
        }
        var output = response.chatResponse().getResult().getOutput();
        return output == null ? null : output.getText();
    }

    /**
     * 把元数据里的任意值归一成字符串：{@code null} → {@code null}，去空白后为空 → {@code null}。
     * <p>
     * 用于读 vector store / YAML 里取出的 metadata。这些值来源不一（可能是 String、Integer、Date），
     * 统一走 {@code String.valueOf}；<b>空串与 null 等价</b> 是为了让调用方只需判一次 null，
     * 不用写 {@code if (s == null || s.isBlank())}。
     */
    public static String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).strip();
        return s.isEmpty() ? null : s;
    }
}
