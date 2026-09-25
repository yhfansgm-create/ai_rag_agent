package org.agent.rag.retrieve;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * RRF（Reciprocal Rank Fusion，倒数排名融合）—— 把多路检索结果合成一个统一排名。
 * <p>
 * 公式：{@code score(d) = Σ 1 / (k + rank_i(d))}，rank 从 1 开始，k 默认 60。
 * <p>
 * <b>为什么用 RRF 而不是加权求和</b>：向量余弦相似度与关键词分数<b>量纲完全不同</b>，
 * 加权求和要先做归一化，而归一化参数换一批数据就失效。RRF 只用<b>排名</b>不用分数，
 * 天然免调参、对分数尺度不敏感 —— 这正是它在工业界成为默认做法的原因。
 * <p>
 * 本项目的多路来源是<b>查询变体</b>（原问题 + 术语归一化后的变体，见 {@link QueryExpander}）。
 * 因为云 RDS 不一定能装 zhparser 中文分词扩展，本项目没有走"数据库全文索引 + 向量"的经典 hybrid，
 * 而是在应用层用"多路查询 + RRF"达到相近效果 —— 同样绕开了对数据库扩展的依赖。
 */
@Component
public class RrfFusion {

    public static final int DEFAULT_K = 60;

    private final int k;

    public RrfFusion(@Value("${rag.retrieval.rrf-k:60}") int k) {
        this.k = k;
    }

    /**
     * 融合多路有序结果。
     *
     * @param rankedLists 各路结果，每路<b>必须已按相关性降序排列</b>（排名即权重来源）
     * @param keyFn       取元素的唯一标识（用于跨路去重）
     * @return 融合后的有序列表；同一元素只出现一次，保留首次出现时的实例
     */
    public <T> List<T> fuse(List<List<T>> rankedLists, Function<T, String> keyFn) {
        Map<String, Double> scores = new HashMap<>();
        // LinkedHashMap 记录首次出现顺序：List.sort 是稳定排序，
        // 因此分数相同时会保持这个顺序，让结果可复现（银行场景要求同问同答）
        Map<String, T> firstSeen = new LinkedHashMap<>();

        for (List<T> list : rankedLists) {
            if (list == null) {
                continue;
            }
            for (int i = 0; i < list.size(); i++) {
                T item = list.get(i);
                if (item == null) {
                    continue;
                }
                String key = keyFn.apply(item);
                if (key == null) {
                    continue;
                }
                scores.merge(key, 1.0 / (k + i + 1), Double::sum);
                firstSeen.putIfAbsent(key, item);
            }
        }

        List<String> ordered = new ArrayList<>(scores.keySet());
        ordered.sort((a, b) -> Double.compare(scores.get(b), scores.get(a)));

        List<T> result = new ArrayList<>(ordered.size());
        for (String key : ordered) {
            result.add(firstSeen.get(key));
        }
        return result;
    }
}
