package org.agent.rag.ingest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 入库版本收敛 —— 回答"同一份制度有三个版本，哪版是现行的？"
 * <p>
 * 这是 {@link org.agent.rag.TemporalGate} 的<b>上游</b>。TemporalGate 在检索侧问"这条 chunk 今天能不能用"，
 * 但只有当每条 chunk 身上带着正确的 {@code effective_until}，它才问得出正确答案。
 * 现实中写制度的人只写"这版从哪天生效"，很少回头去补上一版的失效日 ——
 * 本类就是替他们把这件事算出来。
 * <p>
 * <b>核心规则</b>：同一 {@code doc_id} 的多个版本里，{@code effective_from} 最晚的那版是现行版
 * （并列时比 {@code version} 字符串，按数字段比较，"1.10" &gt; "1.9"）。
 * 其余版本若作者未声明 {@code effective_until}，自动推导为 <b>现行版生效日 − 1 天</b>，
 * 形成左右闭合且<b>不重叠</b>的区间 —— 两条法条同时生效，是合规上最不能接受的状态。
 * <p>
 * <b>为什么不删旧版本</b>：银行要能回答"2023 年那笔业务当时依据的是哪一版"，
 * 删除等于销毁证据。所以旧版本照常入库，只是被时间区间挡在现行口径之外 ——
 * 这也让本功能<b>不依赖向量库的 delete 能力</b>，纯元数据操作。
 */
@Slf4j
@Component
public class DocumentVersionResolver {

    /** 版本串里的片段：连续的纯数字 或 连续的纯字母 */
    private static final Pattern VERSION_SEGMENT = Pattern.compile("\\d+|[A-Za-z]+");

    /** 待判断的一个文档版本（只取版本收敛需要的字段，便于纯逻辑单测） */
    public record DatedDoc(String docId, String version, LocalDate effectiveFrom, LocalDate effectiveUntil) {
    }

    /** 对单个文档的调整裁定 */
    public record Adjustment(LocalDate effectiveUntil, String supersededBy, boolean current) {
        static Adjustment untouched() {
            return new Adjustment(null, null, true);
        }
    }

    /** 裁定结果 + 需要人工复核的告警 */
    public record Resolution(List<Adjustment> adjustments, List<String> warnings) {
    }

    /**
     * 对一批文档做版本收敛。
     *
     * @param docs  与待入库文档<b>顺序一一对应</b>
     * @param today 推导基准日（当前版本没写生效日时的兜底）；由调用方传入便于测试
     * @return {@code adjustments} 与入参同序同长
     */
    public Resolution resolve(List<DatedDoc> docs, LocalDate today) {
        List<Adjustment> result = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            result.add(Adjustment.untouched());
        }

