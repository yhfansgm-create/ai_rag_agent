package org.agent.memory.service;

import lombok.extern.slf4j.Slf4j;
import org.agent.memory.FactWritePolicy;
import org.agent.memory.dao.UserMemoryDao;
import org.agent.memory.dao.UserMemoryRow;
import org.agent.memory.model.FactDraft;
import org.agent.memory.model.FactKey;
import org.agent.memory.model.FactScope;
import org.agent.memory.model.MemoryActorType;
import org.agent.memory.model.MemoryChangeAction;
import org.agent.memory.model.MemoryEntityType;
import org.agent.memory.model.MemoryStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * L2 的<b>写入落库段</b>：一条候选事实 → 一次短事务。
 *
 * <h2>⭐⭐ 本类最重要的那条硬约束（改它之前先读这里）</h2>
 * <pre>
 *   蒸馏（LLM，慢，异步线程，无事务）
 *     → 决策（纯函数，无 IO）
 *       → 每候选一个 @Transactional 短事务（FOR UPDATE + 计数 + 插入/更新 + 日志，毫秒级）
 * </pre>
 * <b>绝不可以把 {@code @Transactional} 往上挪一层去包住蒸馏。</b>
 * 蒸馏是一次完整 LLM 往返（秒级）；事务一旦包住它，线程就会<b>握着数据库连接和
 * {@code FOR UPDATE} 行锁等两秒</b>，并发几轮就能把 Hikari 池耗光 ——
 * 症状是"偶发接口超时"，而排查方向会跑到连接池、GC、网络上去，
 * <b>几乎不可能定位到记忆模块</b>。所以事务的边界在这里收窄到"几毫秒的几条 SQL"。
 * <p>
 * ⚠️ 配套条件是<b>事务方法必须在另一个 bean 上</b>：{@code SemanticMemoryService}
 * 调用本类的 public 方法，跨 bean 才走得到 Spring 的事务代理。
 * 同类自调用（甚至同一个类里 A 方法调自己的 {@code @Transactional} B 方法）
 * 会让 {@code @Transactional} <b>静默失效</b>。这个坑本项目的注释里已经点过一次名
 * （{@code MemoryAdvisor} 当初拒绝 {@code @Async} 的理由之一），不能再犯第二次。
 *
 * <h2>为什么这里是"决策 + 执行"而不是只有"执行"</h2>
 * {@code FactWritePolicy.decide} 需要的两个数（{@code countActive} 与
 * "该键是否已有有效行"）都是<b>查库</b>得到的，而且必须与后面的写在同一把事务里读
 * （否则"读到没有有效行 → 决定新增"与"别人刚插了一条"之间有一个窗口，
 * 只能靠 {@code uk_owner_active} 抛 DuplicateKeyException 兜底）。
 * 所以决策发生在事务<b>内</b>是必然的，而它本身仍是个纯函数调用 ——
 * 事务里没有 LLM、没有网络、没有任何慢操作。
 *
 * <h2>⚠️ 事务管理器不指定名字</h2>
 * 本项目的 {@code @Transactional} 用的是 Boot 自动配置的那个（单候选 DataSource 上）。
 * 也就是说<b>它必然与 MyBatis 绑在同一个库上</b> —— 这正是本类需要的：
 * {@code selectActiveByKey} 的 {@code FOR UPDATE} 行锁与后续的 INSERT 必须在同一条连接上，
 * 否则锁了也白锁。<b>故意不写 {@code @Transactional("xxxManager")}</b>：
 * 硬编码一个名字，就有"事务管理器指向 A 库、MyBatis 指向 B 库"这种静默错配的可能。
 */
@Slf4j
@Service
public class MemoryFactWriter {

