package org.agent.memory;

import org.agent.permission.SessionEnvelope;
import org.agent.rag.retrieve.RetrievalAdvisor;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 写入侧的密级判定：算出"这一轮内容可能反映的最高密级"，供落库（纯函数，无状态）。
 *
 * <h2>① 为什么 L1 上的 guard 只能"算严"，不能"拒绝写入"</h2>
 * RAG-07 §三 的 {@code MemoryWriteGuard} 表里多行写着「拒绝写入」，但那是为 L2 设计的 ——
 * L1 是审计记录，回答的是"发生了什么"。任何拒绝写入都会在审计里留一个洞：
 * 用户问了、系统答了，却不留痕。§四 那句「任何进不了 L1 的记忆都不该进 L2」
 * 也说明拒绝语义属于 L2，L1 只是它的数据源。
 * <p>
 * 所以本类是个纯函数：产出一个更严的密级值，<b>一行都不少</b>。
 * §三 表里的四项检查在 L1 上逐条落空，理由：
 * <ul>
 *   <li><b>归属</b> —— 已由阶段 0 的读取侧复核覆盖（{@code ChatMemoryService.toTurn} 比对
 *       {@code user_id} 与信封），写的时候再拒一遍是重复的</li>
 *   <li><b>scope</b> —— L1 恒为"本次会话"，表里根本没有 scope 列</li>
 *   <li><b>内容红线</b> —— L1 存的是答案全文，而 {@code CodeSniffer} 认的是代码不是凭证，
 *       拿它拦会拦掉正常业务答案（客户经理就是要抄条款原文给客户）</li>
 *   <li><b>来源</b> —— L1 行的来源就是它自己（本轮问答），无从"不可信"</li>
 * </ul>
 *
 * <h2>② 密级公式为什么是 max</h2>
 * {@code clearance = max(该轮引用资料的 doc_level)}；没有可判的资料时回退会话密级。
 * 答案可能反映其中任意一篇的内容，含密级最高的那一篇 —— 取 max 才配得上
 * "可能反映的最高密级"这句话。
 *
 * <h2>③ 为什么 {@code min(写入者密级, 资料密级)} 是个空操作</h2>
 * 文档早期写过这个式子，它是错的 —— 不只是"偏松"，而是<b>恒等于右边那一项</b>：
 * {@code RetrievalAdvisor} 写进 {@code CONTEXT_RETRIEVED_DOCS} 的是<b>过完两道门禁之后</b>
 * 的列表，而门禁保证 {@code doc_level <= envelope.dataClearanceLevel()}，于是
 * "引用资料的最高密级 ≤ 写入者密级"恒成立 → {@code min} 永远取到资料侧。
 * <p>
 * 也就是说 {@code min} 在干净数据上什么都不做，<b>只在 metadata 脏数据时才生效 ——
 * 而生效方向恰好是不安全的那一侧</b>：把密级 4 的内容标成 2，正是 D2「洗密级」通道。
 *
 * <h2>④ 刻意<b>不</b>按会话密级夹取上限</h2>
 * 已过门禁的资料其密级天然 ≤ 会话密级，所以"再夹一次"在干净数据上同样是空操作；
 * 而在脏数据上它只会把密级<b>调低</b>，与 ③ 是同一个错误。故取到多少就存多少。
 *
 * <h2>⑤ 本阶段的边界：存的是快照</h2>
 * 能堵住"用户被降级后仍能读到旧轮的高密级结论"（§一 表 #2），
 * <b>堵不住</b>"当时引用的那份制度后来被提升密级" —— 那需要读取时按活密级重判
 * 或异步重算，归阶段 4 巡检。这个边界是明确记下的缺口，不是没想到。
 */
public final class MemoryWriteGuard {

    /** 合法密级的下界，与 {@code RetrievalAdvisor.passesGate} 的 {@code lv < 1} 同一口径 */
    private static final int MIN_LEVEL = 1;

    private MemoryWriteGuard() {
    }

    /**
     * @param retrievedDocs 本轮检索命中的资料（{@code CONTEXT_RETRIEVED_DOCS}，已过门禁）；可为 null
     * @param envelope      本轮实况信封；仅在"没有可判的资料"时用到，用它的密级封顶
     * @return 该轮内容可能反映的最高密级。脏数据里的离群<b>高</b>值原样保留（越严越安全）
     */
    public static int clearanceFor(List<Document> retrievedDocs, SessionEnvelope envelope) {
        int max = 0;
        if (retrievedDocs != null) {
            for (Document doc : retrievedDocs) {
                if (doc == null) {
                    continue;
                }
                int level = RetrievalAdvisor.docLevelOf(doc);
                // 读不出数值（-1）或非法低值（0、负数）不参与取 max。
                // 注意这里**不用**夹成 MIN_LEVEL：夹上去等于替脏数据编了个密级，
                // "不参与"才诚实 —— 全都不参与时下面回退到会话密级，那才是更严的答案
                if (level >= MIN_LEVEL && level > max) {
                    max = level;
                }
            }
        }
        // 无资料、或资料密级全都读不出：无从判定内容来源，就按本轮用户能看到的天花板封顶。
        // 正常流程走不到这里（命中才有 PROCEED 轮，而命中就带 doc_level），
        // 这是给脏数据与将来新增的写入路径兜底 —— 宁严勿松
        return max >= MIN_LEVEL ? max : envelope.dataClearanceLevel();
    }
}
