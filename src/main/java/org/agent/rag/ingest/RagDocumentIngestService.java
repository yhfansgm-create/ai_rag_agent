package org.agent.rag.ingest;

import org.agent.rag.TemporalGate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RAG 入库管道（第 2 步）：文档 → 元数据解析 → 切分 → 元数据注入每个 chunk → 权限预检 → 写入向量库。
 * <p>
 * 防越权关键：每个 chunk 都必须带 kb_id / doc_level / org_codes 三个元数据，
 * 缺任一 → 拒入（fail-closed）。这些元数据是检索层 filter 的依据。
 * <p>
 * <b>目录入库分两阶段</b>：
 * <ol>
 *   <li><b>收集</b>：先把目录下所有文档解析成待入库单元（含 manifest 展开），任一文档元数据不合法即整体中止</li>
 *   <li><b>收敛 + 写入</b>：按 doc_id 做版本收敛（{@link DocumentVersionResolver}），推导旧版本失效日，再逐个写入</li>
 * </ol>
 * 分两阶段是<b>必须</b>的：要判断"哪版是现行的"，就得先看见同一 doc_id 的<b>全部</b>版本 ——
 * 边扫边写的话，先写入的版本还不知道后面有更新的版本存在，失效日无从推导。
 * 顺带得到一个好处：校验失败发生在<b>任何写入之前</b>，不会留下"入库了一半"的知识库。
 * <p>
 * VectorStore 用 ObjectProvider 注入：未配置向量库时应用照常启动，入库时报错提示。
 */
@Slf4j
@Service
public class RagDocumentIngestService {

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final DocMetadataParser metadataParser;
    private final MarkdownChunker chunker;
    private final IngestManifestLoader manifestLoader;
    private final CodeSniffer codeSniffer;
    private final DocumentVersionResolver versionResolver;
    private final String kbDir;

    /** 一次目录入库的结果清单。既是接口返回值，也是排查"为什么某版查不到"的凭据 */
    public record IngestReport(int documents, int chunks, List<String> superseded, List<String> warnings) {
        public IngestReport {
            superseded = List.copyOf(superseded);
            warnings = List.copyOf(warnings);
        }

        public int supersededCount() {
            return superseded.size();
        }
    }

