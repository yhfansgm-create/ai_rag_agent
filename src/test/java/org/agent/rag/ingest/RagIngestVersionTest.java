package org.agent.rag.ingest;

import org.agent.rag.TemporalGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入库版本管理端到端测试：front-matter → 版本收敛 → chunk metadata → 检索侧时效门禁真的能拦住。
 * <p>
 * 这里刻意跨了"入库"和"检索"两层来断言 —— 只验证 ingest 算出了失效日还不够，
 * 必须证明那个失效日真的被 {@link TemporalGate} 消费、旧版本真的查不到，功能才算闭环。
 */
@ExtendWith(MockitoExtension.class)
class RagIngestVersionTest {

    @Mock
    private VectorStore vectorStore;
    @Mock
    private ObjectProvider<VectorStore> provider;
    @TempDir
    Path kbDir;

    private RagDocumentIngestService service() {
        when(provider.getIfAvailable()).thenReturn(vectorStore);
        return new RagDocumentIngestService(provider, new DocMetadataParser(), new MarkdownChunker(),
                new IngestManifestLoader(), new CodeSniffer(), kbDir.toString());
    }

    private static String versionedDoc(String version, String effectiveFrom) {
        return """
                ---
                kb_id: kb-teller
                doc_id: DOC-V-001
                title: 柜面大额取款业务规范
                doc_type: manual
                doc_level: 2
                org_codes:
                  - "0100/0101"
                version: "%s"
                effective_from: %s
                is_code: false
                ---
                # 一、大额取款
                %s 版本的大额取款须主管授权。
                """.formatted(version, effectiveFrom, version);
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(kbDir.resolve(name), content);
    }

    /** 展开某次 add() 的全部 chunk */
    @SuppressWarnings("unchecked")
    private static List<Document> allChunks(List<List<Document>> captured) {
        return captured.stream().flatMap(List::stream).toList();
    }

    @Test
    @DisplayName("同 doc_id 两版本入库 → 旧版自动获得失效日（= 新版生效日前一天）并标注被谁取代")
    void twoVersions_olderAutoClosed() throws IOException {
        write("teller-v1.md", versionedDoc("1.0", "2024-01-01"));
        write("teller-v2.md", versionedDoc("2.0", "2024-07-01"));

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        RagDocumentIngestService.IngestReport report = service().ingestDir();
        verify(vectorStore, org.mockito.Mockito.times(2)).add(captor.capture());
        List<Document> chunks = allChunks(captor.getAllValues());

        assertThat(report.documents()).isEqualTo(2);
        assertThat(report.supersededCount()).isEqualTo(1);
        assertThat(report.superseded().get(0)).contains("DOC-V-001").contains("2.0").contains("2024-06-30");
        assertThat(report.warnings()).isEmpty();

        // 旧版被补上失效日与取代者
        Document old = chunkOf(chunks, "1.0");
        assertThat(old.getMetadata().get(TemporalGate.META_EFFECTIVE_UNTIL)).isEqualTo("2024-06-30");
        assertThat(old.getMetadata().get("superseded_by")).isEqualTo("2.0");
        // 新版保持"生效中"，不写入失效日
        Document current = chunkOf(chunks, "2.0");
        assertThat(current.getMetadata()).doesNotContainKey(TemporalGate.META_EFFECTIVE_UNTIL);
    }

    @Test
    @DisplayName("闭环验证：入库推导的失效日，检索侧 TemporalGate 真的拦得住旧版本")
    void derivedUntil_isHonouredByRetrievalGate() throws IOException {
        write("teller-v1.md", versionedDoc("1.0", "2024-01-01"));
        write("teller-v2.md", versionedDoc("2.0", "2024-07-01"));

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        service().ingestDir();
        verify(vectorStore, org.mockito.Mockito.times(2)).add(captor.capture());
        List<Document> chunks = allChunks(captor.getAllValues());

        TemporalGate gate = new TemporalGate();
        LocalDate today = LocalDate.of(2026, 9, 19);

        assertThat(gate.stateOf(chunkOf(chunks, "1.0").getMetadata(), today))
                .isEqualTo(TemporalGate.State.EXPIRED);
        assertThat(gate.stateOf(chunkOf(chunks, "2.0").getMetadata(), today))
                .isEqualTo(TemporalGate.State.EFFECTIVE);
        // 追溯历史业务：2024 年 3 月办的那笔，当时依据的正是 v1.0
        assertThat(gate.stateOf(chunkOf(chunks, "1.0").getMetadata(), LocalDate.of(2024, 3, 1)))
                .isEqualTo(TemporalGate.State.EFFECTIVE);
    }

    @Test
    @DisplayName("目录里有非法文档 → 整体中止，向量库里一条都没写（不留半份知识库）")
    void invalidDocAbortsWholeIngest() throws IOException {
        write("ok.md", versionedDoc("1.0", "2024-01-01"));
        write("bad.md", """
                ---
                kb_id: kb-teller
                doc_level: 2
                version: "1.0"
                ---
                缺 org_codes 的文档
                """);

        assertThatThrownBy(() -> service().ingestDir())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("org_codes");
        verify(vectorStore, never()).add(any());
    }

    @Test
    @DisplayName("单文件入库不做版本收敛（看不见兄弟版本就无从判断）—— 保留原样入库语义")
    void singleFileIngestSkipsVersionResolution() throws IOException {
        write("teller-v1.md", versionedDoc("1.0", "2024-01-01"));
        write("teller-v2.md", versionedDoc("2.0", "2024-07-01"));

        service().ingestFile(kbDir.resolve("teller-v1.md"));

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        Document only = allChunks(captor.getAllValues()).get(0);
        assertThat(only.getMetadata()).doesNotContainKey(TemporalGate.META_EFFECTIVE_UNTIL);
    }

    @Test
    @DisplayName("只有一个版本的文档不被动失效日（收敛只针对真正的多版本）")
    void singleVersionUntouched() throws IOException {
        write("solo.md", versionedDoc("1.0", "2024-01-01"));

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        RagDocumentIngestService.IngestReport report = service().ingestDir();
        verify(vectorStore).add(captor.capture());

        assertThat(report.supersededCount()).isZero();
        assertThat(allChunks(captor.getAllValues()).get(0).getMetadata())
                .doesNotContainKey(TemporalGate.META_EFFECTIVE_UNTIL);
    }

    @Test
    @DisplayName("缺知识库目录 → 明确报错（不是静默入库 0 条）")
    void missingDir_throws() {
        RagDocumentIngestService svc = new RagDocumentIngestService(provider, new DocMetadataParser(),
                new MarkdownChunker(), new IngestManifestLoader(), new CodeSniffer(),
                kbDir.resolve("不存在的子目录").toString());
        when(provider.getIfAvailable()).thenReturn(vectorStore);

        assertThatThrownBy(svc::ingestDir).isInstanceOf(IOException.class).hasMessageContaining("知识库目录不存在");
    }

    private static Document chunkOf(List<Document> chunks, String version) {
        return chunks.stream()
                .filter(d -> version.equals(d.getMetadata().get("doc_version")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 doc_version=" + version + " 的 chunk"));
    }
}
