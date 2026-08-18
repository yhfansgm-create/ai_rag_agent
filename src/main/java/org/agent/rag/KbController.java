package org.agent.rag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
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
            int n = ingestService.ingestDir();
            return ResponseEntity.ok(Map.of("ingestedChunks", n));
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