    /**
     * 一次蒸馏的上下文 —— 归属、来源、密级、开关。
     *
     * <h2>为什么归属只从信封来（构造上就没有第二个来源）</h2>
     * {@link FactDraft} 里<b>没有</b> owner / scope / 密级字段，本 record 的 {@code ownerId}
     * 由 {@code SemanticMemoryService} 从<b>本轮实况信封</b>取。所以
     * "模型能不能把一条记忆写到别人名下"这个问题在类型层面就不成立 ——
     * 模型唯一的输出是一对字符串（key、value），它连表达归属的字段都没有。
     *
     * @param ownerId          归属人工号（来自信封）
     * @param sessionId        来源会话（无源不写的一半）
     * @param turnNo           来源轮号（无源不写的另一半）。
     *                         ⚠️ {@code <= 0} 直接拒绝构造 —— 见紧凑构造
     * @param refDocId         该轮引用过的第一份资料的 doc_id，没有则 {@code null}。
     *                         <b>由调用方从 citedRefs 取，模型无权输出</b>（让它给出处等于让它编出处）
     * @param clearanceLevel   该条记忆的密级 = <b>该轮内容的最高密级</b>，取自
     *                         {@code TurnFacts.clearanceLevel()}（L1 已由 {@code MemoryWriteGuard}
     *                         算好）。⚠️ <b>不重算</b>：一条公式一个出口，否则同一轮会在
     *                         L1 与 L2 算出两个密级。也不做 {@code min} —— 见 DDL 文件的论证
     * @param authVersion      写入时的权限版本。<b>仅审计</b>，不参与任何读取判定
     * @param userMessage      该轮用户原话。复述验证的<b>唯一</b>证据来源，见 {@link FactWritePolicy}
     * @param groundingEnabled 复述验证开关。⚠️ 关掉它 = 模型的推测也会落库，
     *                         这是留给"复现漏洞现场"的对比开关，默认必须是 {@code true}
     * @param quotaPerUser     每用户有效事实数上限
     */
    public record WriteContext(String ownerId, String sessionId, int turnNo, String refDocId,
                               int clearanceLevel, long authVersion, String userMessage,
                               boolean groundingEnabled, int quotaPerUser) {

        /**
         * ⚠️ {@code turnNo <= 0} 拒绝构造，是"<b>无源不写</b>"的第二道防线。
         * <p>
         * 真正的保证在<b>执行顺序</b>里：调用方必须<b>先</b> {@code appendTurn} 拿到轮号、
         * <b>再</b>提交蒸馏，且没有轮号就不提交（那是 {@code FactWritePolicy} 类注释
         * 写死的结论 —— 它拿不到轮号，所以它没法检查这一项，只能靠顺序）。
         * 而这里是<b>类型层面</b>的兜底：真有人绕过顺序造了一个没有轮号的 context，
         * 会在构造处当场炸，而不是往库里写一条 {@code source_turn_no = 0} 的孤儿记忆 ——
         * 那种行在审计里看着像"有来源"，实际指不回去，比没有来源更坏。
         */
        public WriteContext {
            if (turnNo <= 0) {
                throw new IllegalArgumentException("L2 写入必须有来源轮号（无源不写）");
            }
        }
    }

    /** 同值重申的审计原因。与 {@code MemoryChangeAction.WRITE} 配对，见该类注释 */
    private static final String REASON_REFRESH_SAME_VALUE = "REFRESH_SAME_VALUE";

    /** 值变更（取代）的审计原因 */
    private static final String REASON_VALUE_CHANGED = "VALUE_CHANGED";

    private final UserMemoryDao dao;
    private final MemoryAuditAppender auditAppender;

    public MemoryFactWriter(UserMemoryDao dao, MemoryAuditAppender auditAppender) {
        this.dao = dao;
        this.auditAppender = auditAppender;
    }

