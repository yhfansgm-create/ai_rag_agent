package org.agent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务术语表 —— 解决"员工用口语问、制度用条文写"的<b>词汇不匹配</b>问题。
 * <p>
 * 银行里这套东西本来就存在（业务术语标准 / 名词解释），把它变成可加载的词表即可，
 * 不需要模型参与。相比"用 LLM 改写查询"，词典方案有三个银行看重的性质：
 * <ul>
 *   <li><b>确定性</b>：同一问题永远改写成同一个结果，可复现、可回归测试</li>
 *   <li><b>可审计</b>：改写依据是白纸黑字的词条，出了争议能解释"为什么这么搜"</li>
 *   <li><b>无幻觉</b>：不会把"年假"改写成"年终奖"</li>
 * </ul>
 * LLM 改写的覆盖度更广，但上述三点在银行是硬要求 —— 所以本项目的策略是
 * <b>词典打底 + 留出 LLM 改写的扩展位</b>（见 {@link QueryExpander} 的类注释）。
 * <p>
 * 词表文件缺失或格式错误时<b>降级为空词典并 WARN</b>，不让应用起不来 ——
 * 术语表是增强项，缺了只是召回差一点，不该阻断整个知识库服务。
 */
@Slf4j
@Component
public class TermDictionary {

    /** 一个术语：制度里的标准写法 + 员工可能用的各种说法 */
    public record Term(String canonical, List<String> aliases) {
    }

    private final List<Term> terms;

    @Autowired
    public TermDictionary(@Value("${rag.retrieval.terms-file:classpath:kb-terms.yml}") String location) {
        this.terms = load(location);
        log.info("业务术语表加载完成：{} 条词条（来源 {}）", terms.size(), location);
    }

    private TermDictionary(List<Term> terms) {
        this.terms = terms;
    }

    /** 空词典：单元测试用，行为等价于"没有术语表" */
    public static TermDictionary empty() {
        return new TermDictionary(List.of());
    }

    /** 直接给定词条的词典：单元测试用，免去落盘一个 yml */
    public static TermDictionary of(List<Term> terms) {
        return new TermDictionary(List.copyOf(terms));
    }

    public List<Term> terms() {
        return terms;
    }

    @SuppressWarnings("unchecked")
    private static List<Term> load(String location) {
        ResourceLoader loader = new DefaultResourceLoader();
        try {
            Resource resource = loader.getResource(location);
            if (!resource.exists()) {
                log.warn("业务术语表不存在（{}），跳过查询扩展。检索将退化为单路向量召回", location);
                return List.of();
            }
            try (InputStream in = resource.getInputStream()) {
                Map<String, Object> root = new Yaml().load(in);
                Object listObj = root == null ? null : root.get("terms");
                if (!(listObj instanceof List<?> list)) {
                    log.warn("业务术语表格式异常（缺少 terms 列表），按空词典处理：{}", location);
                    return List.of();
                }
                List<Term> parsed = new ArrayList<>();
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> raw)) {
                        continue;
                    }
                    Map<String, Object> m = new HashMap<>();
                    raw.forEach((k, v) -> m.put(String.valueOf(k), v));
                    String canonical = str(m.get("canonical"));
                    List<String> aliases = strList(m.get("aliases"));
                    if (canonical == null || aliases.isEmpty()) {
                        log.warn("术语表存在无效词条（canonical 或 aliases 缺失），已跳过：{}", m);
                        continue;
                    }
                    parsed.add(new Term(canonical, aliases));
                }
                return List.copyOf(parsed);
            }
        } catch (Exception e) {
            log.warn("业务术语表加载失败（{}），跳过查询扩展：{}", location, e.toString());
            return List.of();
        }
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).strip();
        return s.isEmpty() ? null : s;
    }

    private static List<String> strList(Object o) {
        if (o instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::strip).filter(s -> !s.isEmpty()).toList();
        }
        String s = str(o);
        return s == null ? List.of() : List.of(s);   // 容忍单值写法
    }
}
