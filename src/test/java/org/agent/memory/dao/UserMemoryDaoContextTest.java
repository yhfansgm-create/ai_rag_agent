package org.agent.memory.dao;

import org.agent.memory.model.FactScope;
import org.agent.memory.model.MemoryStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 集成测试：连真实 MySQL 验证 {@code user_memory} 的读写与约束行为，不调 LLM。
 * <p>
 * <b>前置条件</b>：本机 MySQL(127.0.0.1:3306/fan_ai_agent) 在运行，且已执行
 * {@code sql/user_memory.sql} 建表（⚠️ 带 {@code --default-character-set=utf8mb4}，见该文件头）。
 * <p>
 * <b>这个测试存在的理由</b>：本表有两处本项目此前<b>没有先例</b>的东西，
 * 它们都是"不变量落在 DB 层"的设计，而 DB 行为只能靠真库验：
 * <ol>
 *   <li><b>生成列 + 唯一索引</b>（{@code active_key} + {@code uk_owner_active}）——
 *       唯一索引对 NULL 不生效，靠生成列把"有效"压成非 NULL 值。这条语法/语义
 *       是整套 supersede 并发防线的地基，写错了会静默失效（不报错，只是约束不住）</li>
 *   <li><b>枚举列 + 时间列 + 三个 boolean 语义的时间列</b>（{@code valid_to} /
 *       {@code expires_at} / {@code last_hit_at}）的往返</li>
 * </ol>
 * <p>
 * 另外两件只在 DAO 层能验的事：<b>密级下推进 SQL 真的生效</b>（决策 4 的偏离），
 * 以及 <b>{@code COALESCE(last_hit_at, created_at)} 这条排序</b>（决策 8 里
 * "刚写入的事实不能排在最后"）。这两条都是"看起来对"很容易、"实际对"要验的。
 * <p>
 * 测试数据一律用随机 {@code owner_id}（32 位，正好占满 VARCHAR(32)）与随机 {@code fact_uid}，
 * 所以不需要清理代码，也不会污染其它测试。
 */
@SpringBootTest(properties = "rag.vectorstore.enabled=false")
@ActiveProfiles("local")
class UserMemoryDaoContextTest {

    /** 一轮引用的资料密级，默认 4 —— 与 L1 测试里的 highDoc() 同一个口径 */
    private static final int HIGH_CLEARANCE = 4;

    @Autowired
    private UserMemoryDao dao;

    /** 绕开 DAO 做两件事：验 DEFAULT 行为、精确控制时间列（DATETIME 只到秒，构造不出稳定序） */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================================================================
    // 往返与列映射
    // ==================================================================