    /**
     * 判一条候选并落库 —— <b>本阶段唯一的事务边界</b>。
     *
     * <h2>三种写入形态</h2>
     * <table>
     *   <tr><th>情形</th><th>动作</th><th>审计</th></tr>
     *   <tr><td>无有效行</td><td>INSERT</td><td>{@code WRITE}（before = null）</td></tr>
     *   <tr><td>有有效行、值相同</td><td>REFRESH（续期，不新增行）</td>
     *       <td>{@code WRITE} + {@code reason = REFRESH_SAME_VALUE}</td></tr>
     *   <tr><td>有有效行、值不同</td><td>SUPERSEDE 旧行 + INSERT 新行</td>
     *       <td><b>两条</b>：旧行 {@code SUPERSEDE}、新行 {@code WRITE}</td></tr>
     * </table>
     *
     * @param ctx   上下文；{@code null} 直接返回（调用方没能提供归属时不该写任何东西）
     * @param draft 模型提出的候选。<b>可以是 {@code null}</b>（模型输出 {@code {"facts":[null]}}）——
     *              不在这里跳过，而是交给策略判成 {@code UNKNOWN_KEY}，
     *              这样"模型回了一个 null"在日志里看得见
     * @return 判定结果（含具名拒绝理由）；{@code ctx} 为 {@code null} 时返回 {@code null}。
     *         审计已经由本方法负责，调用方拿它主要是为了日志与测试断言
     */
    @Transactional
    public FactWritePolicy.Decision writeCandidate(WriteContext ctx, FactDraft draft) {
        if (ctx == null) {
            return null;
        }
        Optional<FactKey> lookedUp = FactKey.resolve(draft == null ? null : draft.rawKey());
        // 已经有有效行时**不查计数**：配额对"取代/续期"无条件放行（见 FactWritePolicy.Quota），
        // 所以那个数字对它没有任何影响。少一次查询，也让"键不认识"这条早退不碰库
        UserMemoryRow existing = lookedUp
                .map(key -> dao.selectActiveByKey(ctx.ownerId(), FactScope.USER, key.code()))
                .orElse(null);
        int activeCount = (existing != null || lookedUp.isEmpty()) ? 0 : dao.countActive(ctx.ownerId());

        // 决策。⚠️ 这一步是纯函数，事务里放它是安全的
        FactWritePolicy.Decision decision = FactWritePolicy.decide(draft, ctx.userMessage(),
                ctx.groundingEnabled(),
                new FactWritePolicy.Quota(activeCount, ctx.quotaPerUser(), existing != null));

        if (!decision.allowed()) {
            auditReject(decision);
            // 只记 key 与理由，**不记值**：拒写的候选值可能是凭证类内容（红线那一条尤其），
            // 而日志不该成为新的泄露面（同 FactRedlines「绝不返回命中原值」的立场）
            log.debug("L2 候选未写入：reason={} key={}", decision.reject(),
                    decision.key() == null ? null : decision.key().code());
            return decision;
        }

        LocalDateTime now = LocalDateTime.now();
        // 留存期由 Java 按词表算，**模型无权输出 expires_at**（留存策略不能交给模型判断）
        LocalDateTime expiresAt = now.plusDays(decision.key().ttlDays());
        if (existing == null) {
            insertNew(ctx, decision.key(), decision.value(), now, expiresAt);
        } else if (existing.factValue().equals(decision.value())) {
            refreshSameValue(existing, expiresAt, now);
        } else {
            supersede(ctx, existing, decision.key(), decision.value(), now, expiresAt);
        }
        return decision;
    }

    /** 无有效行：新增 + 一条 {@code WRITE} */
    private void insertNew(WriteContext ctx, FactKey key, String value,
                           LocalDateTime now, LocalDateTime expiresAt) {
        String factUid = UUID.randomUUID().toString();
        UserMemoryRow row = newRow(factUid, ctx, key, value, now, expiresAt);
        dao.insert(row);
        auditAppender.append(MemoryEntityType.FACT, factUid, MemoryChangeAction.WRITE,
                null, snapshot(row), MemoryActorType.AUTO, null, null);
    }

    /**
     * 有有效行、值相同：<b>续期</b>，不新增行。
     * <p>
     * 为什么"重申同一件事"值钱：用户<b>在新会话里又说了一遍</b>，是"它现在仍然为真"的
     * <b>最强证据</b> —— 而那正是 TTL 需要的信号。所以这不算"重复写入"，
     * 算一次有价值的状态更新，也就必须留痕（"续了期"是真实发生的状态变化）。
     * <p>
     * 审计的 before/after 是<b>同一行的两个快照</b>，after 只改了确实变了的那一列
     * （{@code expiresAt}）—— 于是这条日志读起来就是"保质期从 X 推到了 Y"，
     * 差的 diff 一眼看得出来。
     */
    private void refreshSameValue(UserMemoryRow existing, LocalDateTime expiresAt, LocalDateTime now) {
        if (dao.refresh(existing.factUid(), now, expiresAt) == 0) {
            // 0 行 = 这行已被并发取代（refresh 带 valid_to IS NULL）。什么都不做：
            // 那条候选会被下一个同样说这句话的轮次重新提出来，不值得为它重试
            log.warn("L2 续期落空：该行已被并发取代，本次跳过 factUid={}", existing.factUid());
            return;
        }
        Map<String, Object> before = snapshot(existing);
        Map<String, Object> after = snapshot(existing);
        after.put("expiresAt", text(expiresAt));
        auditAppender.append(MemoryEntityType.FACT, existing.factUid(), MemoryChangeAction.WRITE,
                before, after, MemoryActorType.AUTO, null, REASON_REFRESH_SAME_VALUE);
    }

