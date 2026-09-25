package org.agent.rag.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * front-matter 元数据解析测试。
 */
class DocMetadataParserTest {

    private static final String SAMPLE = """
            ---
            kb_id: kb-teller
            doc_id: DOC-001
            title: 柜面存取款操作手册
            doc_type: manual
            doc_level: 2
            org_codes:
              - "0100/0101"
              - "0100/0103"
            owner: 张三-运营部
            version: "1.2"
            is_code: false
            ---
            # 一、适用范围
            正文……
            """;

    @Test
    @DisplayName("解析 front-matter → DocMetadata")
    void parsesFrontMatter() {
        DocMetadata m = new DocMetadataParser().parse(SAMPLE);

        assertThat(m.kbId()).isEqualTo("kb-teller");
        assertThat(m.docId()).isEqualTo("DOC-001");
        assertThat(m.docLevel()).isEqualTo(2);
        assertThat(m.orgCodes()).containsExactly("0100/0101", "0100/0103");
        assertThat(m.version()).isEqualTo("1.2");
        assertThat(m.isCode()).isFalse();
        assertThat(m.owner()).isEqualTo("张三-运营部");
    }

    @Test
    @DisplayName("未声明 is_code → isCode()=null（由入库侧 CodeSniffer 决定）")
    void absentIsCodeIsNull() {
        String noFlag = """
                ---
                kb_id: kb-teller
                doc_id: DOC-002
                title: 无代码声明文档
                doc_type: manual
                doc_level: 2
                org_codes:
                  - "0100/0101"
                ---
                # 一、正文
                正文……
                """;
        DocMetadata m = new DocMetadataParser().parse(noFlag);
        assertThat(m.isCode()).isNull();
    }

    @Test
    @DisplayName("去掉 front-matter 返回正文")
    void stripsFrontMatter() {
        String body = DocMetadataParser.stripFrontMatter(SAMPLE);
        assertThat(body).startsWith("# 一、适用范围").contains("正文");
    }

    @Test
    @DisplayName("缺少 front-matter → 抛异常(fail-closed)")
    void missingFrontMatterThrows() {
        String bad = "# 没有元数据的文档\n正文";
        assertThatThrownBy(() -> new DocMetadataParser().parse(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("front-matter");
    }

    @Test
    @DisplayName("front-matter 未闭合 → 抛异常")
    void unclosedFrontMatterThrows() {
        String bad = "---\nkb_id: kb-teller\n# 没有闭合的元数据";
        assertThatThrownBy(() -> new DocMetadataParser().parse(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未闭合");
    }
}
