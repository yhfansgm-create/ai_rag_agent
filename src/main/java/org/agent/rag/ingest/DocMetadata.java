package org.agent.rag.ingest;

import java.time.LocalDate;
import java.util.List;

/**
 * 文档级元数据 —— 从 markdown front-matter 或入库 manifest 解析而来。
 * <p>
 * 防越权最小契约（缺一不可，入库时校验）：{@code kbId}、{@code docLevel}、{@code orgCodes}。
 * 其余是版本管控/审计溯源用。
 */
public record DocMetadata(
        /** 知识库分片 ID（对应会话 access_scope_json.kb_ids） */
        String kbId,
        /** 文档唯一 ID */
        String docId,
        String title,
        /** manual / policy / script / code */
        String docType,
        /** 密级 1-5（JR/T 0197-2020） */
        int docLevel,
        /** 显式授权组织清单 */
        List<String> orgCodes,
        String owner,
        String source,
        LocalDate effectiveFrom,
        LocalDate effectiveUntil,
        String version,
        /**
         * 是否代码类（商业秘密，最高一档）。
         * {@code null}=未声明（由入库侧 CodeSniffer 决定）；显式 true 继承到全 chunk；显式 false 尊重作者声明。
         */
        Boolean isCode
) {
}