    /**
     * 有有效行、值不同：旧行盖终点章 + 新行插入，<b>两条</b>审计。
     *
     * <h2>⚠️ 顺序是"先取代、后插入"，不能颠倒</h2>
     * {@code uk_owner_active} 建在生成列 {@code active_key} 上：
     * {@code valid_to IS NULL} 时 {@code active_key = fact_key}，否则为 {@code NULL}。
     * 也就是说<b>同一个 (owner, scope, fact_key) 只容许一条 valid_to IS NULL 的行</b>。
     * 先 INSERT 的话，旧行还没被盖终点章，唯一键会当场把新行拒掉 ——
     * 于是"改口"这个最该被支持的操作反而永远失败。
     * <p>
     * 取代的 before/after <b>都是旧行</b>：{@code before} 是它原来的样子，
     * {@code after} 是它被盖上 {@code valid_to}/{@code superseded_by} 之后的样子。
     * 新行的诞生由配对的那条 {@code WRITE} 记录 —— 两条日志合起来才是一次完整的取代。
     */
    private void supersede(WriteContext ctx, UserMemoryRow existing, FactKey key, String value,
                           LocalDateTime now, LocalDateTime expiresAt) {
        String newFactUid = UUID.randomUUID().toString();
        if (dao.supersede(existing.factUid(), now, newFactUid) == 0) {
            // 乐观并发保护生效：我拿到的那份旧行已经被别人取代过了。
            // 此时**不能**接着插新行 —— 那样会得到两条有效行（一条是别人插的、
            // 一条是我的），superseded_by 也会指向一个不在链上的键
            log.warn("L2 取代落空：旧行已被并发取代，本次放弃（不写新行） factUid={}",
                    existing.factUid());
            return;
        }
        UserMemoryRow row = newRow(newFactUid, ctx, key, value, now, expiresAt);
        dao.insert(row);

        Map<String, Object> before = snapshot(existing);
        Map<String, Object> after = snapshot(existing);
        after.put("validTo", text(now));
        after.put("supersededBy", newFactUid);
        auditAppender.append(MemoryEntityType.FACT, existing.factUid(), MemoryChangeAction.SUPERSEDE,
                before, after, MemoryActorType.AUTO, null, REASON_VALUE_CHANGED);
        auditAppender.append(MemoryEntityType.FACT, newFactUid, MemoryChangeAction.WRITE,
                null, snapshot(row), MemoryActorType.AUTO, null, null);
    }

    /**
     * 拒写审计 —— <b>只有内容红线那一条会写</b>。
     * <p>
     * {@code NOT_GROUNDED} / {@code TIER_CONFIRM} / {@code QUOTA_EXCEEDED} 这些
     * <b>刻意不写</b>：它们是例行质量过滤（每轮都可能命中，且命中的候选里绝大多数
     * 本来就不该被记），而且<b>它们没有对应的记忆条目</b> —— 记进去只会把审计表灌满
     * "从未存在过的记忆"，让真正需要审计的写/取代事件被淹没。
     * 这条区分写在 {@code MemoryChangeAction} 的注释里，这里是它的执行点。
     * <p>
     * ⚠️ {@code after} 里只有 {@code {factKey, pattern, valueLength}}：
     * {@code FactRedlines.Hit} <b>本来就只带模式名与长度、不带给中的文本</b>
     * （见它的注释），所以这里连"顺手带上值"的可能性都没有。被拒的凭证不会被抄第二遍。
     */
    private void auditReject(FactWritePolicy.Decision decision) {
        if (decision.reject() != FactWritePolicy.RejectReason.REDLINE_VALUE) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("factKey", decision.key() == null ? null : decision.key().code());
        detail.put("pattern", decision.redline() == null ? null : decision.redline().pattern());
        detail.put("valueLength", decision.redline() == null ? null : decision.redline().valueLength());
        auditAppender.append(MemoryEntityType.FACT, null, MemoryChangeAction.GUARD_REJECT,
                null, detail, MemoryActorType.AUTO, null, decision.reject().name());
    }

