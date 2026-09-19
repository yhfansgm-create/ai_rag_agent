package org.agent.rag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识库入库入口：POST /api/kb/ingest 扫描 rag.kb.dir 目录并入库。
 * 未配置向量库时返回 400 提示。
 */
@RestController
@RequestMapping("/kb")
public class KbController {

    private final RagDocumentIngestService ingestService;

    public KbController(RagDocumentIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingest() {
        try {
            RagDocumentIngestService.IngestReport r = ingestService.ingestDir();
            // 入库结果要能自证：进了几个文档、几个 chunk、哪些旧版本被收敛、哪些地方需要人工复核。
            // 返回 warnings 而不是只打日志，是因为版本失效日推算依赖 front-matter 的填写质量，
            // 而填写质量只有运维/制度管理员能补 —— 必须让调用方看见
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("documents", r.documents());
            body.put("ingestedChunks", r.chunks());
            body.put("supersededCount", r.supersededCount());
            body.put("superseded", r.superseded());
            body.put("warnings", r.warnings());
            return ResponseEntity.ok(body);
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
