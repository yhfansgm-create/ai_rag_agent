package org.agent.memory.service;

import org.agent.audit.AuditHashChain;
import org.agent.memory.dao.MemoryChangeLogDao;
import org.agent.memory.dao.MemoryChangeLogRow;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 审计链的<b>校验器</b>：读回 {@code memory_change_log} 全链，逐条重算哈希并检查连通性。
 *
 * <h2>为什么它必须存在</h2>
 * <b>没有校验器的哈希链是装饰品。</b>"可发现篡改（tamper-evident）"这个说法必须由
 * 一段<b>能跑</b>的代码定义，否则它只是一句写在 DDL 注释里的承诺 ——
 * 与 {@code FactGrounding} 必须有"能真的失败"的测试是同一个道理。
 * <p>
 * 本阶段它只被测试使用，但<b>现在就收进来</b>：它是 RAG-04 #13「审计表 + 哈希链」
 * 将来的消费方要直接复用的那个东西，而"将来再加"的代价是那时没人记得
 * {@code prevHash}/{@code payload} 这两条链是怎么接的。
 *
 * <h2>⚠️ 它能证明什么、不能证明什么</h2>
 * <ul>
 *   <li><b>能</b>：某一行被改过（{@code HASH_MISMATCH}）、链在某一处断过
 *       （{@code PREV_HASH_NOT_LINKED}）—— 完整列表见 {@code AuditHashChain.verify}</li>
 *   <li><b>不能</b>：阻止篡改。有 DB 写权限的人可以整条重写（连同 {@code prev_hash}），
 *       那样校验是<b>通过</b>的。所以本机制只到"可发现"，不到"防篡改"</li>
 *   <li><b>也不能</b>发现"应用层压根没记"这件事 —— 日志与业务行同一个事务，
 *       应用可以选择不调用 {@link MemoryAuditAppender}。生产解法是日志表单独授权
 *       （只授 INSERT）/触发器/WORM 存储，本阶段不做，
 *       补偿手段是每次追加后把 head hash 打一条 INFO 日志（外部锚点）</li>
 * </ul>
 *
 * <h2>本阶段全表读</h2>
 * {@code selectChain()} 按 {@code id ASC} 取全表。数据量小的时候这是最省事的正确做法；
 * 数据量上来后要改成分页 + 从检查点起校验，那时再谈 —— 现在做分页只会引入
 * "检查点存在哪"这个没有答案的问题。
 */
@Component
public class MemoryChangeLogVerifier {

    private final MemoryChangeLogDao dao;

    public MemoryChangeLogVerifier(MemoryChangeLogDao dao) {
        this.dao = dao;
    }

    /**
     * 校验全链。
     *
     * @return 全部不一致项；<b>空列表 = 链完整</b>（空表也返回空列表 ——
     *         "什么都没有"不是"不一致"）
     */
    public List<AuditHashChain.Mismatch> verifyChain() {
        List<AuditHashChain.AuditEntry> entries = dao.selectChain().stream()
                .map(MemoryChangeLogRow::toAuditEntry)
                .toList();
        return AuditHashChain.verify(entries);
    }
}