    /**
     * 组装一条新行。
     * <p>
     * ⚠️ {@code scope} 写死 {@code USER}、{@code orgPath} 写死 {@code null}，
     * 这是<b>"没有 GLOBAL/DEPT 写入路径"的落点</b>：没有任何代码能造出一个别的 scope 的行
     * （{@code FactDraft} 里没有 scope 字段，这里也不读任何配置或参数）。
     * DEPT 的读分支照实现并被集成测试证明可读，但晋升机制归阶段 4。
     * <p>
     * ⚠️ {@code status = ACTIVE}，被取代时也<b>不改</b>：{@code status} 表达生命周期/健康度，
     * "是否为当前有效"由 {@code valid_to} 表达（见 {@code MemoryStatus} 类注释）。
     * <p>
     * {@code validFrom} / {@code expiresAt} 由 Java 传（不用 DB 默认值）：
     * 一是 {@code expiresAt = now + FactKey.ttlDays} 必须与 validFrom 同源算出来，
     * 二是时间要能用固定时钟测。
     */
    private static UserMemoryRow newRow(String factUid, WriteContext ctx, FactKey key, String value,
                                        LocalDateTime now, LocalDateTime expiresAt) {
        return new UserMemoryRow(factUid, ctx.ownerId(), FactScope.USER, key.code(), value,
                ctx.clearanceLevel(), null, ctx.sessionId(), ctx.turnNo(), ctx.refDocId(),
                now, null, null, expiresAt, MemoryStatus.ACTIVE, null, ctx.authVersion(), null);
    }

    /**
     * 审计快照。放的是"这一行当时长什么样"的完整形状，而不是只放变了的那一列 ——
     * 因为审计要回答的是"当时到底记了什么"，而不只是"哪里变了"。
     * <p>
     * 含 {@code factValue}：它就是 {@code user_memory.fact_value} 里那份内容，
     * 写在同一个库里，不是新的暴露面；而一条只说"值变了"、不说变成什么的审计
     * 回答不了任何问题。绝不放值的只有 {@code GUARD_REJECT} 那一种，见 {@link #auditReject}。
     */
    private static Map<String, Object> snapshot(UserMemoryRow row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("factUid", row.factUid());
        map.put("ownerId", row.ownerId());
        map.put("scope", name(row.scope()));
        map.put("factKey", row.factKey());
        map.put("factValue", row.factValue());
        map.put("clearanceLevel", row.clearanceLevel());
        map.put("orgPath", row.orgPath());
        map.put("sourceSessionId", row.sourceSessionId());
        map.put("sourceTurnNo", row.sourceTurnNo());
        map.put("refDocId", row.refDocId());
        map.put("validFrom", text(row.validFrom()));
        map.put("validTo", text(row.validTo()));
        map.put("supersededBy", row.supersededBy());
        map.put("expiresAt", text(row.expiresAt()));
        map.put("status", name(row.status()));
        map.put("permAuthVersion", row.permAuthVersion());
        return map;
    }

    /**
     * 时间 → 字符串。
     * <p>
     * ⚠️ 必须先转字符串再进快照：直接把 {@code LocalDateTime} 放进 Map 会让序列化
     * 依赖"ObjectMapper 注册了 JSR-310 模块没有" —— 容器里那个注册了，
     * 单测里 {@code new ObjectMapper()} 没有，于是同一段代码在两种环境下行为不同。
     * 顺带这也是审计 JSON 该有的形状：时间在审计里是给人看与给 diff 比的，不是给程序算的。
     */
    private static String text(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
