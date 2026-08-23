package org.agent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 入库管道（第 2 步）：文档 → 元数据解析 → 切分 → 元数据注入每个 chunk → 权限预检 → 写入向量库。
 * <p>
 * 防越权关键：每个 chunk 都必须带 kb_id / doc_level / org_codes 三个元数据，
 * 缺任一 → 拒入（fail-closed）。这些元数据是检索层 filter 的依据。
 * <p>
 * VectorStore 用 ObjectProvider 注入：未配置向量库时应用照常启动，入库时报错提示。
 */
@Service
public class RagDocumentIngestService {

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final DocMetadataParser metadataParser;
    private final MarkdownChunker chunker;
    private final IngestManifestLoader manifestLoader;
    private final String kbDir;

    public RagDocumentIngestService(ObjectProvider<VectorStore> vectorStoreProvider,
                                    DocMetadataParser metadataParser,
                                    MarkdownChunker chunker,
                                    IngestManifestLoader manifestLoader,
                                    @Value("${rag.kb.dir:./kb}") String kbDir) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.metadataParser = metadataParser;
        this.chunker = chunker;
        this.manifestLoader = manifestLoader;
        this.kbDir = kbDir;
    }

    /** 扫描配置的知识库目录，入库全部文档（.md 直接解析；.yml/.yaml 当 manifest） */
    public int ingestDir() throws IOException {
        VectorStore store = requireVectorStore();
        Path dir = Path.of(kbDir);
        if (!Files.exists(dir)) {
            throw new IOException("知识库目录不存在: " + dir.toAbsolutePath());
        }
        int total = 0;
        try (var stream = Files.list(dir)) {
            List<Path> files = stream.filter(this::isDocFile).toList();
            for (Path p : files) {
                total += ingestFile(p, store);
            }
        }
        return total;
    }

    public int ingestFile(Path file) throws IOException {
        return ingestFile(file, requireVectorStore());
    }

    private int ingestFile(Path file, VectorStore store) throws IOException {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".md")) {
            return ingestMarkdown(file, store);
        }
        if (name.endsWith(".yml") || name.endsWith(".yaml")) {
            return ingestManifest(file, store);
        }
        return 0;
    }

    private int ingestMarkdown(Path file, VectorStore store) throws IOException {
        String content = Files.readString(file);
        DocMetadata meta = metadataParser.parse(content);   // 缺 front-matter 直接抛
        validate(meta);                                      // 缺关键元数据 → 拒入
        String body = DocMetadataParser.stripFrontMatter(content);
        List<MarkdownChunker.Chunk> chunks = chunker.chunk(body);
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            docs.add(toDocument(meta, chunks.get(i), i));
        }
        store.add(docs);
        return docs.size();
    }

    private int ingestManifest(Path file, VectorStore store) throws IOException {
        List<IngestManifestLoader.ManifestEntry> entries = manifestLoader.load(Files.readString(file));
        int total = 0;
        for (IngestManifestLoader.ManifestEntry e : entries) {
            if (e.file() == null) {
                continue;
            }
            validate(e.metadata());
            Path docPath = Path.of(kbDir).resolve(e.file());
            String content = Files.readString(docPath);
            // 非 markdown（docx/pdf）暂时整篇一段；生产用对应解析器切分
            MarkdownChunker.Chunk chunk = new MarkdownChunker.Chunk(
                    e.metadata().title(), content, MarkdownChunker.containsFencedCode(content));
            List<Document> docs = List.of(toDocument(e.metadata(), chunk, 0));
            store.add(docs);
            total += docs.size();
        }
        return total;
    }

    /** 每个 chunk 的向量库 metadata：文档级元数据 + 章节路径 + 序号（Document 不接受 null 值，可选字段非空才放） */
    private Document toDocument(DocMetadata meta, MarkdownChunker.Chunk chunk, int chunkNo) {
        Map<String, Object> metadata = new HashMap<>();
        // 防越权三要素(必填, 校验过) + 溯源信息
        metadata.put("kb_id", meta.kbId());
        metadata.put("doc_level", meta.docLevel());
        metadata.put("org_codes", meta.orgCodes());
        metadata.put("section", chunk.section());
        metadata.put("chunk_no", chunkNo);
        // 代码标签按 chunk 计算：文档级声明为代码，或本 chunk 实际含围栏代码块
        metadata.put("is_code", meta.isCode() || chunk.containsCode());
        putIfNotNull(metadata, "doc_id", meta.docId());
        putIfNotNull(metadata, "doc_version", meta.version());
        putIfNotNull(metadata, "doc_type", meta.docType());
        putIfNotNull(metadata, "source", meta.source());
        String id = (meta.docId() == null ? "?" : meta.docId())
                + "@" + (meta.version() == null ? "?" : meta.version())
                + "#" + chunkNo;
        return Document.builder().id(id).text(chunk.text()).metadata(metadata).build();
    }

    private static void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    /** 防越权预检：kb_id / doc_level / org_codes 缺一不可 */
    private void validate(DocMetadata meta) {
        if (meta.kbId() == null || meta.kbId().isBlank()) {
            throw new IllegalArgumentException("缺少必需元数据 kb_id");
        }
        if (meta.docLevel() < 1 || meta.docLevel() > 5) {
            throw new IllegalArgumentException("doc_level 必须为 1-5, 实际: " + meta.docLevel());
        }
        if (meta.orgCodes() == null || meta.orgCodes().isEmpty()) {
            throw new IllegalArgumentException("缺少必需元数据 org_codes(授权组织清单不能为空)");
        }
    }

    private VectorStore requireVectorStore() {
        VectorStore store = vectorStoreProvider.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException("未配置 VectorStore。请启用 rag.vectorstore.enabled 并配置 PG 数据源 + embedding 模型");
        }
        return store;
    }

    private boolean isDocFile(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".md") || n.endsWith(".yml") || n.endsWith(".yaml");
    }
}
