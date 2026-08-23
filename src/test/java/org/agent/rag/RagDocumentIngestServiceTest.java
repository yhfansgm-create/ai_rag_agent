package org.agent.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入库管道测试：元数据注入 + 权限预检（缺关键元数据 → 拒入）。
 */
@ExtendWith(MockitoExtension.class)
class RagDocumentIngestServiceTest {

    @Mock
    private VectorStore vectorStore;
    @Mock
    private ObjectProvider<VectorStore> provider;

    private RagDocumentIngestService service() {
        when(provider.getIfAvailable()).thenReturn(vectorStore);
        return new RagDocumentIngestService(provider, new DocMetadataParser(),
                new MarkdownChunker(), new IngestManifestLoader(), "./kb");
    }

    private static final String VALID_DOC = """
            ---
            kb_id: kb-teller
            doc_id: DOC-TEST-001
            title: 测试手册
            doc_type: manual
            doc_level: 2
            org_codes:
              - "0100/0101"
            version: "1.0"
            is_code: false
            ---
            # 一、存取款
            柜面存取款须核验身份。
            # 二、大额
            大额需主管授权。
            """;

    @Test
    @DisplayName("合法文档 → 每个 chunk 带关键元数据写入向量库")
    void ingestValidDoc_writesChunksWithMetadata() throws Exception {
        Path file = Files.createTempFile("valid", ".md");
        Files.writeString(file, VALID_DOC);
        try {
            service().ingestFile(file);

            ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
            verify(vectorStore).add(captor.capture());
            List<Document> docs = captor.getValue();

            assertThat(docs).hasSize(2);
            for (Document d : docs) {
                assertThat(d.getMetadata().get("kb_id")).isEqualTo("kb-teller");
                assertThat(d.getMetadata().get("doc_level")).isEqualTo(2);
                assertThat((List<String>) d.getMetadata().get("org_codes")).contains("0100/0101");
                assertThat(d.getMetadata()).containsKey("section").containsKey("chunk_no");
                assertThat(d.getMetadata().get("is_code")).isEqualTo(false); // 纯文本文档 → 非代码
            }
            assertThat(docs.get(0).getMetadata().get("section")).isEqualTo("一、存取款");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("含围栏代码块的 chunk → 该 chunk is_code=true（权限判定下沉到 chunk 级）")
    void codeFenceTagsChunkIsCodeTrue() throws Exception {
        String codeDoc = """
                ---
                kb_id: kb-teller
                doc_id: DOC-TEST-002
                title: 带代码的需求文档
                doc_type: manual
                doc_level: 2
                org_codes:
                  - "0100/0101"
                version: "1.0"
                is_code: false
                ---
                # 一、需求描述
                纯文字需求。
                # 二、实现验证
                正确性验证代码如下：
                ```java
                String secret = "S3CRET";
                ```
                验证结论。
                """;
        Path file = Files.createTempFile("code", ".md");
        Files.writeString(file, codeDoc);
        try {
            service().ingestFile(file);

            ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
            verify(vectorStore).add(captor.capture());
            List<Document> docs = captor.getValue();

            assertThat(docs).hasSize(2);
            // 纯文字 chunk：非代码
            assertThat(docs.get(0).getMetadata().get("is_code")).isEqualTo(false);
            // 含围栏代码块的 chunk：is_code=true，doc_level 保持文档级(2)，不提升（供检索侧脱敏）
            assertThat(docs.get(1).getMetadata().get("is_code")).isEqualTo(true);
            assertThat(docs.get(1).getMetadata().get("doc_level")).isEqualTo(2);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("缺 org_codes → 拒入(fail-closed)")
    void missingOrgCodes_rejects() throws Exception {
        String bad = """
                ---
                kb_id: kb-teller
                doc_level: 2
                ---
                正文
                """;
        Path file = Files.createTempFile("bad", ".md");
        Files.writeString(file, bad);
        try {
            assertThatThrownBy(() -> service().ingestFile(file))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("org_codes");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("未配置向量库 → 报错提示，不写库")
    void noVectorStore_throws() throws Exception {
        ObjectProvider<VectorStore> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        RagDocumentIngestService svc = new RagDocumentIngestService(empty, new DocMetadataParser(),
                new MarkdownChunker(), new IngestManifestLoader(), "./kb");

        Path file = Files.createTempFile("x", ".md");
        Files.writeString(file, VALID_DOC);
        try {
            assertThatThrownBy(() -> svc.ingestFile(file))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("VectorStore");
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
