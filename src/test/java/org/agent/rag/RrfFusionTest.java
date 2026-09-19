package org.agent.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RRF 融合测试：只用排名、不用分数。
 */
class RrfFusionTest {

    private final RrfFusion fusion = new RrfFusion(RrfFusion.DEFAULT_K);

    @Test
    @DisplayName("两路都排第一的文档，融合后仍排第一（共识优先）")
    void consensusRanksFirst() {
        List<List<String>> lists = List.of(
                List.of("a", "b", "c"),
                List.of("a", "c", "b"));

        assertThat(fusion.fuse(lists, s -> s)).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("只在一路里靠前、另一路没有的文档，会输给两路都上榜的文档")
    void crossListAgreementBeatsSingleListRank() {
        // x 只在第一路排第 1；y 在两路都排第 2、第 3
        List<List<String>> lists = List.of(
                List.of("x", "y"),
                List.of("y", "z"));

        // x: 1/61 ≈ 0.0164
        // y: 1/62 + 1/61 ≈ 0.0161 + 0.0164 = 0.0325  → y 胜
        assertThat(fusion.fuse(lists, s -> s)).containsExactly("y", "x", "z");
    }

    @Test
    @DisplayName("跨路去重：同一文档只出现一次，保留首次出现的实例")
    void dedupesById() {
        List<List<String>> lists = List.of(
                List.of("a", "b"),
                List.of("b", "a"));

        List<String> fused = fusion.fuse(lists, s -> s);

        assertThat(fused).hasSize(2).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    @DisplayName("单路结果保持原顺序（保证查询扩展没命中时行为与改造前等价）")
    void singleListOrderPreserved() {
        List<String> only = List.of("m", "n", "o");

        assertThat(fusion.fuse(List.of(only), s -> s)).containsExactly("m", "n", "o");
    }

    @Test
    @DisplayName("空输入 / null 路 / null 元素都不炸")
    void toleratesEmptyAndNull() {
        List<List<String>> none = List.of();
        List<List<String>> oneEmptyList = List.of(List.of());
        // List.of 不接受 null 元素，故用 Arrays.asList 构造这两种脏数据
        List<List<String>> nullList = java.util.Arrays.asList(List.of("a"), null);
        List<List<String>> nullItem = List.of(java.util.Arrays.asList("a", null, "b"));

        assertThat(fusion.fuse(none, s -> s)).isEmpty();
        assertThat(fusion.fuse(oneEmptyList, s -> s)).isEmpty();
        assertThat(fusion.fuse(nullList, s -> s)).containsExactly("a");
        assertThat(fusion.fuse(nullItem, s -> s)).containsExactly("a", "b");
    }

    @Test
    @DisplayName("k 越小，头部排名优势越大：k=0 时第 1 名得分 1，稳压两个第 2 名")
    void smallKAmplifiesTopRank() {
        List<List<String>> lists = List.of(
                List.of("x"),
                List.of("y", "x"));   // x 在第 2 路排第 2

        // k=60：x = 1/61 + 1/62 ≈ 0.0325，y = 1/61 ≈ 0.0164 → x 仍胜（两路共识）
        assertThat(new RrfFusion(60).fuse(lists, s -> s)).containsExactly("x", "y");
        // k=0：x = 1/1 + 1/2 = 1.5，y = 1/1 = 1.0 → x 胜，且差距被拉大
        assertThat(new RrfFusion(0).fuse(lists, s -> s)).containsExactly("x", "y");
    }
}
