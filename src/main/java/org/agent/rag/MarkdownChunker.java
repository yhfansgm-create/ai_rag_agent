package org.agent.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 简单 markdown 切分：按标题（#~######）分段，保留章节路径（section）用于引文溯源。
 * 生产可换成更精细的分段器（按段落、固定 token 长度等），这里先演示元数据链路。
 */
@Component
public class MarkdownChunker {

    public record Chunk(String section, String text) {
    }

    public List<Chunk> chunk(String body) {
        List<Chunk> chunks = new ArrayList<>();
        String section = "概述";
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            if (line.matches("^#{1,6}\\s+.*")) {
                if (!sb.isEmpty()) {
                    chunks.add(new Chunk(section, sb.toString().strip()));
                    sb.setLength(0);
                }
                section = line.replaceFirst("^#{1,6}\\s+", "").strip();
            } else {
                sb.append(line).append('\n');
            }
        }
        if (!sb.isEmpty()) {
            chunks.add(new Chunk(section, sb.toString().strip()));
        }
        return chunks;
    }
}
