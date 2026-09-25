package org.agent.rag.ingest;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 简单 markdown 切分：按标题（#~######）分段，保留章节路径（section）用于引文溯源，
 * 并识别围栏代码块（```/~~~）标记每个 chunk 是否含代码（{@code containsCode}）。
 * <p>
 * containsCode 是防越权的最小判定单元：入库时据此给 chunk 打 is_code 元数据，
 * 检索时对密级不足的用户脱敏/丢弃代码内容。
 */
@Component
public class MarkdownChunker {

    public record Chunk(String section, String text, boolean containsCode) {
    }

    /** 围栏代码块起始行：行首（可带缩进）出现 3 个以上反引号或波浪号 */
    private static final Pattern FENCE_LINE = Pattern.compile("(?m)^\\s*(```+|~~~+).*");

    public List<Chunk> chunk(String body) {
        List<Chunk> chunks = new ArrayList<>();
        String section = "概述";
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            if (line.matches("^#{1,6}\\s+.*")) {
                if (!sb.isEmpty()) {
                    chunks.add(new Chunk(section, sb.toString().strip(), containsFencedCode(sb.toString())));
                    sb.setLength(0);
                }
                section = line.replaceFirst("^#{1,6}\\s+", "").strip();
            } else {
                sb.append(line).append('\n');
            }
        }
        if (!sb.isEmpty()) {
            chunks.add(new Chunk(section, sb.toString().strip(), containsFencedCode(sb.toString())));
        }
        return chunks;
    }

    /** 文本中是否存在围栏代码块（供入库/检索侧判定 chunk 是否含代码） */
    public static boolean containsFencedCode(String text) {
        return text != null && FENCE_LINE.matcher(text).find();
    }
}
