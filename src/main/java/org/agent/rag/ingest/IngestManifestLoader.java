package org.agent.rag.ingest;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析入库 manifest（Word/PDF 等无法内嵌 front-matter 的文档，标注放在这里）。
 * 格式见 docs/RAG知识库文档模板.md 第三节。
 */
@Component
public class IngestManifestLoader {

    public record ManifestEntry(String file, DocMetadata metadata) {
    }

    private final Yaml yaml = new Yaml();

    @SuppressWarnings("unchecked")
    public List<ManifestEntry> load(String content) {
        Map<String, Object> root = yaml.load(content);
        List<ManifestEntry> entries = new ArrayList<>();
        Object docs = root == null ? null : root.get("documents");
        if (docs instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> map = new HashMap<>();
                    m.forEach((k, v) -> map.put(String.valueOf(k), v));
                    Object fileObj = map.remove("file");
                    String file = fileObj == null ? null : String.valueOf(fileObj);
                    entries.add(new ManifestEntry(file, DocMetadataParser.fromMap(map)));
                }
            }
        }
        return entries;
    }
}
