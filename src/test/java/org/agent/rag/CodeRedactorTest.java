package org.agent.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 代码块脱敏器测试：围栏代码块替换为占位符，正文保留。
 */
class CodeRedactorTest {

    private final CodeRedactor redactor = new CodeRedactor();

    @Test
    @DisplayName("围栏代码块替换为占位符，前后正文保留")
    void replacesFencedBlock() {
        String text = "本章验证接口正确性：\n```java\nString s = \"x\";\n```\n结论如上。";

        var r = redactor.redact(text);

        assertThat(r.hadCode()).isTrue();
        assertThat(r.fullyRedacted()).isTrue();
        assertThat(r.text())
                .contains("本章验证接口正确性")
                .contains("结论如上")
                .contains(CodeRedactor.PLACEHOLDER)
                .doesNotContain("String s");
    }

    @Test
    @DisplayName("多个代码块全部替换")
    void replacesMultipleBlocks() {
        String text = "```java\na();\n```\n说明\n```sql\nselect 1;\n```";

        var r = redactor.redact(text);

        assertThat(r.hadCode()).isTrue();
        assertThat(r.fullyRedacted()).isTrue();
        assertThat(r.text())
                .doesNotContain("a()")
                .doesNotContain("select 1")
                .contains("说明");
    }

    @Test
    @DisplayName("支持波浪线围栏 ~~~")
    void handlesTildeFence() {
        String text = "~~~python\nx = 1\n~~~\n正文";

        var r = redactor.redact(text);

        assertThat(r.hadCode()).isTrue();
        assertThat(r.text()).doesNotContain("x = 1").contains(CodeRedactor.PLACEHOLDER);
    }

    @Test
    @DisplayName("无代码 → 原文不变，hadCode=false")
    void noCodeUnchanged() {
        String text = "纯文字，无任何代码块。";

        var r = redactor.redact(text);

        assertThat(r.hadCode()).isFalse();
        assertThat(r.fullyRedacted()).isTrue();
        assertThat(r.text()).isEqualTo(text);
    }

    @Test
    @DisplayName("未闭合围栏 → 无完整代码块匹配，原文不变（由入库打标 + 检索整块丢弃兜底）")
    void unclosedFenceUnchanged() {
        String text = "```java\nString x = 1;";

        var r = redactor.redact(text);

        assertThat(r.hadCode()).isFalse();
        assertThat(r.text()).isEqualTo(text);
    }

    @Test
    @DisplayName("containsCode 探测围栏代码块")
    void containsCodeDetectsFence() {
        assertThat(redactor.containsCode("```java\nx\n```")).isTrue();
        assertThat(redactor.containsCode("普通文字")).isFalse();
    }
}
