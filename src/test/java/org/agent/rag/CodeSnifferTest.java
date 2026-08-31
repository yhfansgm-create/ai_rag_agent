package org.agent.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 代码嗅探测试：裸代码行识别 + document/chunk 两级阈值。
 */
class CodeSnifferTest {

    private final CodeSniffer sniffer = new CodeSniffer();

    @Test
    @DisplayName("裸 SQL 语句行 → 识别为代码")
    void bareSqlLineDetected() {
        assertThat(CodeSniffer.isCodeLine("SELECT cust_no FROM t_deposit;")).isTrue();
        assertThat(CodeSniffer.isCodeLine("insert into t_flow (id) values (1);")).isTrue();
        assertThat(CodeSniffer.isCodeLine("CREATE TABLE t_tmp (id INT);")).isTrue();
        assertThat(CodeSniffer.isCodeLine("UPDATE t_acct SET bal = bal - 100 WHERE id = 1;")).isTrue();
    }

    @Test
    @DisplayName("编程语法行 → 识别为代码")
    void codeSyntaxLineDetected() {
        assertThat(CodeSniffer.isCodeLine("public static void main(String[] args) {")).isTrue();
        assertThat(CodeSniffer.isCodeLine("String s = \"x\";")).isTrue();
        assertThat(CodeSniffer.isCodeLine("}")).isTrue();
        assertThat(CodeSniffer.isCodeLine("List<String> names = new ArrayList<>();")).isTrue();
        assertThat(CodeSniffer.isCodeLine("console.log(\"ok\");")).isTrue();
        assertThat(CodeSniffer.isCodeLine("return bal;")).isTrue();
    }

    @Test
    @DisplayName("纯文字行 → 不识别为代码")
    void proseLinesNotDetected() {
        assertThat(CodeSniffer.isCodeLine("柜面存取款须核验身份。")).isFalse();
        assertThat(CodeSniffer.isCodeLine("单笔超过 5 万元需主管授权。")).isFalse();
        assertThat(CodeSniffer.isCodeLine("")).isFalse();
        assertThat(CodeSniffer.isCodeLine("   ")).isFalse();
        // 中文全角分号/冒号不触发；case 要求 ASCII 冒号
        assertThat(CodeSniffer.isCodeLine("分别填写 1；2；3 三个字段。")).isFalse();
        assertThat(CodeSniffer.isCodeLine("case 1：场景一")).isFalse();
    }

    @Test
    @DisplayName("codeLineCount 统计疑似代码行数")
    void countsCodeLines() {
        String text = "说明：\nSELECT a FROM t;\n结论。";
        assertThat(CodeSniffer.codeLineCount(text)).isEqualTo(1);
        assertThat(CodeSniffer.codeLineCount("纯文字。")).isZero();
        assertThat(CodeSniffer.codeLineCount(null)).isZero();
    }

    @Test
    @DisplayName("document 级闸门与 chunk 级定位（阈值默认 1）")
    void docAndChunkLevel() {
        String withCode = "报表口径：\nSELECT a FROM t;\n由运营部维护。";
        assertThat(sniffer.docLooksLikeCode(withCode)).isTrue();
        assertThat(sniffer.chunkLooksLikeCode(withCode)).isTrue();

        String prose = "柜面存取款须核验身份。\n单笔超过 5 万元需主管授权。";
        assertThat(sniffer.docLooksLikeCode(prose)).isFalse();
        assertThat(sniffer.chunkLooksLikeCode(prose)).isFalse();
    }
}
