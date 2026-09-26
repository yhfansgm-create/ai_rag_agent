package org.agent.memory.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * L2 语义记忆的<b>受控词表</b> —— 本包内"能记什么"的唯一真相源。
 *
 * <h2>为什么必须是受控词表，而不是让模型自由发挥</h2>
 * 自由词表意味着 {@code fact_key} 的取值空间由模型决定，于是：
 * <ul>
 *   <li>{@code supersede} 会失效 —— 同一个概念的两种叫法（"业务条线"/"业务线"）会各自
 *       成为独立的 key，用户改口时永远替代不上（{@code uk_owner_active} 只对同 key 生效）</li>
 *   <li>配额会被同义名称吃光</li>
 *   <li>没法给每个 key 定不同的 TTL、许可词、档位 —— 而这些正是本层安全的全部依据</li>
 * </ul>
 * 代价是<b>词表外的新事实类型记不到</b>（缺口 7：需要人工扩这张表）。这是刻意接受的：
 * 少记一条事实只是体验损失，记错一条事实是安全问题。
 *
 * <h2>{@code code} 与 {@code label} 的分工（DDL 里那处偏离的理由）</h2>
 * <b>存进库的是 {@code code}（ASCII），不是 {@code label}（中文）</b>。
 * 理由是 {@code label} 活在<b>提示词</b>里，一旦有人把「关注条线」改成「业务条线」，
 * 库里旧行的 key 立刻变成孤儿，而 supersede 会<b>静默失效</b>（不报错，只是永远替代不上）。
 * 用 ASCII code 把"稳定标识"与"展示文本"解耦，展示文本由 {@link #label()} 提供。
 *
 * <h2>{@link Tier} —— 档位决定"这条能不能自动写"</h2>
 * 用户已定：<b>只做两档</b>（自动写 / 直接不写），<b>没有 PENDING 状态机</b>。
 * 所以 {@link Tier#CONFIRM} 与 {@link Tier#FORBIDDEN} 的<b>行为相同</b>（都拒写），
 * 区别只在审计理由（{@code TIER_CONFIRM} / {@code TIER_FORBIDDEN}）与"将来会不会放开"：
 * CONFIRM 是"缺一个确认流程"，FORBIDDEN 是"永远不该由自动蒸馏写"。
 * <p>
 * ⚠️ <b>FORBIDDEN 的键仍然留在词表里，这是刻意的</b>：若把它们删掉，模型提出
 * "金额阈值"时会被判成 {@code UNKNOWN_KEY}，与"拼错了一个新概念"混为一谈。
 * 留在词表里有两个好处：① 提示词能把它们渲染成显式的"禁止记录"——
 * <b>否定式的词表比缺席的词表更能约束模型</b>；② 审计理由能区分
 * "这个类别我不该记"与"这个词我不认识"。
 */
public enum FactKey {

    // ------------------------------------------------------------------
    // AUTO 档：四个自动写入的事实键。⚠️ 全部带 TTL
    // ------------------------------------------------------------------

    /**
     * 业务条线：用户主要做对公还是对私。
     * <p>
     * TTL 最长（365 天）：条线归属是这四个里最稳定的一条 —— 转岗是低频事件。
     */
    BUSINESS_LINE("business_line", "业务条线", Tier.AUTO, 365,
            true, false,
            new String[]{"对公", "对私", "条线", "业务线", "公司业务", "零售", "同业", "个金"},
            new String[]{"业务条线", "业务线", "条线", "主线"}),

    /**
     * 常用机构：用户常跑的网点 / 支行。
     * <p>
     * TTL 180 天：轮岗、调店是中等频率事件。
     * <p>
     * ⚠️ 这个词与 L5 权限画像里的"所属机构"<b>不是一回事</b>：画像是"你有权看什么"（权限），
     * 这里是"你平时看什么"（偏好）。两者可以不一致，而且<b>偏好绝不能反过来影响权限</b> ——
     * 本键只用于排序与提示，不参与任何 access 判定。
     */
    PREFERRED_ORG("preferred_org", "常用机构", Tier.AUTO, 180,
            true, false,
            new String[]{"网点", "支行", "分行", "机构", "常去", "常跑", "行号"},
            new String[]{"常用机构", "常用网点", "常去的网点", "常跑机构"}),

    /**
     * 关注主题：用户常查的业务主题。
     * <p>
     * TTL 最短（90 天）：主题是这四个里变得最快的（一个季度的业务重点会换）。
     * <b>允许挂 {@code ref_doc_id}</b>：主题天然对应某份制度文档，这是唯一一个
     * "指针比值更有用"的键。
     */
    FOCUS_TOPIC("focus_topic", "关注主题", Tier.AUTO, 90,
            true, true,
            new String[]{"关注", "常查", "主要看", "研究", "主题", "经常问", "老是问"},
            new String[]{"关注主题", "关注的话题", "常查主题"}),

    /**
     * 偏好产品：用户常办的产品类别。
     * <p>
     * TTL 180 天。
     */
    PREFERRED_PRODUCT("preferred_product", "偏好产品", Tier.AUTO, 180,
            true, false,
            new String[]{"产品", "存单", "理财", "贷款", "基金", "保险", "大额存单", "经常办"},
            new String[]{"偏好产品", "常用产品", "常办的产品"}),

    // ------------------------------------------------------------------
    // CONFIRM 档：需要人工确认才该写。本阶段没有确认流程 ⇒ 一律拒写
    // ------------------------------------------------------------------

    /**
     * 关注客户。⚠️ <b>涉及第三方隐私</b>，且它不是"关于本用户的偏好" ——
     * 记住"某客户经理在盯某个客户"这件事，风险与被记住的客户本人有关，
     * 而那个客户<b>从没同意过</b>。这类事实必须走人工确认（阶段 4）。
     */
    WATCHED_CLIENT("watched_client", "关注客户", Tier.CONFIRM, 90,
            false, false,
            new String[]{"客户", "这个户", "那家企业", "盯着"},
            new String[]{"关注客户", "重点客户", "在盯的客户"}),

    /**
     * 评级结论。⚠️ 结论类事实一旦被记忆固化，就会在<b>依据已经变化之后</b>继续影响模型
     * （"这家是 A 级"—— 而评级早就下调了）。它必须回原文取，不该进记忆。
     * 归 CONFIRM 而不是 FORBIDDEN：人工确认过的结论是有正当用途的（阶段 4）。
     */
    RATING_CONCLUSION("rating_conclusion", "评级结论", Tier.CONFIRM, 90,
            false, false,
            new String[]{"评级", "等级", "评分", "定性"},
            new String[]{"评级结论", "风险评级", "客户评级"}),

    // ------------------------------------------------------------------
    // FORBIDDEN 档：永远不该由自动蒸馏写
    // ------------------------------------------------------------------

    /**
     * 金额 / 比例 / 期限阈值。⚠️ <b>永远不写</b>，理由与
     * {@code NumericGuardrailAdvisor} 逐字一致：<b>数字必须有原文出处，不能被记忆携带</b>。
     * 一条被记下来的"20 万"在费率调整之后就是错的，而它还会被注入未来的每一轮 prompt。
     * <p>
     * 这也是 {@code LlmSummaryWriter} 那条"摘要里不许出现数字"的同一个立场：
     * <b>记忆层整体不承载事实性数字</b>，数字一律回原文取。
     */
    AMOUNT_THRESHOLD("amount_threshold", "金额比例期限阈值", Tier.FORBIDDEN, 0,
            false, false,
            new String[]{"金额", "利率", "比例", "期限", "起存", "多少万"},
            new String[]{"金额阈值", "金额", "利率", "期限"});

    /** 事实的可写档位。见类注释：本阶段 CONFIRM 与 FORBIDDEN 的行为相同（都拒写） */
    public enum Tier {
        /** 自动写入 */
        AUTO,
        /** 需人工确认 —— 本阶段无确认流程，故拒写（理由 {@code TIER_CONFIRM}） */
        CONFIRM,
        /** 永不自动写入（理由 {@code TIER_FORBIDDEN}） */
        FORBIDDEN
    }

    private final String code;
    private final String label;
    private final Tier tier;
    private final int ttlDays;
    private final boolean autoWritable;
    private final boolean refDocAllowed;
    private final String[] licenseTerms;
    private final String[] aliases;

    FactKey(String code, String label, Tier tier, int ttlDays,
            boolean autoWritable, boolean refDocAllowed,
            String[] licenseTerms, String[] aliases) {
        this.code = code;
        this.label = label;
        this.tier = tier;
        this.ttlDays = ttlDays;
        this.autoWritable = autoWritable;
        this.refDocAllowed = refDocAllowed;
        this.licenseTerms = licenseTerms;
        this.aliases = aliases;
    }

    /** 落库的值（ASCII）。⚠️ 不要改成 label() —— 那会让既有行变孤儿，见类注释 */
    public String code() {
        return code;
    }

    /** 展示文本（中文），只用在提示词里 */
    public String label() {
        return label;
    }

    public Tier tier() {
        return tier;
    }

    /**
     * 保质期（天）。0 = 不设过期。
     * <p>
     * ⚠️ {@code expires_at} 由 Java 按这个值算，<b>模型无权输出</b> ——
     * 留存策略不能交给模型判断。四个 AUTO 键全部 &gt; 0：真正永久稳定的事实
     * （部门 / 角色 / 密级）已经在 L5 权限画像里了，L2 存的本来就只有"会变的事实"。
     */
    public int ttlDays() {
        return ttlDays;
    }

    /** 是否允许自动蒸馏写入（= {@code tier() == AUTO}），与决策"只做两档"一致 */
    public boolean autoWritable() {
        return autoWritable;
    }

    /** 是否允许挂 {@code ref_doc_id} 指针 */
    public boolean refDocAllowed() {
        return refDocAllowed;
    }

    /**
     * 许可词：用户原话里<b>至少要出现其中一个</b>，才允许把这条事实归到本键下。
     * <p>
     * 这是 {@code FactGrounding} 四步里的第 2 步。它的作用不是"判断用户说了什么"，
     * 而是<b>挡住"模型换了个说法硬塞"</b>：用户没提过任何与"网点"有关的词，
     * 就不该冒出一条"常用机构"。缺失这一步的话，模型可以用一个泛泛的自述信号
     * （"我主要做这个"）把它编的任意 key 塞进来。
     */
    public String[] licenseTerms() {
        return licenseTerms.clone();
    }

    /**
     * 解析模型给出的键名（<b>这个词表与提示词的唯一接口</b>）。
     * <p>
     * 容错是必需的，不是好心：提示词里渲染给模型看的是 {@link #label()}（中文），
     * 而模型完全可能照抄 label、照抄 code、或大小写不标准。若只认 code，
     * 那模型每次答中文标签都会被判 {@code UNKNOWN_KEY} —— 表现为"L2 永远不写"，
     * 而且日志里看不出原因（每条都"键不认识"）。
     * <p>
     * ⚠️ 别名只做<b>整串</b>匹配，不做包含匹配：包含匹配会让"业务线"这种短别名
     * 命中"业务线相关的其他东西"，把两个不同的键搅在一起。
     *
     * @return 匹配到的键；不认识则 {@code empty}（调用方据此判 {@code UNKNOWN_KEY}，
     *         <b>绝不做"最接近的一个"这种猜测</b> —— 猜错的键会静默污染 supersede）
     */
    public static Optional<FactKey> resolve(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String needle = raw.strip().toLowerCase(Locale.ROOT);
        for (FactKey key : values()) {
            if (key.code.equals(needle) || key.label.equals(raw.strip())) {
                return Optional.of(key);
            }
            if (Arrays.stream(key.aliases).anyMatch(a -> a.equals(raw.strip()))) {
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }

    /**
     * 渲染成提示词里的一段词表。放在枚举上而不是提示词常量里，是为了<b>不可能漂移</b>：
     * 加一个键就自动出现在提示词里，不存在"忘了同步"这种状态。
     *
     * @param tier 只渲染该档位
     */
    public static String renderForPrompt(Tier tier) {
        StringBuilder sb = new StringBuilder();
        for (FactKey key : values()) {
            if (key.tier != tier) {
                continue;
            }
            sb.append("- ").append(key.code).append("（").append(key.label).append('）');
            if (tier == Tier.AUTO) {
                sb.append("；用户提及的线索词：")
                        .append(String.join("、", key.licenseTerms));
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
