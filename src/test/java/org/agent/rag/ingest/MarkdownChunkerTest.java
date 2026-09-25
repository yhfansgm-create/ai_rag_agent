package org.agent.rag.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 标题切分测试。
 */
class MarkdownChunkerTest {

    @Test
    @DisplayName("按标题分段，保留章节路径")
    void splitsByHeadings() {
        String body = "# 一、适用范围\n内容A\n## 2.1 身份核验\n内容B\n### 2.1.1 核对\n内容C\n";

        var chunks = new MarkdownChunker().chunk(body);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).section()).isEqualTo("一、适用范围");
        assertThat(chunks.get(0).text()).contains("内容A");
        assertThat(chunks.get(1).section()).isEqualTo("2.1 身份核验");
        assertThat(chunks.get(1).text()).contains("内容B");
        assertThat(chunks.get(2).section()).isEqualTo("2.1.1 核对");
        assertThat(chunks.get(2).text()).contains("内容C");
    }

    @Test
    @DisplayName("无标题时整篇一段")
    void noHeadingSingleChunk() {
        var chunks = new MarkdownChunker().chunk("一段没有标题的正文");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).section()).isEqualTo("概述");
    }

    @Test
    @DisplayName("含围栏代码块的 chunk 标记 containsCode=true")
    void marksChunksContainingFencedCode() {
        String body = "# 一、范围\n普通正文\n# 二、实现\n```java\npublic void verify(){}\n```\n验证逻辑\n";

        var chunks = new MarkdownChunker().chunk(body);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).containsCode()).isFalse();
        assertThat(chunks.get(1).containsCode()).isTrue();
    }

    @Test
    @DisplayName("整篇不含代码 → 所有 chunk containsCode=false")
    void plainTextNeverCode() {
        var chunks = new MarkdownChunker().chunk("# 标题\n只有文字的正文，无代码。");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).containsCode()).isFalse();
    }
}
