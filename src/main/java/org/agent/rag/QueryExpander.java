package org.agent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 查询扩展器 —— 把用户的口语问题改写成"更可能命中制度原文"的若干变体。
 * <p>
 * <b>回答的真实问题</b>：员工问"年假能休几天"，制度里写的是"带薪年休假"，
 * 用户口语和文件用语对不上，纯向量检索也救不了这种精确术语的错位。
 * <p>
 * 产出的第一路<b>永远是原问题</b>，扩展变体只做补充。这一点是刻意的：
 * 改写本身可能引入偏差，保留原问题做多路召回，等于"改写错了也还有兜底"。
 * <p>
 * <b>扩展位留给 LLM</b>：当前只用 {@link TermDictionary} 做确定性改写。
 * 若要接 LLM 改写，在这里再追加一路变体即可 ——
 * {@link RrfFusion} 只关心"有几路有序结果"，不关心每路是怎么来的。
 * 这个接缝是刻意留的：机制不变，策略可换。
 */
@Slf4j
@Component
public class QueryExpander {

    public static final int DEFAULT_MAX_VARIANTS = 3;

    private final TermDictionary dictionary;
    private final int maxVariants;

    @Autowired
    public QueryExpander(TermDictionary dictionary,
                         @Value("${rag.retrieval.max-query-variants:3}") int maxVariants) {
        this.dictionary = dictionary;
        this.maxVariants = Math.max(1, maxVariants);
    }

    /** 测试便捷构造：空词典 + 默认上限 */
    public QueryExpander() {
        this(TermDictionary.empty(), DEFAULT_MAX_VARIANTS);
    }

    /**
     * 展开查询变体。
     *
     * @return 至少含原问题一路；原问题为空时返回空列表（调用方据此跳过检索）
     */
    public List<String> expand(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.strip();
        // LinkedHashSet：去重且保持"原问题优先"的顺序
        Set<String> variants = new LinkedHashSet<>();
        variants.add(q);

        for (TermDictionary.Term term : dictionary.terms()) {
            // 方向一：口语 → 制度用语。命中率最高的一路，因为文档用的是 canonical
            for (String alias : term.aliases()) {
                if (!alias.equals(term.canonical()) && q.contains(alias)) {
                    variants.add(q.replace(alias, term.canonical()));
                }
            }
            // 方向二：制度用语 → 口语。反向情况少但存在（比如工单里抄了制度用词，
            // 而某份文档的正文用的却是俗称），成本几乎为零，顺手加上
            if (q.contains(term.canonical())) {
                for (String alias : term.aliases()) {
                    variants.add(q.replace(term.canonical(), alias));
                }
            }
        }

        List<String> result = variants.stream().limit(maxVariants).toList();
        if (result.size() > 1) {
            log.debug("查询扩展：{} → {} 路变体", q, result.size());
        }
        return result;
    }
}