    @Autowired
    public RagDocumentIngestService(ObjectProvider<VectorStore> vectorStoreProvider,
                                    DocMetadataParser metadataParser,
                                    MarkdownChunker chunker,
                                    IngestManifestLoader manifestLoader,
                                    CodeSniffer codeSniffer,
                                    DocumentVersionResolver versionResolver,
                                    @Value("${rag.kb.dir:./kb}") String kbDir) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.metadataParser = metadataParser;
        this.chunker = chunker;
        this.manifestLoader = manifestLoader;
        this.codeSniffer = codeSniffer;
        this.versionResolver = versionResolver;
        this.kbDir = kbDir;
    }

    /** 测试便捷构造：版本收敛用默认实现（无状态，行为完全一致） */
    public RagDocumentIngestService(ObjectProvider<VectorStore> vectorStoreProvider,
                                    DocMetadataParser metadataParser,
                                    MarkdownChunker chunker,
                                    IngestManifestLoader manifestLoader,
                                    CodeSniffer codeSniffer,
                                    String kbDir) {
        this(vectorStoreProvider, metadataParser, chunker, manifestLoader, codeSniffer,
                new DocumentVersionResolver(), kbDir);
    }

    /** 扫描配置的知识库目录，做版本收敛后入库全部文档（.md 直接解析；.yml/.yaml 当 manifest） */
    public IngestReport ingestDir() throws IOException {
        VectorStore store = requireVectorStore();
        Path dir = Path.of(kbDir);
        if (!Files.exists(dir)) {
            throw new IOException("知识库目录不存在: " + dir.toAbsolutePath());
        }

        // 排序而非依赖 Files.list 的返回顺序：报告与版本收敛结果必须可复现
        List<Path> files;
        try (var stream = Files.list(dir)) {
            files = stream.filter(this::isDocFile).sorted().toList();
        }

        // 阶段一：全部解析完再动手写。解析/校验抛异常时，向量库里不会留下半份知识库
        List<Unit> units = collect(dir, files);

        // 阶段二：版本收敛 —— 需要"同一 doc_id 的全部版本"这个全局视野，所以只能在这一步做
        List<DocumentVersionResolver.DatedDoc> dated = units.stream()
                .map(u -> new DocumentVersionResolver.DatedDoc(
                        u.meta().docId(), u.meta().version(), u.meta().effectiveFrom(), u.meta().effectiveUntil()))
                .toList();
        DocumentVersionResolver.Resolution resolution = versionResolver.resolve(dated, LocalDate.now());
        resolution.warnings().forEach(log::warn);

        int totalChunks = 0;
        List<String> superseded = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            DocumentVersionResolver.Adjustment adj = resolution.adjustments().get(i);
            totalChunks += write(store, u, adj);
            if (adj.supersededBy() != null) {
                superseded.add(String.format("%s（version=%s）→ 已被 version=%s 取代，推导失效日 %s",
                        u.meta().docId(), u.meta().version(), adj.supersededBy(), adj.effectiveUntil()));
            }
        }

        log.info("知识库入库完成：{} 个文档 / {} 个 chunk，其中 {} 个为历史版本",
                units.size(), totalChunks, superseded.size());
        return new IngestReport(units.size(), totalChunks, superseded, resolution.warnings());
    }

    /**
     * 单文件入库 —— <b>不做版本收敛</b>。
     * 收敛的前提是看得见同一 doc_id 的其他版本，单文件调用给不了这个视野，
     * 硬做只能靠猜。所以它保留"原样入库"的语义，供测试与增量补录使用；
     * 正常入库走 {@link #ingestDir()}。
     */
    public int ingestFile(Path file) throws IOException {
        VectorStore store = requireVectorStore();
        int total = 0;
        for (Unit u : collectOne(file)) {
            total += write(store, u, DocumentVersionResolver.Adjustment.untouched());
        }
        return total;
    }

    // ---------------------------------------------------------------- 阶段一：收集

    /** 一个待入库单元：元数据已解析校验完，正文已就绪，只差写库 */
    private record Unit(String label, DocMetadata meta, String body, boolean docLooksLikeCode, boolean markdown) {
    }

    private List<Unit> collect(Path dir, List<Path> files) throws IOException {
        List<Unit> units = new ArrayList<>();
        for (Path p : files) {
            units.addAll(collectOne(p, dir));
        }
        return units;
    }

    /** 单文件 → 待入库单元（manifest 会展开成多条） */
    private List<Unit> collectOne(Path file) throws IOException {
        return collectOne(file, null);
    }

    private List<Unit> collectOne(Path file, Path dir) throws IOException {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".md")) {
            return List.of(collectMarkdown(file));
        }
        if (name.endsWith(".yml") || name.endsWith(".yaml")) {
            return collectManifest(file, dir);
        }
        return List.of();
    }

    private Unit collectMarkdown(Path file) throws IOException {
        String content = Files.readString(file);
        DocMetadata meta = metadataParser.parse(content);   // 缺 front-matter 直接抛
        validate(meta);                                      // 缺关键元数据 → 拒入
        String body = DocMetadataParser.stripFrontMatter(content);
        boolean docLooksLikeCode = codeSniffer.docLooksLikeCode(body);
        warnIfDeclaredFalseButLooksLikeCode(file, meta, docLooksLikeCode);
        return new Unit(file.getFileName().toString(), meta, body, docLooksLikeCode, true);
    }

    private List<Unit> collectManifest(Path file, Path dir) throws IOException {
        Path base = dir != null ? dir : Path.of(kbDir);
        List<IngestManifestLoader.ManifestEntry> entries = manifestLoader.load(Files.readString(file));
        List<Unit> units = new ArrayList<>();
        for (IngestManifestLoader.ManifestEntry e : entries) {
            if (e.file() == null) {
                continue;
            }
            validate(e.metadata());
            Path docPath = base.resolve(e.file());
            String content = Files.readString(docPath);
            boolean docLooksLikeCode = codeSniffer.docLooksLikeCode(content);
            warnIfDeclaredFalseButLooksLikeCode(docPath, e.metadata(), docLooksLikeCode);
            // 非 markdown（docx/pdf）暂时整篇一段；生产用对应解析器切分。
            // markdown=false：正文里没有 front-matter 可剥，也不按标题切分
            units.add(new Unit(file.getFileName() + ":" + e.file(), e.metadata(), content, docLooksLikeCode, false));
        }
        return units;
    }

    // ---------------------------------------------------------------- 阶段二：写入

    private int write(VectorStore store, Unit u, DocumentVersionResolver.Adjustment adj) {
        List<Document> docs;
        if (u.markdown()) {
            List<MarkdownChunker.Chunk> chunks = chunker.chunk(u.body());
            docs = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                docs.add(toDocument(u.meta(), chunks.get(i), i, u.docLooksLikeCode(),
                        adj.effectiveUntil(), adj.supersededBy()));
            }
        } else {
            MarkdownChunker.Chunk chunk = new MarkdownChunker.Chunk(
                    u.meta().title(), u.body(), MarkdownChunker.containsFencedCode(u.body()));
            docs = List.of(toDocument(u.meta(), chunk, 0, u.docLooksLikeCode(),
                    adj.effectiveUntil(), adj.supersededBy()));
        }
        store.add(docs);
        return docs.size();
    }

    /** 每个 chunk 的向量库 metadata：文档级元数据 + 章节路径 + 序号（Document 不接受 null 值，可选字段非空才放） */
    private Document toDocument(DocMetadata meta, MarkdownChunker.Chunk chunk, int chunkNo, boolean docLooksLikeCode,
                                LocalDate derivedUntil, String supersededBy) {
        Map<String, Object> metadata = new HashMap<>();
        // 防越权三要素(必填, 校验过) + 溯源信息
        metadata.put("kb_id", meta.kbId());
        metadata.put("doc_level", meta.docLevel());
        metadata.put("org_codes", meta.orgCodes());
        metadata.put("section", chunk.section());
        metadata.put("chunk_no", chunkNo);
        // 代码标签按 chunk 计算（三选一）：
        //  ① 作者显式声明为代码 → 全 chunk 继承（true 最高优先）
        //  ② 本 chunk 实际含 markdown 围栏代码块
        //  ③ 作者未声明（null）且文档级嗅探疑似代码 → 本 chunk 内含疑似代码行（裸代码兜底）
        // 显式 false：尊重作者声明，不嗅探（① 不命中，③ 也被 declared==null 挡掉），围栏 ② 仍生效
        Boolean declared = meta.isCode();
        boolean isCode = Boolean.TRUE.equals(declared)
                || chunk.containsCode()
                || (declared == null && docLooksLikeCode && codeSniffer.chunkLooksLikeCode(chunk.text()));
        metadata.put("is_code", isCode);
        putIfNotNull(metadata, "doc_id", meta.docId());
        putIfNotNull(metadata, "doc_version", meta.version());
        putIfNotNull(metadata, "doc_type", meta.docType());
        // 文档标题：检索侧引文出处要用（"来源：《XX 管理办法》 版本 3 · 2.1"），让用户能回原文核对
        putIfNotNull(metadata, "doc_title", meta.title());
        putIfNotNull(metadata, "source", meta.source());
        // 时效字段：DocMetadata 一直有这两个字段，但此前没写进 chunk metadata，
        // 导致检索侧拿不到、时效管控形同虚设。这里补上，检索侧由 TemporalGate 消费。
        // 存 ISO-8601 字符串（yyyy-MM-dd）而不是日期对象：PgVectorStore 把 metadata 序列化成 jsonb，
        // 日期对象依赖 Jackson 的 JavaTimeModule，而字符串在 jsonb 里字典序即时间序，可比较且无序列化风险。
        putIfNotNull(metadata, TemporalGate.META_EFFECTIVE_FROM, TemporalGate.iso(meta.effectiveFrom()));
        // 失效日：作者写了就用作者的；没写则由版本收敛推导出来（见 DocumentVersionResolver）
        LocalDate until = meta.effectiveUntil() != null ? meta.effectiveUntil() : derivedUntil;
        putIfNotNull(metadata, TemporalGate.META_EFFECTIVE_UNTIL, TemporalGate.iso(until));
        // 审计线索：这版是被谁取代的。检索侧不消费它，但人工复核"这条为什么被判定为历史版本"时要靠它
        putIfNotNull(metadata, "superseded_by", supersededBy);
        // PgVectorStore 的 id 列是 UUID 类型，写入时会走 UUID.fromString(document.getId())，
        // 可读的 "docId@version#chunkNo" 不是合法 UUID，会抛 Invalid UUID string 导致整个入库失败。
        // 用 nameUUIDFromBytes 从该 key 派生确定性 UUID：同一文档+版本+分片序号始终映射到同一 UUID，
        // 重复入库是覆盖而非新增，保留原有的幂等语义。可读标识已由 metadata 的
        // doc_id / doc_version / chunk_no 保留，UUID 仍可溯源。
        // 注意 key 里含 version：同一 doc_id 的不同版本是不同 UUID，因此多版本可以共存、互不覆盖 ——
        // 这正是"入库版本管理"能在不删除旧版本的前提下工作的前提。
        String chunkKey = (meta.docId() == null ? "?" : meta.docId())
                + "@" + (meta.version() == null ? "?" : meta.version())
                + "#" + chunkNo;
        String id = UUID.nameUUIDFromBytes(chunkKey.getBytes(StandardCharsets.UTF_8)).toString();
        return Document.builder().id(id).text(chunk.text()).metadata(metadata).build();
    }

    private static void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    /** 作者显式 is_code=false 但正文检出疑似代码 → 尊重声明（裸代码不提升），但打 WARN 提醒复核（防漏标泄漏） */
    private void warnIfDeclaredFalseButLooksLikeCode(Path file, DocMetadata meta, boolean docLooksLikeCode) {
        if (meta.isCode() == Boolean.FALSE && docLooksLikeCode) {
            log.warn("文档 {} 显式声明 is_code=false，但正文检出疑似代码信号。作者声明优先：裸代码不提升标签；"
                    + "围栏代码块不受影响、仍会按代码脱敏。请复核声明是否准确，以防裸代码对低密级用户泄漏。",
                    file.getFileName());
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