        // 按 doc_id 归组。doc_id 缺失的文档不参与收敛：没有稳定标识就无法判断谁是谁的新版，
        // 硬猜只会把不相关的文档互相标成"已废止"——那比不收敛更危险
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < docs.size(); i++) {
            String docId = docs.get(i).docId();
            if (docId == null || docId.isBlank()) {
                continue;
            }
            groups.computeIfAbsent(docId, k -> new ArrayList<>()).add(i);
        }

        for (Map.Entry<String, List<Integer>> e : groups.entrySet()) {
            List<Integer> idx = e.getValue();
            if (idx.size() < 2) {
                continue;   // 单版本，无需收敛
            }

            // 从旧到新串成一条链。版本收敛的本质是给每个版本找它的"继任者"：
            // 必须逐段首尾相接（v1 止于 v2 生效前、v2 止于 v3 生效前），
            // 若一律拿"现行版生效日 − 1 天"，三版并存时中间那版就会一直"生效"到最新版生效前，
            // 与它自己的继任者区间重叠。
            List<Integer> chain = new ArrayList<>(idx);
            chain.sort((i, j) -> {
                if (later(docs.get(i), docs.get(j))) {
                    return 1;
                }
                return later(docs.get(j), docs.get(i)) ? -1 : 0;
            });

            int currentIdx = chain.get(chain.size() - 1);
            DatedDoc current = docs.get(currentIdx);
            LocalDate currentFrom = current.effectiveFrom();
            if (currentFrom == null) {
                warnings.add(String.format(
                        "文档 %s 的现行版本（version=%s）未声明 effective_from，旧版本失效日按今天 %s 倒推（%s）。"
                                + "该推断依赖入库当天，重跑可能得到不同结果，请补齐 front-matter 的 effective_from",
                        e.getKey(), current.version(), today, today.minusDays(1)));
            }

            for (int p = 0; p < chain.size() - 1; p++) {
                int i = chain.get(p);
                DatedDoc old = docs.get(i);
                // 取代它的永远是其直接继任者，不是最新版 —— 审计要能还原逐版的替换关系
                String supersededBy = label(docs.get(chain.get(p + 1)));
                String who = String.format("%s version=%s", e.getKey(), old.version());

                // 继任者没写生效日时退回现行版生效日，再退回今天
                LocalDate nextFrom = docs.get(chain.get(p + 1)).effectiveFrom();
                if (nextFrom == null) {
                    nextFrom = currentFrom != null ? currentFrom : today;
                }

                if (old.effectiveUntil() != null) {
                    // 作者已声明失效日：尊重人工判断，不覆盖
                    result.set(i, new Adjustment(null, supersededBy, false));
                    if (old.effectiveUntil().isAfter(nextFrom)) {
                        warnings.add(String.format(
                                "文档 %s 已声明 effective_until=%s，晚于继任版本的生效日 %s，两个版本存在重叠期。"
                                        + "请复核是否漏改了旧版本的失效日",
                                who, old.effectiveUntil(), nextFrom));
                    }
                    continue;
                }
                LocalDate until = nextFrom.minusDays(1);
                if (old.effectiveFrom() != null && until.isBefore(old.effectiveFrom())) {
                    warnings.add(String.format(
                            "文档 %s 推导出的失效日 %s 早于其生效日 %s（区间为空），该版本从未生效。"
                                    + "请检查 front-matter 的日期是否填错", who, until, old.effectiveFrom()));
                }
                result.set(i, new Adjustment(until, supersededBy, false));
            }

            log.info("版本收敛：{} 共 {} 个版本，现行版本为 version={}（生效日 {}），其余 {} 个版本失效日已推导",
                    e.getKey(), idx.size(), current.version(), currentFrom, idx.size() - 1);
        }

        return new Resolution(result, warnings);
    }

    /**
     * a 是否比 b 更"现行"。
     * <p>
     * 生效日在<b>双方都有</b>时才作数；只要有一方没写，就交给版本号裁决 ——
     * 刻意不采用"填了日期的比没填的优先"，因为那会让"新版本漏填 effective_from"这种
     * 最常见的填报疏漏，反过来把<b>旧版本</b>判成现行版，错得更隐蔽。
     * 版本号缺失时 compareVersion 返回负数，即"没版本号的排在后面"，同样合理。
     */
    private boolean later(DatedDoc a, DatedDoc b) {
        LocalDate fa = a.effectiveFrom();
        LocalDate fb = b.effectiveFrom();
        if (fa != null && fb != null) {
            int c = fa.compareTo(fb);
            if (c != 0) {
                return c > 0;
            }
            // 生效日相同（同日换版）→ 交给版本号
        }
        return compareVersion(a.version(), b.version()) > 0;
    }

    private static String label(DatedDoc d) {
        if (d.version() != null && !d.version().isBlank()) {
            return d.version();
        }
        if (d.effectiveFrom() != null) {
            return "生效于 " + d.effectiveFrom();
        }
        return "当前版本";
    }

    /**
     * 版本号比较：把版本串切成"数字段 / 字母段"两类片段后逐段比。
     * 数字段按数值比（"1.10" &gt; "1.9"，"v10" &gt; "v2"），数字段整体排在字母段之前（"2" &lt; "v2"）。
     * 用纯字符串比较会把 1.10 判成小于 1.9；用"只在非字母数字处切分"则会把 "v10" 当成一整段，
     * 于是 "v2" &gt; "v10" —— 两种常见写法都是错的。
     */
    static int compareVersion(String a, String b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null || a.isBlank()) {
            return -1;
        }
        if (b == null || b.isBlank()) {
            return 1;
        }
        List<String> as = segments(a);
        List<String> bs = segments(b);
        int n = Math.max(as.size(), bs.size());
        for (int i = 0; i < n; i++) {
            String x = i < as.size() ? as.get(i) : "";
            String y = i < bs.size() ? bs.get(i) : "";
            int c = compareSegment(x, y);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** 切成连续的"纯数字"或"纯字母"片段：v1.10-beta → [v, 1, 10, beta] */
    private static List<String> segments(String version) {
        List<String> out = new ArrayList<>();
        Matcher m = VERSION_SEGMENT.matcher(version);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private static int compareSegment(String x, String y) {
        if (x.isEmpty() && y.isEmpty()) {
            return 0;
        }
        if (x.isEmpty()) {
            return -1;
        }
        if (y.isEmpty()) {
            return 1;
        }
        boolean xn = x.chars().allMatch(Character::isDigit);
        boolean yn = y.chars().allMatch(Character::isDigit);
        if (xn && yn) {
            try {
                return Long.compare(Long.parseLong(x), Long.parseLong(y));
            } catch (NumberFormatException ignore) {
                // 超长数字串（如时间戳），退回字符串比较
            }
        }
        if (xn != yn) {
            return xn ? -1 : 1;
        }
        return x.compareToIgnoreCase(y);
    }

    /** 供调用方复用的"失效日 = 现行版生效日 − 1 天" */
    static LocalDate dayBefore(LocalDate d) {
        return d.minus(1, ChronoUnit.DAYS);
    }
}
