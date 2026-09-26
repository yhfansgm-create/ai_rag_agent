package org.agent.memory.service;

import lombok.extern.slf4j.Slf4j;
import org.agent.memory.dao.SessionSummaryDao;
import org.agent.memory.dao.SessionSummaryRow;
import org.agent.memory.model.MemoryActorType;
import org.agent.memory.model.MemoryChangeAction;
import org.agent.memory.model.MemoryEntityType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 摘要的<b>写入落库段</b>：一版新摘要 → 一次短事务（取代旧版 + 插入新版 + 两条日志）。
 *
 * <h2>为什么与 {@link MemoryFactWriter} 分开成两个类，而不是合成一个"记忆写入服务"</h2>
 * 两者除了"都要 @Transactional、都要追加审计"之外<b>没有共同的判决逻辑</b>：
 * 事实那边有九项门禁、配额、三种写入形态；摘要这边只有"取代旧版 + 插入新版"。
 * 合起来会得到一个两套规则都塞在里面的类，而它们的<b>失败后果完全不同</b> ——
 * 事实写坏了是"模型记住了不该记的"，摘要写坏了是"高密级内容被长期携带"。
 * 混在一起写注释时，两件事的理由会互相淹没。
 * <p>
 * 共用的部分已经抽走了：{@link MemoryAuditAppender}（算哈希接链）与被两个类
 * 各自调用的 {@code dao}。剩下的重复只有"先取代后插入"这一个顺序，而它
 * <b>在两个表上是两个不同的唯一键约束</b>（{@code uk_owner_active} / {@code uk_session_active}），
 * 抽成一个泛型方法反而会让"为什么顺序不能反"这个理由被抽象掉。
 *
 * <h2>⭐ 与 {@link MemoryFactWriter} 逐字相同的那条硬约束</h2>
 * <pre>
 *   压缩（LLM，慢，异步线程，无事务）
 *     → 每版摘要一个 @Transactional 短事务（取代 + 插入 + 日志，毫秒级）
 * </pre>
 * {@code @Transactional} <b>绝不可以往上挪一层去包住压缩</b> —— 那会让线程握着数据库连接
 * 和行锁等一次 LLM 往返（秒级），并发几轮就能把 Hikari 池耗光。
 * 完整论证见 {@link MemoryFactWriter} 的类注释（那段是规范，这里是第二个执行点）。
 * <p>
 * 同理，事务方法必须在<b>另一个 bean</b> 上：{@code SessionSummaryService} 跨 bean 调用
 * 本类才走得到事务代理；同类自调用会让 {@code @Transactional} 静默失效。
 *
 * <h2>⚠️ 事务管理器同样不指定名字</h2>理由见 {@link MemoryFactWriter}：硬编码事务管理器
 * 的名字，就有"事务管理器指向 A 库、MyBatis 指向 B 库"这种静默错配的可能。
 */
@Slf4j
@Service
public class SummaryWriterService {

    /** 取代旧版的审计原因：被一次更宽的压缩取代 */
    private static final String REASON_SUPERSEDED_BY_COMPACTION = "SUPERSEDED_BY_COMPACTION";

    private final SessionSummaryDao dao;
    private final MemoryAuditAppender auditAppender;

    public SummaryWriterService(SessionSummaryDao dao, MemoryAuditAppender auditAppender) {
        this.dao = dao;
        this.auditAppender = auditAppender;
    }

