package org.agent.memory.service;

import lombok.extern.slf4j.Slf4j;
import org.agent.memory.SemanticMemoryProperties;
import org.agent.memory.dao.UserMemoryDao;
import org.agent.memory.dao.UserMemoryRow;
import org.agent.memory.distill.DistillRequest;
import org.agent.memory.distill.FactDistiller;
import org.agent.memory.model.CitedChunkRef;
import org.agent.memory.model.FactDraft;
import org.agent.memory.model.FactKey;
import org.agent.memory.model.FactScope;
import org.agent.memory.model.MemoryStatus;
import org.agent.memory.model.RecalledFact;
import org.agent.memory.model.TurnFacts;
import org.agent.permission.SessionEnvelope;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * L2 语义记忆的<b>唯一策略出口</b>：读（{@link #recall}）与写的提交（{@link #scheduleDistill}）。
 *
 * <h2>和 L1 刻意相反的一处：密级条件下推进了 SQL</h2>
 * L1 把密级判定放在 {@code LIMIT} <b>之后</b>（{@code ChatMemoryService}），因为
 * {@code LIMIT N} 的语义是"最近 N 轮"—— 塞进 {@code WHERE} 会让降级用户<b>跳过最近几轮
 * 去捞更早的</b>，那是绕过"最近"这条安全边界。
 * <p>
 * <b>这个推理在 L2 上不成立</b>：L2 的 {@code LIMIT} 是"该用户最多返回 N 条事实"的
 * <b>展示预算</b>，没有"最近 vs 更早"这条边界；且 {@code uk_owner_active} 保证
 * 同一 {@code (owner, scope, fact_key)} 至多一条有效行 ⇒ <b>不存在"同一事实的更早版本"可回捞</b>。
 * 所以下推（{@code UserMemoryDao} 的 {@code clearance_level <= #{envelopeClearance}}）
 * 的收益大于风险。
 * <p>
 * <b>但 Java 侧仍然再复核一遍</b>，这才是"一条权限策略一个出口"在 L2 的落点：
 * <ul>
 *   <li>SQL 里的 {@code ownerId} / {@code envelopeClearance} / {@code orgPath}
 *       <b>全部来自本轮实况信封</b>，没有任何一个来自行里的快照列</li>
 *   <li>复核让单测能直接喂进"不该返回的行"并断言被丢掉 —— 不依赖数据库真的返回错东西</li>
 * </ul>
 * {@code perm_auth_version} 与 L1 逐字相同：<b>只留痕，不参与判定</b>。
 *
 * <h2>⚠️ 本类<b>没有</b>事务，也不该有</h2>
 * 写入落库是 {@link MemoryFactWriter} 的短事务。事务边界往上挪一格就会包住 LLM 调用，
 * 详见那个类的类注释（那是本阶段最大的风险点）。
 *
 * <h2>失败隔离在哪一层</h2>
 * 读侧（{@code recall}）与 DAO 一律 <b>fail-fast</b>：让异常抛给 {@code MemoryAdvisor}
 * 的 catch 边界，那里统一降级成"这一路没有记忆"，对话照常。隔离边界只有一处才好审计。
 * 而<b>异步任务体自带 try/catch</b>：它不在 advisor 的栈上，异常抛进
 * {@code ThreadPoolExecutor} 只会被吞掉，且看不出是谁。
 */
@Slf4j
@Service
public class SemanticMemoryService {

    private final UserMemoryDao userMemoryDao;
    private final FactDistiller distiller;
    private final MemoryFactWriter factWriter;
    private final ExecutorService distillExecutor;
    private final SemanticMemoryProperties props;

    public SemanticMemoryService(UserMemoryDao userMemoryDao,
                                 FactDistiller distiller,
                                 MemoryFactWriter factWriter,
                                 @Qualifier("memoryDistillExecutor") ExecutorService distillExecutor,
                                 SemanticMemoryProperties props) {
        this.userMemoryDao = userMemoryDao;
        this.distiller = distiller;
        this.factWriter = factWriter;
        this.distillExecutor = distillExecutor;
        this.props = props;
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /**
     * 召回本轮可注入 prompt 的稳定事实，<b>并把这些行记一次命中</b>。
     * <p>
     * ⚠️ 命中的判据是"通过了策略复核、进入了返回列表"。渲染器随后还会按预算从尾部裁剪，
     * 被裁掉的那条仍会被记一次命中 —— 这是<b>知道并且选择</b>的不精确：把 {@code touchHit}
     * 挪到渲染之后，意味着"哪些行能读"这条策略就有了第二个出口，而
     * "一条权限策略一个出口"比命中统计的最后一格精度重要。
     * 误差方向是"多记"，只影响阶段 4 巡检③的 LRU 排序，不影响任何权限判定。
     *
     * @param envelope 本轮实况权限信封 —— 读取判定的<b>唯一</b>依据
     * @return 可直接渲染的事实列表；关掉开关、或信封为空、或一条都读不到时返回空列表
     */
    public List<RecalledFact> recall(SessionEnvelope envelope) {
        LocalDateTime now = LocalDateTime.now();
        List<RecalledFact> readable = readReadable(envelope, now);
        if (!readable.isEmpty()) {
            // ⚠️ 在**调用线程**上同步批量更新（一条 UPDATE ... WHERE fact_uid IN (...) ，毫秒级）：
            // ① 单测可以直接 verify(dao).touchHit(...)，不需要 await；
            // ② 不扔进蒸馏那个 Executor —— 那里可能正卡着一个 2 秒的 LLM 调用，
            //    会让"命中统计丢了"与"记忆没写上"两个完全不同的问题纠缠在一起
            userMemoryDao.touchHit(readable.stream().map(RecalledFact::factUid).toList(), now);
        }
        return readable;
    }

    /**
     * 只读不记命中 —— <b>专供蒸馏的"已知事实"输入</b>。
     * <p>
     * 为什么必须与 {@link #recall} 分开：那些事实只是喂给抽取器（"这些已经记住了，
     * 不要重复输出"），<b>没有进任何 prompt</b>。若走 {@code recall}，每轮蒸馏都会给
     * 全部事实续一次命中，{@code last_hit_at} 立刻变成噪声 ——
     * 而阶段 4 巡检③正是靠它判断"哪条记忆长期没被用过"。
     * <p>
     * 与 {@code recall} 共用同一个复核（{@link #readReadable}）：喂给抽取器的已知事实
     * 也必须过同一条权限出口，否则高密级事实会通过蒸馏这条侧路进到模型的上下文里。
     */
    List<RecalledFact> recallForDistill(SessionEnvelope envelope) {
        return readReadable(envelope, LocalDateTime.now());
    }

    /**
     * 读 + 策略复核。两条读取路径（注入 / 蒸馏输入）共用它，所以"哪些行能读"
     * 只有一个判据来源。
     */
    private List<RecalledFact> readReadable(SessionEnvelope envelope, LocalDateTime now) {
        if (!props.enabled() || envelope == null || props.recallLimit() <= 0) {
            return List.of();
        }
        List<UserMemoryRow> candidates = new ArrayList<>(userMemoryDao.selectUserFacts(
                envelope.userId(), envelope.dataClearanceLevel(), now, props.recallLimit()));
        // DEPT：org_path 精确匹配（不做前缀匹配，与 SessionAuthService.buildAccessFilter 的
        // org_codes in [...] 同口径）。信封没有 orgPath 时**不发起这次查询** ——
        // 不是省一次查询，而是"没有组织就没有部门级可见范围"这条语义的显式表达
        if (hasText(envelope.orgPath())) {
            candidates.addAll(userMemoryDao.selectDeptFacts(
                    envelope.orgPath(), envelope.dataClearanceLevel(), now, props.recallLimit()));
        }

        // 两段结果各自有序，合起来不一定有序 —— 必须整体重排。
        // ⚠️ 不重排的话 DEPT 那一整段永远在尾部，于是预算裁剪时它总是先被丢掉，
        // 而那与"它更不重要"无关，只是拼接顺序的副作用
        candidates.sort(Comparator.comparing(SemanticMemoryService::recencyOf).reversed());

        List<RecalledFact> readable = new ArrayList<>();
        for (UserMemoryRow row : candidates) {
            if (readable.size() >= props.recallLimit()) {
                break;
            }
            RecalledFact fact = toRecalled(row, envelope, now);
            if (fact != null) {
                readable.add(fact);
            }
        }
        return List.copyOf(readable);
    }

    /**
     * 策略复核 + 转成读侧模型。五个拒绝理由共用同一个返回 {@code null} 的出口 ——
     * 与 {@code ChatMemoryService.toTurn} 同一手法："绝不返回不该返回的行"这条不变量
     * 由构造保证，将来加第 6 个理由也不会漏掉某个分支。
     *
     * @return 可读的那条事实；{@code null} 表示丢弃
     */
    private RecalledFact toRecalled(UserMemoryRow row, SessionEnvelope envelope, LocalDateTime now) {
        if (row == null || row.factUid() == null) {
            return null;
        }
        // ① 有效性与健康度。SQL 已经滤过，这里是"策略只在 Java 侧成立"的复核：
        //    万一有人改坏了 SQL（或另加了一条查询路径），这里仍然拦得住
        if (row.validTo() != null || row.status() != MemoryStatus.ACTIVE) {
            return null;
        }
        // ② 密级：判据是**本轮信封**，不是行里的快照列。1 <= level <= 信封密级 ——
        //    下界 1 不是形式主义：列上 DEFAULT 5，别的写入路径若显式写了个 0，
        //    那就是一个"比任何信封都低"的通行证（同 ChatMemoryService.readableAt）
        int level = row.clearanceLevel();
        int envelopeClearance = envelope.dataClearanceLevel();
        if (level < 1 || level > envelopeClearance) {
            return null;
        }
        // ③ 过期：到期不删行，只是不再召回
        if (row.expiresAt() != null && !row.expiresAt().isAfter(now)) {
            return null;
        }
        // ④ 归属：USER 比 owner、DEPT 比 org_path。GLOBAL 恒拒 —— 见 FactScope 的注释
        if (!visibleBy(row, envelope)) {
            return null;
        }
        // ⑤ 词表：键必须还能解析出来。解析不出来说明词表收缩过（或数据脏），
        //    该行读不出标签、也就渲染不出来 —— 丢弃并留 WARN，不静默跳过
        Optional<FactKey> key = FactKey.resolve(row.factKey());
        if (key.isEmpty()) {
            log.warn("L2 召回跳过一条无法解析词表的记忆：factUid={} factKey={}",
                    row.factUid(), row.factKey());
            return null;
        }
        return new RecalledFact(row.factUid(), key.get(), row.factValue(), row.scope(),
                level, row.sourceTurnNo(), row.refDocId(), toInstant(row.expiresAt()));
    }

    /**
     * 归属可见性。
     * <p>
     * ⚠️ 用 {@code switch} 穷举枚举而不是 {@code if/else}：{@code FactScope} 里
     * {@code GLOBAL} 是"声明但读写皆拒"的 —— 没有这一支的话，将来有人加"全局记忆"
     * 时会走 {@code default} 悄悄放行。<b>不存在的枚举值不可能被漏判，存在但被显式拒绝的才会</b>。
     * 穷举 switch（无 default）有个附带好处：枚举加一项就编译不过，逼着人来看一眼。
     */
    private static boolean visibleBy(UserMemoryRow row, SessionEnvelope envelope) {
        if (row.scope() == null) {
            return false;
        }
        return switch (row.scope()) {
            case USER -> envelope.userId() != null && envelope.userId().equals(row.ownerId());
            case DEPT -> hasText(envelope.orgPath()) && envelope.orgPath().equals(row.orgPath());
            case GLOBAL -> false;
        };
    }

    /**
     * 排序用的时间：{@code COALESCE(last_hit_at, created_at)}。
     * <p>
     * ⚠️ 用 {@code COALESCE} 而不是裸 {@code last_hit_at} 的理由：<b>刚写入、还没被命中过的
     * 事实不能排在最后</b> —— 否则演示时"我上一句刚说的它就不记得"。
     * 兜到 {@code LocalDateTime.MIN} 只是为了不 NPE（{@code created_at} 列是 NOT NULL，
     * 真走到那里说明数据异常，排在最后是偏严的方向）。
     */
    private static LocalDateTime recencyOf(UserMemoryRow row) {
        if (row.lastHitAt() != null) {
            return row.lastHitAt();
        }
        return row.createdAt() == null ? LocalDateTime.MIN : row.createdAt();
    }

    // ------------------------------------------------------------------
    // 写的提交
    // ------------------------------------------------------------------

    /**
     * 提交一轮蒸馏（<b>异步</b>）。调用方在写完 L1 之后调用它，见 {@code MemoryAdvisor}。
     *
     * <h2>⚠️ 无源不写：靠执行顺序保证，不靠注释</h2>
     * 没有 {@code turnNo} 就<b>不提交</b>。这条判据在这里，而 {@code FactWritePolicy}
     * 拿不到轮号（{@code FactDraft} 里没有、也不该有），所以那里检查不了 ——
     * 详见它的类注释。{@code MemoryFactWriter.WriteContext} 的紧凑构造还有第二道防线。
     *
     * <h2>为什么是异步</h2>
     * 蒸馏是一次完整的 LLM 往返（秒级）。同步会让<b>每一次</b> {@code chatClient.call()}
     * 都多等一个 RTT —— 用户的回答已经生成好了，却还在等一个"锦上添花"的记忆写入。
     * 这个异步承诺在 {@code MemoryAdvisor} 的类注释里已经写了两轮（"等写入变成跨表多条时
     * 再上有界队列 + 丢弃策略"），本方法就是那句话说的时刻。
     *
     * <h2>失败形态</h2>
     * {@code distillExecutor.execute(...)} 本身<b>不吞异常</b>（线程池已关闭时会抛
     * {@code RejectedExecutionException}）—— 交给 {@code MemoryAdvisor} 的写侧 catch 边界。
     * 队列满则是另一种形态：{@code WarnAndDiscardPolicy} 会打一条 WARN 然后丢弃任务，
     * 不阻塞、不抛（见 {@code MemoryDistillConfig}）。两种都必须<b>可见</b>。
     *
     * @param facts    本轮事实（用户原话、密级、引文、会话）。{@code null} 直接返回
     * @param turnNo   L1 落库后拿到的真实轮号。{@code <= 0} 视为"没有来源"，不提交
     * @param envelope 本轮实况信封 —— 归属与"已知事实"的读取依据
     */
    public void scheduleDistill(TurnFacts facts, int turnNo, SessionEnvelope envelope) {
        if (!props.enabled() || facts == null || envelope == null) {
            return;
        }
        if (turnNo <= 0) {
            log.warn("L2 蒸馏未提交：本轮没有拿到来源轮号（无源不写） sessionId={}", facts.sessionId());
            return;
        }
        distillExecutor.execute(() -> runDistill(facts, turnNo, envelope));
    }

    /**
     * 异步任务体。
     * <p>
     * ⚠️ <b>两层 try/catch，缺一不可</b>：
     * <ul>
     *   <li>外层：把整轮蒸馏（含读已知事实、调模型）的失败收成一条 WARN。
     *       任务体不在 advisor 的栈上，外抛只会被 {@code ThreadPoolExecutor} 吞掉，
     *       症状是"什么都没发生" —— 比日志里的一条 WARN 难查得多</li>
     *   <li>内层（每个候选）：一条候选写失败不该带走同一轮的其它候选。
     *       它们的失败原因彼此独立（一条撞了唯一键，另一条未必）</li>
     * </ul>
     * 记忆的失败是允许的（记忆是锦上添花），记忆的失败<b>不可见</b>是不允许的。
     */
    private void runDistill(TurnFacts facts, int turnNo, SessionEnvelope envelope) {
        try {
            List<FactDraft> drafts = distiller.distill(new DistillRequest(
                    facts.userMessage(), facts.bizDate(), recallForDistill(envelope)));
            if (drafts.isEmpty()) {
                return;
            }
            MemoryFactWriter.WriteContext ctx = new MemoryFactWriter.WriteContext(
                    envelope.userId(), facts.sessionId(), turnNo, refDocIdOf(facts.citedRefs()),
                    facts.clearanceLevel(), facts.authVersion(), facts.userMessage(),
                    props.groundingEnabled(), props.quotaPerUser());
            for (FactDraft draft : drafts) {
                try {
                    factWriter.writeCandidate(ctx, draft);
                } catch (Exception e) {
                    log.warn("L2 单条候选写入失败，跳过该条（同一轮其余候选继续）", e);
                }
            }
        } catch (Exception e) {
            log.warn("L2 蒸馏任务失败，本轮不写记忆（不影响本轮回答）", e);
        }
    }

    /**
     * 该轮引用过的第一条资料的 {@code doc_id}，没有则 {@code null}。
     * <p>
     * 一条事实只有一个 {@code ref_doc_id} 列，取第一条即可 ——"这条偏好是在哪份制度里
     * 出现的"是定位信息，不是引用归因。多条时<b>刻意不拼接</b>：把若干个 doc_id 拼成
     * 一个值，就是"记忆层变成第二个知识库"的第一个信号（见 {@code RecalledFact} 类注释）。
     * <p>
     * 拒答轮没有引文 ⇒ 必为 {@code null} ⇒ 该事实没有指针。这是<b>允许</b>的：
     * 指针的用途是阶段 4 的巡检①（所依制度已废止），而"我主要做对公"这类偏好
     * 本来就不依赖任何制度。
     */
    private static String refDocIdOf(List<CitedChunkRef> citedRefs) {
        if (citedRefs == null) {
            return null;
        }
        for (CitedChunkRef ref : citedRefs) {
            if (ref != null && hasText(ref.docId())) {
                return ref.docId();
            }
        }
        return null;
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }
}
