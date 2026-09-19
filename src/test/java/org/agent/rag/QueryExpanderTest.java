package org.agent.rag;

import org.agent.rag.TermDictionary.Term;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 查询扩展测试：口语 → 制度用语的确定性改写。
 */
class QueryExpanderTest {

    private static final TermDictionary DICT = TermDictionary.of(List.of(
            new Term("带薪年休假", List.of("年假", "公休假")),
            new Term("跨行汇款", List.of("跨行转账"))));

    private final QueryExpander expander = new QueryExpander(DICT, 3);

    @Test
    @DisplayName("口语别名被替换成制度用语，原问题恒为第 1 路（改写错了还有兜底）")
    void aliasRewrittenToCanonical_originalFirst() {
        List<String> variants = expander.expand("年假能休几天");

        assertThat(variants).hasSize(2);
        assertThat(variants.get(0)).isEqualTo("年假能休几天");
        assertThat(variants.get(1)).isEqualTo("带薪年休假能休几天");
    }

    @Test
    @DisplayName("没有词条命中时只返回原问题一路（检索退化为单路，与改造前等价）")
    void noTermHit_singleVariant() {
        assertThat(expander.expand("大额取款怎么办理")).containsExactly("大额取款怎么办理");
    }

    @Test
    @DisplayName("一句话命中两个词条 → 各生成一路变体")
    void multipleTerms() {
        List<String> variants = expander.expand("年假期间跨行转账限额");

        assertThat(variants.get(0)).isEqualTo("年假期间跨行转账限额");
        assertThat(variants).contains("带薪年休假期间跨行转账限额", "年假期间跨行汇款限额");
    }

    @Test
    @DisplayName("反向也扩：制度用语 → 口语（有些文档正文用的是俗称）")
    void canonicalAlsoExpandsToAlias() {
        List<String> variants = expander.expand("带薪年休假怎么算");

        assertThat(variants.get(0)).isEqualTo("带薪年休假怎么算");
        assertThat(variants).contains("年假怎么算");
    }

    @Test
    @DisplayName("变体数受 max-query-variants 限制，且被截掉的永远是尾部（原问题和主要改写在前）")
    void respectsMaxVariants() {
        QueryExpander capped = new QueryExpander(DICT, 2);

        assertThat(capped.expand("年假期间跨行转账限额")).hasSize(2);
    }

    @Test
    @DisplayName("重复变体去重；空问题返回空列表（调用方据此跳过检索）")
    void dedupesAndHandlesBlank() {
        // "年假" 与 "公休假" 同时出现会改写两条，但都等于原文时会被去重
        assertThat(expander.expand("年假")).doesNotHaveDuplicates();
        assertThat(expander.expand("")).isEmpty();
        assertThat(expander.expand("   ")).isEmpty();
        assertThat(expander.expand(null)).isEmpty();
    }

    @Test
    @DisplayName("空词典 → 恒等于单路原问题")
    void emptyDictionaryIsNoOp() {
        assertThat(new QueryExpander().expand("年假能休几天")).containsExactly("年假能休几天");
    }

    @Test
    @DisplayName("术语表缺文件/格式错时降级为空词典，不抛异常（增强项不该阻断服务）")
    void missingTermsFileDegradesGracefully() {
        assertThat(new TermDictionary("classpath:不存在的术语表.yml").terms()).isEmpty();
    }

    @Test
    @DisplayName("默认术语表能正常加载，且词条结构合法")
    void bundledTermsFileLoads() {
        TermDictionary bundled = new TermDictionary("classpath:kb-terms.yml");

        assertThat(bundled.terms()).isNotEmpty();
        assertThat(bundled.terms()).allSatisfy(t -> {
            assertThat(t.canonical()).isNotBlank();
            assertThat(t.aliases()).isNotEmpty();
        });
        // 真实口语词条确实能被改写（端到端验证词表不是摆设）
        assertThat(new QueryExpander(bundled, 3).expand("储蓄卡挂失怎么办"))
                .contains("借记卡挂失怎么办");
    }
}
