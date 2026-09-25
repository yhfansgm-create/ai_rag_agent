package org.agent.rag.web;

import org.agent.rag.ingest.RagDocumentIngestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识库入库入口：{@code POST /kb/ingest} 扫描 {@code rag.kb.dir} 目录并入库。
 * 未配置向量库时返回 400 提示。
 * <p>
 * ⚠️ 路由<b>没有 {@code /api} 前缀</b>（项目未配置 {@code server.servlet.context-path}），
 * 端口 8123，完整地址 {@code http://localhost:8123/kb/ingest}（<b>无末尾斜杠</b>）。
 * {@code docs/} 下写的 {@code /api/kb/ingest} 是过时写法，照它发请求会 404。
 * <p>
 * 手动触发，<b>不会自动跑</b>；扫描目录只看该层、<b>不递归</b>。
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