    @Test
    @DisplayName("往返：列别名映射正确（含 enum 列与三个时间列）")
    void insertAndSelectRoundTrip() {
        String owner = newOwner();
        String uid = UUID.randomUUID().toString();
        LocalDateTime now = now();
        UserMemoryRow row = Fact.builder()
                .uid(uid).owner(owner).scope(FactScope.USER)
                .key("business_line").value("对公")
                .clearance(HIGH_CLEARANCE)
                .source("sess-1", 3)
                .refDocId("DOC-1")
                .validFrom(now).expiresAt(now.plusDays(90))
                .permAuthVersion(7L)
                .build();

        assertThat(dao.insert(row)).isEqualTo(1);

        List<UserMemoryRow> rows = dao.selectUserFacts(owner, 5, now, 10);

        assertThat(rows).hasSize(1);
        UserMemoryRow got = rows.get(0);
        assertThat(got.factUid()).isEqualTo(uid);
        assertThat(got.ownerId()).isEqualTo(owner);
        assertThat(got.scope()).isEqualTo(FactScope.USER);
        assertThat(got.factKey()).isEqualTo("business_line");
        assertThat(got.factValue()).isEqualTo("对公");
        assertThat(got.clearanceLevel()).isEqualTo(HIGH_CLEARANCE);
        assertThat(got.sourceSessionId()).isEqualTo("sess-1");
        assertThat(got.sourceTurnNo()).isEqualTo(3);
        assertThat(got.refDocId()).isEqualTo("DOC-1");
        assertThat(got.validFrom()).isEqualTo(now);
        assertThat(got.expiresAt()).isEqualTo(now.plusDays(90));
        assertThat(got.permAuthVersion()).isEqualTo(7L);
        // 新插入的行必然是"当前有效"：valid_to 与 superseded_by 为空，status 为默认值
        assertThat(got.validTo()).isNull();
        assertThat(got.supersededBy()).isNull();
        assertThat(got.status()).isEqualTo(MemoryStatus.ACTIVE);
        // last_hit_at 与 created_at 不在 insert 的列清单里（前者由命中续期写，后者由 DB 填）
        assertThat(got.lastHitAt()).isNull();
        assertThat(got.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("fail-closed 默认值：原生 INSERT 漏写 clearance_level → 读回 5（谁都读不到）")
    void missingClearanceLevelDefaultsToHighest() {
        String owner = newOwner();
        String uid = UUID.randomUUID().toString();
        // 故意绕开 DAO：模拟"将来另一条写入路径漏了这列"。与 chat_message 同一个理由 ——
        // 这列一旦漏写，结果必须是"谁都读不到"而不是"谁都能读到"
        jdbcTemplate.update("""
                INSERT INTO user_memory (fact_uid, owner_id, fact_key, fact_value, source_session_id, source_turn_no)
                VALUES (?, ?, 'business_line', '漏写密级的行', 'sess-1', 1)
                """, uid, owner);

        // 用 selectActiveByKey 读（它刻意不按密级过滤），才看得到真实的列值
        UserMemoryRow got = dao.selectActiveByKey(owner, FactScope.USER, "business_line");

        assertThat(got).isNotNull();
        assertThat(got.clearanceLevel()).isEqualTo(5);
        // scope / status / valid_from 也都是 DB 默认值兜住的
        assertThat(got.scope()).isEqualTo(FactScope.USER);
        assertThat(got.status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(got.validFrom()).isNotNull();
        // 而"漏写密级 = 谁都读不到"这件事，要站在召回一侧断言
        assertThat(dao.selectUserFacts(owner, 5, now(), 10))
                .as("默认值 5 是最高密级，信封密级 5 仍可读")
                .hasSize(1);
        assertThat(dao.selectUserFacts(owner, 4, now(), 10))
                .as("但信封密级一旦低于 5，这条就不可读 —— fail-closed 的方向")
                .isEmpty();
    }

    // ==================================================================
    // 召回：这一组验的是决策 4「密级下推进 SQL」与决策 8「COALESCE 排序」
    // ==================================================================

    @Test
    @DisplayName("召回：被取代的、过期的、别人的、DEPT 的，一条都不返回")
    void recallExcludesSupersededExpiredAndOthers() {
        String owner = newOwner();
        LocalDateTime now = now();

        // 唯一该被召回的
        dao.insert(Fact.builder().owner(owner).key("business_line").value("对公").build());
        // 被取代过（valid_to 非空）：历史行不参与召回
        UserMemoryRow superseded = Fact.builder().owner(owner).key("preferred_org").value("0100").build();
        dao.insert(superseded);
        dao.supersede(superseded.factUid(), now, UUID.randomUUID().toString());
        // 已过期：expires_at 是"怀疑标记"，过期只是不再召回，行还在
        dao.insert(Fact.builder().owner(owner).key("focus_topic").value("开户").expiresAt(now.minusDays(1)).build());
        // 别人的记忆
        dao.insert(Fact.builder().owner(newOwner()).key("business_line").value("对私").build());
        // 同 owner 但 scope=DEPT：不该出现在"本人"召回里。
        // ⚠️ org_path 也要随机：DEPT 行是按 org_path 共享的（这正是 DEPT 的语义），
        // 用一个固定值会污染别的 DEPT 测试 —— 本套测试"随机 owner 就不用清理"的假设
        // 在 DEPT 上**不成立**，这里必须显式随机化
        dao.insert(Fact.builder().owner(owner).scope(FactScope.DEPT).orgPath(newOrg())
                .key("focus_topic").value("外汇").build());

        List<UserMemoryRow> rows = dao.selectUserFacts(owner, 5, now, 10);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).factValue()).isEqualTo("对公");
    }

    @Test
    @DisplayName("召回：密级条件真的下推进了 SQL（同一 owner 两条，信封密级 3 只看得到低的）")
    void recallPushesClearanceFilterIntoSql() {
        String owner = newOwner();
        LocalDateTime now = now();
        dao.insert(Fact.builder().owner(owner).key("business_line").value("对公").clearance(2).build());
        dao.insert(Fact.builder().owner(owner).key("preferred_org").value("0100").clearance(4).build());

        assertThat(dao.selectUserFacts(owner, 5, now, 10))
                .as("信封密级 5：两条都能读")
                .hasSize(2);
        assertThat(dao.selectUserFacts(owner, 3, now, 10))
                .as("信封密级 3：只读得到密级 2 的那条")
                .extracting(UserMemoryRow::factKey)
                .containsExactly("business_line");
        assertThat(dao.selectUserFacts(owner, 1, now, 10))
                .as("信封密级 1：都读不到")
                .isEmpty();
    }

    @Test
    @DisplayName("召回排序：COALESCE(last_hit_at, created_at) —— 刚写入还没命中过的事实不会排在最后")
    void recallOrdersByCoalescedHitTime() {
        String owner = newOwner();
        LocalDateTime now = now();
        UserMemoryRow fresh = Fact.builder().owner(owner).key("business_line").value("对公").build();
        UserMemoryRow old = Fact.builder().owner(owner).key("preferred_org").value("0100").build();
        dao.insert(fresh);
        dao.insert(old);

        // created_at 是 DATETIME（只到秒），同一秒插两行构不成稳定序 ——
        // 所以这里用 JdbcTemplate 精确摆时间。这不是"绕开 DAO"，是"摆不出那么多秒"
        jdbcTemplate.update("UPDATE user_memory SET created_at = ? WHERE fact_uid = ?",
                now.minusDays(10), old.factUid());

        assertThat(dao.selectUserFacts(owner, 5, now, 10))
                .as("fresh 从没被命中过（last_hit_at 为 NULL），但它的 created_at 更新 ⇒ 排在前面")
                .extracting(UserMemoryRow::factKey)
                .containsExactly("business_line", "preferred_org");

        // 反过来：旧的那条一旦被命中，就该顶到前面。
        // 命中时间用 now+1 天而不是 now+1 分钟：这条断言的对照物是 fresh 的 created_at
        // （**数据库**的 CURRENT_TIMESTAMP），留足余量免得被 DB 与 JVM 的时钟差异咬到
        dao.touchHit(List.of(old.factUid()), now.plusDays(1));

        assertThat(dao.selectUserFacts(owner, 5, now, 10))
                .as("命中时间一旦晚于 fresh 的 created_at，顺序翻转")
                .extracting(UserMemoryRow::factKey)
                .containsExactly("preferred_org", "business_line");
    }

    @Test
    @DisplayName("DEPT 召回：org_path 精确匹配；orgPath 为 null 时恒不返回（fail-closed）")
    void deptFactsMatchOrgPathExactly() {
        // ⚠️ org_path 必须随机：DEPT 行是按 org_path 共享的（这正是 DEPT 的语义 ——
        // 同组织的任何人都看得到），所以"随机 owner ⇒ 测试互不干扰"这个在别处成立的假设
        // 在这里**不成立**。用固定值的话，别处留下的 DEPT 行会被这条查询一并捞到
        String orgA = newOrg();
        String orgPrefix = orgA + "1";
        LocalDateTime now = now();
        dao.insert(Fact.builder().owner(newOwner()).scope(FactScope.DEPT).orgPath(orgA)
                .key("focus_topic").value("开户").build());
        // 前缀相同的另一个组织：精确匹配就不该被捞到（与 buildAccessFilter 的 org_codes in [...] 同口径）
        dao.insert(Fact.builder().owner(newOwner()).scope(FactScope.DEPT).orgPath(orgPrefix)
                .key("focus_topic").value("外汇").build());

        assertThat(dao.selectDeptFacts(orgA, 5, now, 10))
                .as("精确匹配：前缀更长的组织不该被捞进来（不是前缀匹配）")
                .extracting(UserMemoryRow::factValue)
                .containsExactly("开户");
        assertThat(dao.selectDeptFacts(orgPrefix, 5, now, 10))
                .extracting(UserMemoryRow::factValue)
                .containsExactly("外汇");
        assertThat(dao.selectDeptFacts(null, 5, now, 10))
                .as("orgPath 为 null ⇒ SQL 里 = NULL 永不为真 ⇒ fail-closed，而不是返回全部")
                .isEmpty();
    }

    // ==================================================================
    // 写入路径：配额计数、三种写入形态、约束
    // ==================================================================

    @Test
    @DisplayName("配额计数：被取代的历史行不占配额")
    void countActiveExcludesSuperseded() {
        String owner = newOwner();
        LocalDateTime now = now();
        dao.insert(Fact.builder().owner(owner).key("business_line").value("对公").build());
        UserMemoryRow replaced = Fact.builder().owner(owner).key("preferred_org").value("0100").build();
        dao.insert(replaced);
        assertThat(dao.countActive(owner)).isEqualTo(2);

        dao.supersede(replaced.factUid(), now, UUID.randomUUID().toString());

        assertThat(dao.countActive(owner))
                .as("取代后旧行 valid_to 非空 ⇒ 不再占配额。漏了这条件是「200 条满了 → 改口也改不了」")
                .isEqualTo(1);
        // 而另一行被取代之后，同键可以重新写入（历史行不挡路）
        dao.insert(Fact.builder().owner(owner).key("preferred_org").value("0200").build());
        assertThat(dao.countActive(owner)).isEqualTo(2);
    }

    @Test
    @DisplayName("uk_owner_active 生效：同 owner + 同 scope + 同 key 的第二条有效行被拒")
    void duplicateActiveKeyIsRejected() {
        String owner = newOwner();
        dao.insert(Fact.builder().owner(owner).key("business_line").value("对公").build());

        assertThatThrownBy(() -> dao.insert(Fact.builder().owner(owner).key("business_line").value("对私").build()))
                .as("唯一索引建在生成列 active_key 上：唯一索引对 NULL 不生效，"
                        + "所以必须先把「有效」压成非 NULL 值才约束得住")
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("selectActiveByKey：拿到当前有效行；被取代之后返回 null")
    void selectActiveByKeyReturnsNullAfterSupersede() {
        String owner = newOwner();
        UserMemoryRow row = Fact.builder().owner(owner).key("business_line").value("对公").build();
        dao.insert(row);

        assertThat(dao.selectActiveByKey(owner, FactScope.USER, "business_line"))
                .extracting(UserMemoryRow::factUid).isEqualTo(row.factUid());

        dao.supersede(row.factUid(), now(), UUID.randomUUID().toString());

        assertThat(dao.selectActiveByKey(owner, FactScope.USER, "business_line")).isNull();
        // 其它键不受影响
        assertThat(dao.selectActiveByKey(owner, FactScope.USER, "preferred_org")).isNull();
    }

    @Test
    @DisplayName("supersede 的乐观保护：同一行被取代两次，第二次影响 0 行")
    void supersedeIsGuardedByValidToIsNull() {
        String owner = newOwner();
        UserMemoryRow row = Fact.builder().owner(owner).key("business_line").value("对公").build();
        dao.insert(row);

        assertThat(dao.supersede(row.factUid(), now(), "successor-1")).isEqualTo(1);

        assertThat(dao.supersede(row.factUid(), now(), "successor-2"))
                .as("WHERE valid_to IS NULL ⇒ 已被取代的行不再被覆盖，"
                        + "否则 superseded_by 会被改指向一个不在链上的键")
                .isZero();
        assertThat(dao.selectActiveByKey(owner, FactScope.USER, "business_line")).isNull();
    }

    @Test
    @DisplayName("refresh（同值重申）：只推 expires_at 与 last_hit_at，不新增行、不改来源")
    void refreshExtendsExpiryWithoutTouchingSource() {
        String owner = newOwner();
        LocalDateTime now = now();
        UserMemoryRow row = Fact.builder().owner(owner).key("business_line").value("对公")
                .source("sess-original", 7)
                .validFrom(now).expiresAt(now.plusDays(90)).build();
        dao.insert(row);

        assertThat(dao.refresh(row.factUid(), now.plusDays(30), now.plusDays(120))).isEqualTo(1);

        UserMemoryRow got = dao.selectActiveByKey(owner, FactScope.USER, "business_line");
        assertThat(got.expiresAt()).isEqualTo(now.plusDays(120));
        assertThat(got.lastHitAt()).isEqualTo(now.plusDays(30));
        assertThat(got.sourceSessionId())
                .as("重申不改变「这条记忆最初从哪来」—— 来源是审计信息，不能被覆盖")
                .isEqualTo("sess-original");
        assertThat(got.sourceTurnNo()).isEqualTo(7);
        assertThat(dao.countActive(owner)).as("续期不新增行").isEqualTo(1);
        // 续期对已取代的行无效（同一道乐观保护）
        dao.supersede(row.factUid(), now, UUID.randomUUID().toString());
        assertThat(dao.refresh(row.factUid(), now, now.plusDays(200))).isZero();
    }

    @Test
    @DisplayName("touchHit：批量且只更新给定的 uid")
    void touchHitUpdatesOnlyGivenUids() {
        String owner = newOwner();
        LocalDateTime now = now();
        UserMemoryRow hit = Fact.builder().owner(owner).key("business_line").value("对公").build();
        UserMemoryRow notHit = Fact.builder().owner(owner).key("preferred_org").value("0100").build();
        dao.insert(hit);
        dao.insert(notHit);

        assertThat(dao.touchHit(List.of(hit.factUid()), now)).isEqualTo(1);

        assertThat(dao.selectActiveByKey(owner, FactScope.USER, "business_line").lastHitAt()).isEqualTo(now);
        assertThat(dao.selectActiveByKey(owner, FactScope.USER, "preferred_org").lastHitAt())
                .as("miss 就是 miss：没注入 prompt 的行不该被标成「最近常看」，"
                        + "否则污染阶段 4 巡检③的 LRU 判断")
                .isNull();
    }

    // ------------------------------------------------------------------
    // 测试数据构造
    // ------------------------------------------------------------------

    /**
     * 随机 owner。<b>必须是 32 字符</b>（{@code owner_id VARCHAR(32)}）——
     * 直接拿带连字符的 UUID 会超长。随机化之后测试之间不会互相干扰，也就不需要清理。
     */
    private static String newOwner() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 随机组织路径。同样是为了不清理 —— 但理由与 {@link #newOwner()} <b>不同</b>：
     * DEPT 行按 {@code org_path} 共享，固定值会跨测试串味（见 {@code deptFactsMatchOrgPathExactly}）。
     */
    private static String newOrg() {
        return "ORG-" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 截到秒。{@code DATETIME} 列没有小数秒，带纳秒的 {@code now()} 写进去会被截断，
     * 断言"读回来的等于写进去的"就会假失败 —— 这是时间列往返最容易踩的一处。
     */
    private static LocalDateTime now() {
        return LocalDateTime.now().withNano(0);
    }

    /** 一行事实的构造器。字段多，链式写比六个重载的静态工厂清楚。 */
    private static final class Fact {
        private String uid = UUID.randomUUID().toString();
        /** 默认就给一个随机 owner：忘了调 {@code owner(...)} 时是"互不干扰"，而不是撞 NOT NULL 报错 */
        private String owner = newOwner();
        private FactScope scope = FactScope.USER;
        private String key = "business_line";
        private String value = "对公";
        private int clearance = HIGH_CLEARANCE;
        private String orgPath;
        private String sourceSessionId = "sess-1";
        private int sourceTurnNo = 1;
        private String refDocId;
        private LocalDateTime validFrom;
        private LocalDateTime expiresAt;
        private long permAuthVersion;

        static Fact builder() {
            return new Fact();
        }

        Fact uid(String v) { this.uid = v; return this; }
        Fact owner(String v) { this.owner = v; return this; }
        Fact scope(FactScope v) { this.scope = v; return this; }
        Fact key(String v) { this.key = v; return this; }
        Fact value(String v) { this.value = v; return this; }
        Fact clearance(int v) { this.clearance = v; return this; }
        Fact orgPath(String v) { this.orgPath = v; return this; }
        Fact source(String sessionId, int turnNo) {
            this.sourceSessionId = sessionId;
            this.sourceTurnNo = turnNo;
            return this;
        }
        Fact refDocId(String v) { this.refDocId = v; return this; }
        Fact validFrom(LocalDateTime v) { this.validFrom = v; return this; }
        Fact expiresAt(LocalDateTime v) { this.expiresAt = v; return this; }
        Fact permAuthVersion(long v) { this.permAuthVersion = v; return this; }

        UserMemoryRow build() {
            LocalDateTime from = validFrom != null ? validFrom : now();
            return new UserMemoryRow(uid, owner, scope, key, value, clearance, orgPath,
                    sourceSessionId, sourceTurnNo, refDocId,
                    from, null, null,
                    expiresAt != null ? expiresAt : from.plusDays(90),
                    MemoryStatus.ACTIVE, null, permAuthVersion, null);
        }
    }
}
