package org.agent.memory.model;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * 引文指针：只记"哪份文档的哪一版、哪个分片"，<b>不记内容</b>。
 * <p>
 * 这是 L1「存指针不存内容」最直接的体现（RAG-07 §二 L1）。回看历史时按这三个字段用
 * <b>当前</b>权限重新取原文 —— 取不到就如实说"你已无权查看"。
 * 若把 {@code RetrievalAdvisor.augment()} 渲染出的原文一并存了，这个判断就再也做不了：
 * 原文已经跟着历史走了，权限变更管不住它。
 *
 * @param docId      制度文档编号（chunk metadata 的 {@code doc_id}）
 * @param docVersion 文档版本（chunk metadata 的 {@code doc_version}）；
 *                   允许 null —— 不同知识库的 metadata 完整度不一，缺了不该让整条指针作废。
 *                   这是必需的快照：原文可能已出新版，展示要能标注"该轮引用 v3，当前已有 v5"
 * @param chunkId    分片标识（{@link Document#getId()}，向量库里的存储键）
 */
public record CitedChunkRef(String docId, String docVersion, String chunkId) {

    /**
     * 从本次检索结果里抽指针。
     * <p>
     * {@code doc_id} 缺失的整条跳过：没有 doc_id 的指针回看时定位不到原文，存下来等于无效数据。
     * 这与 {@code DocumentVersionResolver} 对 doc_id 的态度一致 —— 没有稳定标识就无法判断谁是谁。
     *
     * @param docs 本次注入 prompt 的检索结果；拒答轮为 null 或空，返回空列表
     */
    public static List<CitedChunkRef> fromDocuments(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        List<CitedChunkRef> refs = new ArrayList<>(docs.size());
        for (Document d : docs) {
            if (d == null) {
                continue;
            }
            String docId = asString(d.getMetadata().get("doc_id"));
            if (docId == null) {
                continue;
            }
            refs.add(new CitedChunkRef(docId, asString(d.getMetadata().get("doc_version")), d.getId()));
        }
        return List.copyOf(refs);
    }

    private static String asString(Object raw) {
        String s = raw == null ? null : String.valueOf(raw);
        return (s == null || s.isBlank()) ? null : s;
    }
}
