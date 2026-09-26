package org.agent.memory;

import org.agent.memory.model.FactDraft;
import org.agent.memory.model.FactKey;

import java.util.Optional;

/**
 * 写入门禁：一条候选事实能不能落库（纯函数，无 IO，无状态）。
 *
 * <h2>⚠️ L2 可以拒写，L1 不能 —— 这是两侧刻意的不对称</h2>
 * {@code MemoryWriteGuard}（L1）只过滤、不拒绝：L1 是<b>审计记录</b>，拒一次就在
 * 「当时到底说了什么」上留一个洞（见它的类注释）。L2 是<b>加工产物</b>：
 * 一条不满足条件的记忆没有留下的必要，"少记一条"没有审计代价。
 * 所以本类<b>可以</b>直接返回拒绝，而 {@code MemoryWriteGuard} 一行不改。
 * <p>
 * 这两套门禁看起来像重复，实际判据完全不同：L1 判「你能不能看它」（权限），
 * L2 判「它值不值得被记住」（质量 + 安全）。前者不能拒，后者必须拒。
 *
 * <h2>检查顺序（顺序即优先级）</h2>
 * <ol>
 *   <li><b>词表</b> —— 键不认识就到此为止，后面每一项都要键</li>
 *   <li><b>档位</b> —— {@code FORBIDDEN} / {@code CONFIRM} 都不写。本阶段没有
 *       {@code PENDING} 状态机，所以"需确认"的落地形态就是"不写"</li>
 *   <li><b>值形态</b>（空 → 换行 → 归一化 → 空 → 超长 → 字符白名单）</li>
 *   <li><b>内容红线</b> —— 凭证类内容，拒绝即审计</li>
 *   <li><b>复述验证</b> —— 本阶段的安全核心</li>
 *   <li><b>配额</b> —— 最后判，因为它是"资源"而不是"对错"</li>
 * </ol>
 * 便宜的在前、确定在后：词表与档位是纯查表，复述验证要跑正则，配额要走一次调用方
 * 已经算好的计数。<b>顺序错了不只是性能问题</b> —— 若把配额放前面，一个超长的
 * 垃圾候选会以 {@code QUOTA_EXCEEDED} 被记下，于是审计里"为什么没记住"的答案是错的。
 *
 * <h2>⚠️ 换行检查必须在归一化<b>之前</b></h2>
 * {@code forStorage} 会把连续空白折成一个空格 —— 包括换行。所以在它<b>之后</b>
 * 再查换行，那个换行已经不在了，检查恒为 false，成了一个永远不会失败的空操作
 * （阶段 1 的 {@code min} 就是这个形态）。这是本类里最容易被"整理代码顺序"破坏的一处，
 * 有测试锁住（{@code FactWritePolicyTest} 里带换行的值必须拒在 {@code VALUE_HAS_LINE_BREAK}）。
 *
 * <h2>❗没有"来源非空"这一项检查，这是刻意的</h2>
 * 计划里列了九项，第九项是"来源非空"。它<b>不在本类</b>，因为它在本类拿不到：
 * 候选事实里没有轮次号（{@code FactDraft} 也没有，见其注释"归属由构造消解"），
 * 来源是调用方把 {@code turnNo} 传进来的那个参数。硬塞一个本类看不见的参数进来，
 * 只会让"检查过了"变成一句假象 —— 比不检查更糟。
 * <p>
 * 它真正的落点是<b>执行顺序</b>：调用方必须<b>先</b> {@code appendTurn} 拿到 {@code turnNo}、
 * <b>再</b>提交蒸馏，且没有 {@code turnNo} 就不提交。<b>无源不写靠执行顺序保证，不靠注释。</b>
 *
 * <h2>❗没有 GLOBAL scope 检查，也是刻意的</h2>
 * 计划里"GLOBAL 读写皆拒"的那条拒绝，实现形态是<b>没有写入路径</b>：
 * {@code FactDraft} 里没有 scope 字段、写出的一律是 {@code USER}。
 * 既然没有任何代码能造出一个 GLOBAL 行，本类里也就没有可判之处 ——
 * 详见 {@code FactScope} 的注释（声明了但永不被产出的枚举值，比"产出了再拒绝"更安全）。
 */
