package org.agent.rag.ingest;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
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
                boolOrNull(map, "is_code"));
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

    /** 布尔字段：键缺失 → null（表示未声明，由入库侧决定）；键存在 → 布尔值 */
    private static Boolean boolOrNull(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(v));
    }

    /**
     * 日期字段：容忍两种 YAML 写法 —— 加引号("2024-01-01")得到 String，未加引号得到 java.util.Date。
     * <p>
     * SnakeYAML 对未加引号的 ISO 日期会按 UTC 零点解析成 Date(如 2024-01-01 → 2024-01-01T00:00Z)，
     * 此时 String.valueOf 会得到 "Mon Jan 01 08:00:00 CST 2024"(东八区显示)，LocalDate.parse 必然抛
     * DateTimeParseException。故 Date 分支按 UTC 取回原日期，与 YAML 的解析口径一致。
     */
    private static LocalDate dateOf(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Date d) {
            return d.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        return LocalDate.parse(String.valueOf(v));
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
