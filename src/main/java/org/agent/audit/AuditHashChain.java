package org.agent.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 审计哈希链（纯函数，无 IO，无状态）。
 *
 * <pre>
 *   hash = SHA256hex( join(U+001F, [prevHash, ...payload] ) )
 * </pre>
 *
 * <h2>输入形状是"一串有序字段"，不是"一个对象"</h2>
 * 这是刻意的：用对象的话，哈希就依赖<b>反射出来的字段顺序</b>，而字段顺序会随
 * 重命名、加字段、编译器等静默改变 —— 一旦变了，<b>整条历史链全部假失败</b>。
 * 让调用方显式给一个 {@code List<String>}，顺序就成了代码里看得见的东西
 * （{@code MemoryChangeLogRow.toAuditEntry()} 是第一个例子）。
 *
 * <h2>⚠️ 分隔符不是装饰</h2>
 * 若把字段直接拼起来，{@code ("ab", "c")} 与 {@code ("a", "bc")} 会得到同一个哈希 ——
 * 于是一处内容改写可以被"挪一个字符到隔壁字段"掩盖过去。哈希链的经典缺陷，
 * 有单测锁死。
 *
 * <h2>⚠️ null 与空串<b>必须</b>能区分开</h2>
 * 字段值为 null（如 {@code WRITE} 时没有 {@code before_json}）时，<b>不能</b>当成空串：
 * 那样 {@code (reason=null)} 与 {@code (reason="")} 会哈希相同，于是"把 NULL 改成空串"
 * 这种改动不留痕迹。所以 null 渲染成一个专用记号（{@link #NULL_TOKEN}），
 * 它不可能出现在正常字段值里（正常值里没有控制字符）。
 *
 * <h2>⚠️ JSON 字段：进哈希前必须过 {@link #canonicalJson(String)}</h2>
 * MySQL 的 JSON 列会<b>重排对象键、抹掉空白</b>。按写入时的字符串算哈希，
 * 事后 {@code SELECT} 回来重新校验会<b>假失败</b> —— 链上明明没人动过，校验却红了，
 * 那比不校验更糟（它会训练人忽略校验结果）。
 * <p>
 * ⚠️ 而且它的排键规则<b>不是字典序</b>（实测是"键短的在前，同长再按字典序"：
 * {@code {"alpha":1,"zeta":2}} 读回来是 {@code {"zeta": 2, "alpha": 1}}）。
 * 所以"用 {@code TreeMap} 排出字典序就当它规范了"这个最自然的假设<b>恰好是错的</b>。
 * 正确做法是两边都过本函数：由我们自己排键，不依赖数据库的顺序，也就与数据库无关。
 *
 * @see org.agent.audit
 */
public final class AuditHashChain {

    /**
     * 字段分隔符（U+001F，Unit Separator）。见类注释：它不是装饰。
     * <p>
     * ⚠️ 这里用<b>码点常量</b>而不是把 U+001F 写成字符字面量：控制字符写在
     * 源码里是<b>看不见</b>的，一次复制粘贴就能让它消失，而那会让"分隔符存在"这件事
     * 变成一句注释里的承诺。写成 {@code 0x1F} 之后，它是文件里读得出来的信息。
     * （char 的赋值允许这种常量窄化，不需要强制转换。）
     */
    public static final char SEPARATOR = 0x1F;

    /** {@link #NULL_TOKEN} 用的那个控制字符（U+0000）。同上：用码点，不写字面量 */
    private static final char NULL_MARK = 0x00;

    /**
     * null 字段的记号（U+0000）。
     * <p>
     * ⚠️ 用它而不是空串，是为了让"null 被改成空串"这种篡改<b>可发现</b>。
     * 用一个正常值里不可能出现的字符（控制字符）当记号，就不会与真实取值碰撞 ——
     * 而记忆层的事实值在写入前已经禁掉了控制字符（{@code FactWritePolicy}）。
     */
    public static final String NULL_TOKEN = String.valueOf(NULL_MARK);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuditHashChain() {
    }

    /**
     * 链上的一条记录（校验所需的全部信息）。
     *
     * @param prevHash 上一条的 {@code hash}；{@code null} = 链首
     * @param hash     本条内容的哈希
     * @param payload  有序字段。⚠️ <b>顺序是哈希的一部分</b> ——
     *                 换顺序等于换哈希，这正是我们要的（字段被调换位置属于篡改）
     */
    public record AuditEntry(String prevHash, String hash, List<String> payload) {
    }

    /**
     * 一处不一致。
     *
     * @param index  在<b>传入列表里的位置</b>（从 0 开始）。
     *               ⚠️ 不是数据库主键 —— 物理 id 不进 record（本项目约定），
     *               所以校验器只能报"第几条"，调用方拿它去对照自己查出来的行序
     * @param reason 不一致的类型
     */
    public record Mismatch(int index, Reason reason) {

        /** 两种不一致。分开报的理由见 {@link #PREV_HASH_NOT_LINKED} */
        public enum Reason {
            /**
             * 该条的 {@code hash} 与"用它自己的字段重算"的结果不符 ——
             * <b>这条被改过</b>
             */
            HASH_MISMATCH,
            /**
             * 该条的 {@code prevHash} 与上一条的 {@code hash} 对不上 ——
             * <b>它的前一条被改过、被删了，或者它自己被挪了位置</b>。
             * <p>
             * ⚠️ 这个理由必须与 {@link #HASH_MISMATCH} 分开：改动中间一行会
             * <b>同时</b>触发两条记录的不一致（它自己 + 它的下一条），
             * 而这两种不一致指向的问题完全不同（"内容被改" vs "链断了"）。
             * 混成一个理由，排查时就得自己猜是哪一种
             */
            PREV_HASH_NOT_LINKED
        }
    }

    /**
     * 计算一条记录的哈希。
     *
     * @param prevHash 上一条的哈希；{@code null} 表示链首
     * @param payload  有序字段；其中的 {@code null} 会用 {@link #NULL_TOKEN} 渲染
     */
    public static String hash(String prevHash, List<String> payload) {
        StringBuilder sb = new StringBuilder();
        sb.append(render(prevHash));
        if (payload != null) {
            for (String field : payload) {
                sb.append(SEPARATOR).append(render(field));
            }
        }
        return sha256Hex(sb.toString());
    }

    /**
     * 校验一条链。
     *
     * @param entries <b>按链序</b>（即数据库的 {@code id ASC}）排列的记录
     * @return 全部不一致；<b>空列表 = 链完整</b>。空链（无记录）也返回空列表 ——
     *         "什么都没有"不是"不一致"
     */
    public static List<Mismatch> verify(List<AuditEntry> entries) {
        List<Mismatch> mismatches = new ArrayList<>();
        if (entries == null) {
            return mismatches;
        }
        String expectedPrev = null;
        for (int i = 0; i < entries.size(); i++) {
            AuditEntry entry = entries.get(i);
            if (!sameLink(entry.prevHash(), expectedPrev)) {
                mismatches.add(new Mismatch(i, Mismatch.Reason.PREV_HASH_NOT_LINKED));
            }
            if (!hash(entry.prevHash(), entry.payload()).equals(entry.hash())) {
                mismatches.add(new Mismatch(i, Mismatch.Reason.HASH_MISMATCH));
            }
            // 下一条应当指向本条的 hash —— 用**存着的**那个值，不是重算出来的：
            // 重算值只用于判定本条是否被改，链的连通性必须按原样传递，
            // 否则改一行会把它下面的所有行都报成 HASH_MISMATCH（噪声淹掉信号）
            expectedPrev = entry.hash();
        }
        return mismatches;
    }

    /**
     * 把一段 JSON 规范化成"可哈希的确定形态"：<b>对象键递归排序 + 无多余空白</b>。
     * <p>
     * <b>幂等</b>：{@code canonicalJson(canonicalJson(x)) == canonicalJson(x)} ——
     * 这条性质正是"写入时算哈希、事后读回来重新校验"能对上号的前提。
     * <p>
     * ⚠️ <b>数组的顺序保持不变</b>：JSON 数组是有序的，重排数组元素是<b>真实的内容变化</b>，
     * 不该被"规范化"抹平（{@code [1,2]} 与 {@code [2,1]} 必须哈希不同）。
     * 只有对象是"无序键值集合"，排序才是合法的规范化。
     *
     * @param json 原始 JSON；{@code null} / 空白原样返回 {@code null}
     * @throws IllegalArgumentException JSON 不合法。
     *         <b>刻意抛异常而不是退回原始字符串</b>：退回的话哈希就建立在一段
     *         未规范化的文本上，而唯一的后果是"将来某次校验假失败"——
     *         把错误推到很久以后、且推到一个更难定位的地方
     */
    public static String canonicalJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Object parsed = MAPPER.readValue(json, Object.class);
            return MAPPER.writeValueAsString(sortKeys(parsed));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("审计快照不是合法 JSON，不能进哈希链", e);
        }
    }

    /** 递归把对象转成按键排序的 {@code TreeMap}；数组保持顺序，只对元素递归 */
    private static Object sortKeys(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), sortKeys(entry.getValue()));
            }
            return sorted;
        }
        if (node instanceof List<?> list) {
            List<Object> ordered = new ArrayList<>(list.size());
            for (Object item : list) {
                ordered.add(sortKeys(item));
            }
            return ordered;
        }
        return node;
    }

    /**
     * 链首判定：{@code null} 与空串视为同一件事。
     * <p>
     * 放宽这一处是有意的：数据库里 {@code prev_hash} 链首是 {@code NULL}，
     * 而 {@code CHAR} 列在某些驱动/手工插入下会读回空串。这两种写法表达的是
     * 同一个事实（"我上一条不存在"），把它们判成不一致只会制造噪声，
     * 而噪声会让真正的篡改被忽略。
     */
    private static boolean sameLink(String a, String b) {
        return blankToNull(a) == null ? blankToNull(b) == null : blankToNull(a).equals(blankToNull(b));
    }

    private static String blankToNull(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }

    private static String render(String field) {
        return field == null ? NULL_TOKEN : field;
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须实现的算法，走到这里说明运行环境坏了 ——
            // 不是"业务异常"，直接抛，别假装能继续
            throw new IllegalStateException("运行环境缺少 SHA-256", e);
        }
    }
}