public final class FactWritePolicy {

    /**
     * 拒写理由。同时是<b>日志与审计的分诊维度</b>。
     * <p>
     * ⚠️ 其中只有 {@link #REDLINE_VALUE} 会写变更日志（{@code GUARD_REJECT}）：
     * 其余都是例行的质量过滤，写进去只会把审计表灌满"从未存在过的记忆"。
     * 这条区分写在 {@code MemoryChangeAction} 的注释里，此处只做呼应。
     */
    public enum RejectReason {

        /** 词表里没有这个键（模型自造了一个） */
        UNKNOWN_KEY,
        /** 该键被明确列为"不自动写"（具体客户、评级结论…） */
        TIER_CONFIRM,
        /** 该键被明确列为"记忆层不该承载"（金额阈值…）。见 {@code FactKey.AMOUNT_THRESHOLD} */
        TIER_FORBIDDEN,
        /** 值为空（或归一化后只剩分隔符） */
        VALUE_EMPTY,
        /** 值含换行/控制字符 —— 注入面加固，见 {@link FactValueNormalizer#hasLineBreakOrControl(String)} */
        VALUE_HAS_LINE_BREAK,
        /** 值超过 {@link FactValueNormalizer#MAX_VALUE_CHARS} */
        VALUE_TOO_LONG,
        /** 值含白名单外的字符（零宽、双向控制符等） */
        VALUE_BAD_CHARS,
        /** 值命中凭证红线 —— <b>唯一会写审计的拒绝</b> */
        REDLINE_VALUE,
        /** 没能在该轮用户原话里找到要素支撑（模型的推测） */
        NOT_GROUNDED,
        /** 该用户的有效事实已达上限，且这次要新增而不是取代 */
        QUOTA_EXCEEDED
    }

    /**
     * 配额判定所需的两个数 —— <b>由调用方算好传进来</b>，本类不查库（纯函数的代价与收益）。
     *
     * @param activeCount      该用户当前有效事实数（调用方查 {@code countActive}）
     * @param limit            上限（配置项，默认 200）
     * @param keyAlreadyActive 该 {@code (owner, scope, fact_key)} 是否<b>已有</b>有效行。
     *                         ⚠️ 这个字段是配额逻辑的关键：已有有效行的写入是
     *                         <b>取代或续期</b>，不增加行数，所以<b>无条件放行</b>。
     *                         否则用户会卡在"200 条满了 → 想改口也改不了"，
     *                         而改口恰恰是记忆最该支持的事
     */
    public record Quota(int activeCount, int limit, boolean keyAlreadyActive) {

        /** 是否超限（取代/续期永不算超限） */
        public boolean exceeded() {
            return !keyAlreadyActive && activeCount >= limit;
        }
    }

    /**
     * 判定结果。
     *
     * @param reject  拒绝理由；<b>{@code null} 表示放行</b>（照 {@code ChatTurn} 那类
     *                "空值即默认态"的先例，而不是再加一个 {@code boolean allowed} ——
     *                两个字段表达同一件事就一定会有一天不一致）
     * @param key     解析出的键；{@code UNKNOWN_KEY} 时为 {@code null}
     * @param value   <b>归一化后</b>的值；未走到归一化那一步时为 {@code null}
     * @param redline 仅 {@code REDLINE_VALUE} 时非空。
     *                它是这里唯一一个"拒绝理由需要携带细节"的情形（审计要记
     *                {@code pattern} 与 {@code valueLength}），而它的携带形态本身
     *                就是安全设计：{@link FactRedlines.Hit} 里<b>没有原值</b>
     */
    public record Decision(RejectReason reject, FactKey key, String value, FactRedlines.Hit redline) {

        public boolean allowed() {
            return reject == null;
        }

        static Decision reject(RejectReason reason, FactKey key) {
            return new Decision(reason, key, null, null);
        }

        static Decision allow(FactKey key, String value) {
            return new Decision(null, key, value, null);
        }
    }

    private FactWritePolicy() {
    }

