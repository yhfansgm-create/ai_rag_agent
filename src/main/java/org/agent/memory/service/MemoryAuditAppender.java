package org.agent.memory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.agent.audit.AuditHashChain;
import org.agent.memory.dao.MemoryChangeLogDao;
import org.agent.memory.dao.MemoryChangeLogRow;
import org.agent.memory.model.MemoryActorType;
import org.agent.memory.model.MemoryChangeAction;
import org.agent.memory.model.MemoryEntityType;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计链的<b>追加器</b>：把一次记忆变更接进 {@code memory_change_log} 的哈希链。
 *
 * <h2>为什么值得单独一个类</h2>
 * L2 事实（{@code MemoryFactWriter}）与摘要（{@code SummaryWriterService}）都要追加审计行，
 * 而"取链尾 hash → 算 hash → 插入"这三步只要有一处写错，破坏的不是一行日志而是<b>整条链</b>
 * （事后校验会把没被篡改的历史行也报成不一致）。一份实现、两个消费方，就没有
 * "改了这边忘了那边"这种状态 —— 与 {@code MemoryBlockRenderer} 合并两段区块裁剪理由相同。
 *
 * <h2>⚠️ 必须在调用方的事务里被调用</h2>
 * {@link MemoryChangeLogDao#selectHeadHashForUpdate()} 的 {@code FOR UPDATE} 是<b>链的串行化点</b>：
 * 两个并发事务若都读到同一个链尾，各自追加一条、各自指向同一个 {@code prevHash}，
 * 链就<b>分叉</b>了 —— 那正是哈希链最典型的失效形态。
 * <p>
 * 所以本类<b>刻意不加</b> {@code @Transactional}：加了就变成"每次追加一个独立事务"，
 * 于是"业务行与审计行原子地一起成立或一起不成立"这条保证就没了
 * （审计说写了、业务行却回滚了，或反过来）。它由<b>调用方</b>的事务包住 ——
 * 调用方是 {@code MemoryFactWriter} / {@code SummaryWriterService}（各自 {@code @Transactional}）。
 * 也因此它<b>不是</b>同类自调用：跨 bean 调用才走得到代理。
 *
 * <h2>⚠️ {@code createdAt} 必须由 Java 给，且截到秒</h2>
 * 哈希算的就是这个时间。让 DB 填的话时间戳就在哈希的保护范围之外 ——
 * 攻击者可以随意改"何时记录的"而不破坏链（见 {@code MemoryChangeLogRow}）。
 * 这里取 {@code withNano(0)} 是为了让<b>内存里的行与库里的行逐字段相等</b>：
 * 列是 {@code DATETIME(0)}，不存小数秒，不截的话插入前算的 hash 与读回来后重算的
 * 会不一样（{@code hashableCreatedAt()} 已经兜住了这一层，这里只是让两者形状一致）。
 *
 * <h2>⚠️ {@code GUARD_REJECT} 的快照里绝不能放被拒的值</h2>
 * 那是全项目唯一一处"<b>拒绝理由自带细节</b>"的追加（{@code REDLINE_VALUE}），
 * 而细节只允许是 {@code {factKey, pattern, valueLength}} —— 把值抄进去等于
 * 把疑似凭证又存了一份，<b>审计本身成了泄露面</b>。这条约束由调用方遵守，
 * 本类只负责"给它什么就存什么"，不替它做内容判断（本类看不到红线规则，也不该看到）。
 */
@Slf4j
@Component
public class MemoryAuditAppender {

    private final MemoryChangeLogDao dao;
    private final ObjectMapper objectMapper;

    public MemoryAuditAppender(MemoryChangeLogDao dao, ObjectMapper objectMapper) {
        this.dao = dao;
        this.objectMapper = objectMapper;
    }

    /**
     * 追加一条审计记录并返回它（含算好的 {@code hash}）。
     *
     * @param entityType     被变更的实体类型（{@code FACT} / {@code SUMMARY}）
     * @param factUid        被变更的记忆业务键；<b>{@code GUARD_REJECT} 时为 {@code null}</b>
     *                       （那次候选从未成为一条记忆，编一个假 id 比留空更糟）
     * @param action         变更动作
     * @param beforeSnapshot 变更前快照（会被序列化成 JSON）；{@code WRITE} 时传 {@code null}
     * @param afterSnapshot  变更后快照。⚠️ {@code GUARD_REJECT} 时<b>只放类别信息</b>，见类注释
     * @param actorType      发起方。本阶段恒为 {@code AUTO}（模型自动蒸馏）
     * @param reason         变更原因，如 {@code REFRESH_SAME_VALUE} / {@code REDLINE_VALUE}
     * @return 已插入的那一行。<b>它带着算好的 hash</b>，调用方与测试可以直接拿去喂校验器 ——
     *         不必回查数据库（回查还要处理"全库共享表里哪一行是我的"这件事）
     */
    public MemoryChangeLogRow append(MemoryEntityType entityType, String factUid,
                                     MemoryChangeAction action,
                                     Object beforeSnapshot, Object afterSnapshot,
                                     MemoryActorType actorType, String actorId, String reason) {
        MemoryChangeLogRow staged = new MemoryChangeLogRow(entityType, factUid, action,
                toJson(beforeSnapshot), toJson(afterSnapshot),
                actorType, actorId, reason,
                // 链尾必须在**本事务内**读，见类注释。空链时返回 null = 我是链首
                dao.selectHeadHashForUpdate(),
                null,
                LocalDateTime.now().withNano(0));
        MemoryChangeLogRow row = withHash(staged);
        dao.insert(row);

        // 低成本补偿：把 head hash 打进 INFO 日志，等于给链留一个**外部锚点**。
        // 本机制只能说 tamper-evident（可发现），不能说 tamper-proof（防篡改）——
        // 有 DB 写权限的人可以整条重写，但改不了已经流出去的日志行
        log.info("记忆审计链追加：entity={} action={} reason={} factUid={} prevHash={} hash={}",
                entityType, action, reason, factUid, row.prevHash(), row.hash());
        return row;
    }

    /**
     * 算 hash 并填进行里。
     * <p>
     * 走"先建一行（hash 为 null）→ 算 → 再建一行"这两步，是因为
     * {@code hash} 与 {@code payload()} 互为因果：哈希的输入里<b>不含</b> hash 自己
     * （那是必须的，否则没有不动点）。所以只能先有形、再算、再成形。
     * <p>
     * ⚠️ 哈希的输入是 {@code payload()} 而不是"这一行的字段" —— 顺序由
     * {@code MemoryChangeLogRow.payload()} 显式给出，改动那个顺序会让<b>全部历史行同时假失败</b>，
     * 所以它刻意放在行模型上而不是这里。
     */
    private static MemoryChangeLogRow withHash(MemoryChangeLogRow staged) {
        return new MemoryChangeLogRow(staged.entityType(), staged.factUid(), staged.action(),
                staged.beforeJson(), staged.afterJson(), staged.actorType(), staged.actorId(),
                staged.reason(), staged.prevHash(),
                AuditHashChain.hash(staged.prevHash(), staged.payload()),
                staged.hashableCreatedAt());
    }

    /**
     * 快照 → JSON 文本。
     * <p>
     * ⚠️ 快照里的时间一律由调用方先转成 <b>字符串</b> 再放进来。
     * 直接把 {@code LocalDateTime} 塞进 Map 会让序列化依赖"ObjectMapper 有没有注册
     * JSR-310 模块"—— Spring 容器里的那个有（Boot 自动配置），而单测里
     * {@code new ObjectMapper()} 没有，于是同一个方法在两种环境下行为不同。
     * 把类型约束在"字符串/数字/布尔"上，这个差异就不存在了。
     * <p>
     * ⚠️ 这里<b>不</b>调 {@code canonicalJson} 再存：MySQL 的 JSON 列本来就会重排键、
     * 抹空白，存进去的形态无所谓 —— 关键是<b>算哈希时</b>两边都过同一个规范化
     * （那是 {@code payload()} 的职责，写入与校验共用一条路）。
     */
    private String toJson(Object snapshot) {
        if (snapshot == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            // 快照序列化失败 = 审计写不下去。此时**必须**让异常出去把事务掀掉 ——
            // "业务行落库了但审计没记上"是本表存在的意义的反面
            throw new IllegalStateException("审计快照序列化失败", e);
        }
    }
}