    /**
     * 落库一版新摘要，并取代上一版（若有）。
     *
     * <h2>⚠️ 顺序是"先取代、后插入"，不能颠倒</h2>
     * {@code uk_session_active} 建在生成列 {@code active_session_id} 上：
     * {@code valid_to IS NULL} 时它等于 {@code session_id}，否则为 {@code NULL}。
     * 也就是说<b>一个会话只容许一条 {@code valid_to IS NULL} 的摘要</b>。
     * 先 INSERT 的话旧版还没被盖终点章，唯一键会当场把新版拒掉 ——
     * 于是"继续压缩"这个最该被支持的操作反而永远失败。
     * （与 {@code MemoryFactWriter.supersede} 同一个理由，两个表各自的唯一键。）
     *
     * <h2>取代的 before/after 都是旧行</h2>
     * {@code before} 是它原来的样子，{@code after} 是它被盖上
     * {@code valid_to}/{@code superseded_by} 之后的样子。新版的诞生由配对的那条
     * {@code WRITE} 记录 —— 两条日志合起来才是一次完整的取代。
     * 版本化的意义在 {@code SessionSummaryDao.supersede} 的注释里：
     * <b>事后复现"当时模型看到了什么"</b>，被注入模型的东西必须留痕。
     *
     * @param previous 被取代的旧版；{@code null} 表示这是该会话的第一版
     * @param incoming 新版（{@code summaryUid} 已由调用方生成、{@code validFrom} 已算好、密级已算好）。
     *                 ⚠️ <b>密级必须由调用方按"被压缩轮次的 max"算好带进来，模型无权输出</b>，
     *                 见 {@code SessionSummaryRow}。{@code null} 直接返回
     * @return 已插入的那一行；取代落空（旧版已被并发取代）时返回 {@code null}
     */
    @Transactional
    public SessionSummaryRow write(SessionSummaryRow previous, SessionSummaryRow incoming) {
        if (incoming == null) {
            return null;
        }
        if (previous != null
                && dao.supersede(previous.summaryUid(), incoming.validFrom(),
                        incoming.summaryUid()) == 0) {
            // 乐观并发保护生效：我拿到的那份旧版已经被别人取代过了。
            // 此时**不能**接着插新版 —— 那样会得到两条有效摘要（一条是别人插的、
            // 一条是我的），superseded_by 也会指向一个不在链上的键，
            // 而"一个会话一份摘要"正是"每一轮要么在窗口里、要么在摘要里"这条覆盖完备性的前提
            log.warn("摘要取代落空：旧版已被并发取代，本次放弃（不写新版） summaryUid={}",
                    previous.summaryUid());
            return null;
        }

        dao.insert(incoming);

        if (previous != null) {
            Map<String, Object> before = snapshot(previous);
            Map<String, Object> after = snapshot(previous);
            after.put("validTo", text(incoming.validFrom()));
            after.put("supersededBy", incoming.summaryUid());
            auditAppender.append(MemoryEntityType.SUMMARY, previous.summaryUid(),
                    MemoryChangeAction.SUPERSEDE, before, after,
                    MemoryActorType.AUTO, null, REASON_SUPERSEDED_BY_COMPACTION);
        }
        auditAppender.append(MemoryEntityType.SUMMARY, incoming.summaryUid(),
                MemoryChangeAction.WRITE, null, snapshot(incoming),
                MemoryActorType.AUTO, null, null);
        return incoming;
    }

    /**
     * 审计快照。
     * <p>
     * ⚠️ {@code summaryText} 在这里是<b>要放</b>的：它就是 {@code session_summary.summary_text}
     * 里那份内容，写在同一个库里，不是新的暴露面；而一条只说"摘要变了"、不说变成什么的
     * 审计回答不了任何问题 —— 尤其这一层存在的理由正是"事后能复现当时模型看到了什么"。
     * （"绝不放值的只有 {@code GUARD_REJECT} 那一种"这条边界见 {@link MemoryFactWriter}。）
     * <p>
     * ⚠️ 时间一律先转字符串：直接放 {@code LocalDateTime} 会让序列化依赖
     * "ObjectMapper 注册了 JSR-310 模块没有"，而容器里注册了、单测里没有 ——
     * 同一段代码两种行为。
     */
    private static Map<String, Object> snapshot(SessionSummaryRow row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("summaryUid", row.summaryUid());
        map.put("sessionId", row.sessionId());
        map.put("userId", row.userId());
        map.put("coversFromTurnNo", row.coversFromTurnNo());
        map.put("coversThroughTurnNo", row.coversThroughTurnNo());
        map.put("clearanceLevel", row.clearanceLevel());
        map.put("summaryText", row.summaryText());
        map.put("sourceTurnCount", row.sourceTurnCount());
        map.put("validFrom", text(row.validFrom()));
        map.put("validTo", text(row.validTo()));
        map.put("supersededBy", row.supersededBy());
        map.put("permAuthVersion", row.permAuthVersion());
        map.put("expiresAt", text(row.expiresAt()));
        return map;
    }

    private static String text(LocalDateTime value) {
        return value == null ? null : value.toString();
    }
}