    /**
     * 逐项判定一条候选事实。
     *
     * @param draft            模型提出的候选（可能为 {@code null} —— 模型返回了空条目）
     * @param userMessage      该轮用户原话，复述验证的<b>唯一</b>证据来源
     * @param groundingEnabled 复述验证开关（{@code rag.memory.semantic.grounding-enabled}）。
     *                         ⚠️ 关掉它 = 模型的推测也会落库 —— 这是留给"复现漏洞现场"的
     *                         对比开关，默认必须是 {@code true}。参数化而不是读全局配置，
     *                         是为了让"开着/关着"两条路径都能被同一个纯函数直接测到
     * @param quota            配额判定所需的数（见 {@link Quota}）
     */
    public static Decision decide(FactDraft draft, String userMessage,
                                  boolean groundingEnabled, Quota quota) {
        // ① 词表：键不认识就到此为止
        Optional<FactKey> resolved =
                FactKey.resolve(draft == null ? null : draft.rawKey());
        if (resolved.isEmpty()) {
            return Decision.reject(RejectReason.UNKNOWN_KEY, null);
        }
        FactKey key = resolved.get();

        // ② 档位。⚠️ 本阶段 CONFIRM 与 FORBIDDEN 的**结果**一样（都不写），
        //    但理由必须分开记：一个是"这类事实需要人工确认"（将来会变成 PENDING），
        //    一个是"记忆层整体不该承载"（永远不会写）。合并成一个理由会让阶段 4
        //    没法把它们分开处理 —— 而它们将来要走的路完全不同。
        if (key.tier() == FactKey.Tier.FORBIDDEN) {
            return Decision.reject(RejectReason.TIER_FORBIDDEN, key);
        }
        if (key.tier() == FactKey.Tier.CONFIRM) {
            return Decision.reject(RejectReason.TIER_CONFIRM, key);
        }

        // ③ 值形态
        String raw = draft.rawValue();
        if (FactValueNormalizer.isBlank(raw)) {
            return Decision.reject(RejectReason.VALUE_EMPTY, key);
        }
        // ⚠️ 必须在 forStorage 之前 —— 它会把换行折成空格，此后这个检查恒为 false
        if (FactValueNormalizer.hasLineBreakOrControl(raw)) {
            return Decision.reject(RejectReason.VALUE_HAS_LINE_BREAK, key);
        }
        String value = FactValueNormalizer.forStorage(raw);
        if (value.isEmpty()) {
            // 归一化的副作用：值可能只剩分隔符（"、、"）
            return Decision.reject(RejectReason.VALUE_EMPTY, key);
        }
        if (FactValueNormalizer.charCount(value) > FactValueNormalizer.MAX_VALUE_CHARS) {
            return Decision.reject(RejectReason.VALUE_TOO_LONG, key);
        }
        if (!FactValueNormalizer.hasOnlyAllowedChars(value)) {
            return Decision.reject(RejectReason.VALUE_BAD_CHARS, key);
        }

        // ④ 内容红线。顺序在复述验证之前：凭证类内容不该等"先验证它是不是用户说的" ——
        //    它**确实是**用户说的（原话里就有卡号），复述验证拦不住它，只有这一步拦得住。
        //    这也正是"值长度上限 + 字符白名单 + 禁换行 + 显式声明"那条纵深防线里
        //    最靠前的一环（记忆是第二条注入面）。
        Optional<FactRedlines.Hit> hit = FactRedlines.scan(value);
        if (hit.isPresent()) {
            return new Decision(RejectReason.REDLINE_VALUE, key, value, hit.get());
        }

        // ⑤ 复述验证：本阶段的安全核心
        if (groundingEnabled) {
            FactGrounding.Verdict verdict = FactGrounding.check(key, value, userMessage);
            if (!verdict.grounded()) {
                return Decision.reject(RejectReason.NOT_GROUNDED, key);
            }
        }

        // ⑥ 配额
        if (quota != null && quota.exceeded()) {
            return Decision.reject(RejectReason.QUOTA_EXCEEDED, key);
        }

        return Decision.allow(key, value);
    }
}
