package org.agent.rag;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 解析 markdown 文档头部的 front-matter 元数据块（YAML）。
 * 文档模板见 docs/RAG知识库文档模板.md。
 */
@Component
public class DocMetadataParser {

    private final Yaml yaml = new Yaml();

    /** 解析整篇 markdown 的 front-matter → DocMetadata */
    public DocMetadata parse(String content) {
        String block = extractFrontMatter(content);
        Map<String, Object> map = yaml.load(block);
        if (map == null) {
            map = Map.of();
        }
        return fromMap(map);
    }

    /** 从 manifest 条目（也是 YAML map）构造 DocMetadata，与 front-matter 共用同一套转换 */
    public static DocMetadata fromMap(Map<String, Object> map) {
        return new DocMetadata(
                str(map, "kb_id"), str(map, "doc_id"), str(map, "title"), str(map, "doc_type"),
                intOf(map, "doc_level"), strList(map, "org_codes"), str(map, "owner"), str(map, "source"),
                dateOf(map, "effective_from"), dateOf(map, "effective_until"), str(map, "version"),
                boolOf(map, "is_code"));
    }

    /** 提取 front-matter 的 YAML 块（不含首尾 ---）；缺块/未闭合一律抛异常（fail-closed） */
    public static String extractFrontMatter(String content) {
        String t = content.stripLeading();
        if (!t.startsWith("---")) {
            throw new IllegalArgumentException("文档缺少 front-matter 元数据块(必须以 --- 开头)");
        }
        int end = t.indexOf("\n---", 3);
        if (end < 0) {
            throw new IllegalArgumentException("front-matter 未闭合(缺少结尾 ---)");
        }
        return t.substring(3, end);
    }

    /** 去掉 front-matter，返回正文（供切分） */
    public static String stripFrontMatter(String content) {
        String t = content.stripLeading();
        if (!t.startsWith("---")) {
            throw new IllegalArgumentException("文档缺少 front-matter 元数据块(必须以 --- 开头)");
        }
        int end = t.indexOf("\n---", 3);
        if (end < 0) {
            throw new IllegalArgumentException("front-matter 未闭合(缺少结尾 ---)");
        }
        return t.substring(end + 4).stripLeading();
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static int intOf(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            return Integer.parseInt(String.valueOf(v));
        }
        return 0; // 缺失→0，由入库校验层拒绝
    }

    private static boolean boolOf(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        return v != null && "true".equalsIgnoreCase(String.valueOf(v));
    }

    private static LocalDate dateOf(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : LocalDate.parse(String.valueOf(v));
    }

    private static List<String> strList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(v)); // 容忍单值写法
    }
}
