package org.agent.rag;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码块脱敏器：把 markdown 围栏代码块（``` / ~~~）替换为占位符，正文保留。
 * <p>
 * 用于"密级不足但允许保留章节上下文"的检索脱敏（方案 C）：低密级用户能看到
 * "该章节存在实现/代码"的上下文，但看不到代码本体。
 * <p>
 * 只处理围栏代码块；裸代码（无围栏）由入库侧 CodeSniffer 打标 is_code=true，
 * 检索侧 {@link RetrievalAdvisor} 因脱敏器定位不到围栏而整块丢弃（fail-closed）。
 * <p>
 * fail-closed 契约：{@link RedactResult#hadCode()} 标记是否定位到代码，
 * {@link RedactResult#fullyRedacted()} 标记脱敏后是否还有代码残留；
 * 调用方（RetrievalAdvisor）据此决定"保留脱敏结果"还是"整块丢弃"。
 */
@Component
public class CodeRedactor {

    /** 占位符：告知 LLM 该处存在代码但当前会话密级不足 */
    static final String PLACEHOLDER = "[代码块已隐藏：实现细节/商业秘密，当前会话密级不足]";

    /**
     * 围栏代码块：3 个以上反引号/波浪号起止，允许语言标记，多行非贪婪。
     * 组 1 捕获围栏字符（``` 或 ~~~），结尾用 \1 反引用要求同种围栏闭合。
     */
    private static final Pattern FENCE = Pattern.compile(
            "(?s)(```+|~~~+)\\s*[^\\n]*\\n.*?\\1");

    /** 脱敏结果：text=替换后文本；hadCode=是否定位到代码；fullyRedacted=是否无残留 */
    public record RedactResult(String text, boolean hadCode, boolean fullyRedacted) {
    }

    public RedactResult redact(String text) {
        if (text == null || text.isBlank()) {
            return new RedactResult(text, false, true);
        }
        Matcher m = FENCE.matcher(text);
        if (!m.find()) {
            return new RedactResult(text, false, true);
        }
        String replaced = FENCE.matcher(text).replaceAll(PLACEHOLDER);
        boolean fullyRedacted = !FENCE.matcher(replaced).find();
        return new RedactResult(replaced, true, fullyRedacted);
    }

    /** 文本中是否仍含围栏代码块（脱敏结果核验/测试用） */
    public boolean containsCode(String text) {
        return text != null && FENCE.matcher(text).find();
    }
}
