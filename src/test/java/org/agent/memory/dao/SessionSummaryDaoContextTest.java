package org.agent.memory.dao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 集成测试：连真实 MySQL 验证 {@code session_summary} 的读写与约束行为，不调 LLM。
 * <p>
 * <b>前置条件</b>：本机 MySQL(127.0.0.1:3306/fan_ai_agent) 在运行，且已执行
 * {@code sql/session_summary.sql} 建表（⚠️ 带 {@code --default-character-set=utf8mb4}，见该文件头）。
 * <p>
 * <b>这个测试存在的理由</b>：摘要这一层继承了两套不变量，而它们都只能靠真库验：
 * <ol>
 *   <li><b>至多一版当前有效摘要</b>（生成列 {@code active_session_id} + {@code uk_session_active}）
 *       —— 与 {@code user_memory.active_key} 同一手法。这是"整份摘要"能被当成一个整体
 *       来判定密级的前提：若同一会话能同时存在两版有效摘要，"读当前摘要"就有了歧义</li>
 *   <li><b>密级列的 fail-closed 默认值</b>（{@code DEFAULT 5}）—— 漏写列时是"谁都读不到"，
 *       不是"谁都能读到"</li>
 * </ol>
 * 测试数据一律用随机 {@code session_id}（36 位 UUID）与随机 {@code user_id}（32 位），
 * 所以不需要清理代码。
 */
@SpringBootTest(properties = "rag.vectorstore.enabled=false")
@ActiveProfiles("local")
class SessionSummaryDaoContextTest {

    @Autowired
    private SessionSummaryDao dao;

    /** 只用来验 DEFAULT 行为（绕开 DAO 原生 INSERT） */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("往返：插入一版摘要后能按会话读到，列映射正确")
    void insertAndSelectActiveRoundTrip() {
        String sid = newSession();
        String uid = UUID.randomUUID().toString();
        LocalDateTime now = now();
        SessionSummaryRow row = row(sid, uid)
                .covers(1, 4)
                .clearance(3)
                .text("上一轮讨论了《大额存单管理办法》的起存金额相关规定")
                .sourceTurnCount(3)
                .validFrom(now)
                .expiresAt(now.plusDays(30))
                .build();

        assertThat(dao.insert(row)).isEqualTo(1);

        SessionSummaryRow got = dao.selectActive(sid, now);

        assertThat(got).isNotNull();
        assertThat(got.summaryUid()).isEqualTo(uid);
        assertThat(got.sessionId()).isEqualTo(sid);
        assertThat(got.coversFromTurnNo()).isEqualTo(1);
        assertThat(got.coversThroughTurnNo())
                .as("covers_through_turn_no 是**不含**的上界 —— 也就是「下一轮该从哪开始」")
                .isEqualTo(4);
        assertThat(got.clearanceLevel()).isEqualTo(3);
        assertThat(got.summaryText()).contains("大额存单管理办法");
        assertThat(got.sourceTurnCount()).isEqualTo(3);
        assertThat(got.validFrom()).isEqualTo(now);
        assertThat(got.expiresAt()).isEqualTo(now.plusDays(30));
        assertThat(got.validTo()).isNull();
        assertThat(got.supersededBy()).isNull();
        assertThat(got.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("fail-closed 默认值：原生 INSERT 漏写 clearance_level → 读回 5（谁都读不到）")
    void missingClearanceLevelDefaultsToHighest() {
        String sid = newSession();
        // 故意绕开 DAO：模拟"将来另一条写入路径漏了这列"（例如压缩器直接把模型输出接进来）
        jdbcTemplate.update("""
                INSERT INTO session_summary (summary_uid, session_id, user_id,
                       covers_from_turn_no, covers_through_turn_no, summary_text, source_turn_count)
                VALUES (?, ?, ?, 1, 4, '漏写密级的摘要', 3)
                """, UUID.randomUUID().toString(), sid, newUser());

        SessionSummaryRow got = dao.selectActive(sid, now());

        assertThat(got).isNotNull();
        assertThat(got.clearanceLevel())
                .as("漏写密级 = 谁都读不到（DEFAULT 5 是最高密级）。"
                        + "这是安全的失败方向：摘要继承密级一旦漏了，绝不能变成'谁都能读'")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("过期摘要不返回")
    void expiredSummaryIsNotReturned() {
        String sid = newSession();
        LocalDateTime now = now();
        dao.insert(row(sid, UUID.randomUUID().toString())
                .covers(1, 4).text("过期的那版").expiresAt(now.minusDays(1)).build());

        assertThat(dao.selectActive(sid, now))
                .as("expires_at 与 chat_message 同一个 retention-days 口径：到期不删，只是不再注入")
                .isNull();
    }

    @Test
    @DisplayName("含 null expires_at 的摘要永远可读（expires_at 为 NULL 表示不设过期）")
    void summaryWithoutExpiryIsAlwaysReadable() {
        String sid = newSession();
        LocalDateTime now = now();
        dao.insert(row(sid, UUID.randomUUID().toString())
                .covers(1, 4).text("不设过期的那版").expiresAt(null).build());

        assertThat(dao.selectActive(sid, now)).isNotNull();
    }

    @Test
    @DisplayName("uk_session_active 生效：同一会话的第二版有效摘要被拒（取代必须先盖终章）")
    void duplicateActiveSummaryIsRejected() {
        String sid = newSession();
        dao.insert(row(sid, UUID.randomUUID().toString()).covers(1, 4).text("第一版").build());

        assertThatThrownBy(() -> dao.insert(row(sid, UUID.randomUUID().toString())
                .covers(1, 5).text("第二版").build()))
                .as("唯一索引建在生成列 active_session_id 上。"
                        + "约束的意义：同一会话永远只可能有一版'当前'摘要，读侧才没有歧义")
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("取代：旧版盖终章后不再被读到，新版接上（覆盖区间随之右移）")
    void supersedeClosesOldVersionAndLetsNewOneIn() {
        String sid = newSession();
        LocalDateTime now = now();
        String oldUid = UUID.randomUUID().toString();
        String newUid = UUID.randomUUID().toString();
        dao.insert(row(sid, oldUid).covers(1, 4).text("覆盖第 1-3 轮").sourceTurnCount(3).build());

        assertThat(dao.supersede(oldUid, now, newUid)).isEqualTo(1);
        dao.insert(row(sid, newUid).covers(1, 6).text("覆盖第 1-5 轮").sourceTurnCount(5).build());

        SessionSummaryRow got = dao.selectActive(sid, now);

        assertThat(got.summaryUid()).isEqualTo(newUid);
        assertThat(got.coversThroughTurnNo()).isEqualTo(6);
        assertThat(got.sourceTurnCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("supersede 的乐观保护：同一版被取代两次，第二次影响 0 行")
    void supersedeIsGuardedByValidToIsNull() {
        String sid = newSession();
        String uid = UUID.randomUUID().toString();
        dao.insert(row(sid, uid).covers(1, 4).text("只有一版").build());

        assertThat(dao.supersede(uid, now(), UUID.randomUUID().toString())).isEqualTo(1);
        assertThat(dao.supersede(uid, now(), UUID.randomUUID().toString())).isZero();
        assertThat(dao.selectActive(sid, now())).isNull();
    }

    // ------------------------------------------------------------------
    // 测试数据构造
    // ------------------------------------------------------------------

    /** {@code session_id CHAR(36)} —— 正好是带连字符的 UUID 长度 */
    private static String newSession() {
        return UUID.randomUUID().toString();
    }

    /** {@code user_id VARCHAR(32)} —— 去掉连字符的 UUID 正好 32 位 */
    private static String newUser() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 截到秒：{@code DATETIME} 没有小数秒，带纳秒写进去再读回来会不等（见 UserMemoryDaoContextTest 同款说明） */
    private static LocalDateTime now() {
        return LocalDateTime.now().withNano(0);
    }

    private static Summary row(String sid, String uid) {
        return new Summary(sid, uid);
    }

    /** 见 {@code UserMemoryDaoContextTest.Fact}：字段多，链式比一串重载清爽 */
    private static final class Summary {
        private final String sessionId;
        private final String uid;
        private String userId = newUser();
        private int coversFrom = 1;
        private int coversThrough = 4;
        private int clearance = 3;
        private String text = "上一轮讨论了某制度的起存金额相关规定";
        private int sourceTurnCount = 3;
        private LocalDateTime validFrom;
        private LocalDateTime expiresAt;
        /** 区分「没设过」与「显式设为 null（= 不设过期）」—— 只靠字段本身分不出这两者 */
        private boolean expirySet;
        private long permAuthVersion;

        Summary(String sessionId, String uid) {
            this.sessionId = sessionId;
            this.uid = uid;
        }

        Summary covers(int from, int through) { this.coversFrom = from; this.coversThrough = through; return this; }
        Summary clearance(int v) { this.clearance = v; return this; }
        Summary text(String v) { this.text = v; return this; }
        Summary sourceTurnCount(int v) { this.sourceTurnCount = v; return this; }
        Summary validFrom(LocalDateTime v) { this.validFrom = v; return this; }

        Summary expiresAt(LocalDateTime v) {
            this.expiresAt = v;
            this.expirySet = true;
            return this;
        }

        SessionSummaryRow build() {
            LocalDateTime from = validFrom != null ? validFrom : now();
            return new SessionSummaryRow(uid, sessionId, userId,
                    coversFrom, coversThrough, clearance, text, sourceTurnCount,
                    from, null, null, permAuthVersion,
                    expirySet ? expiresAt : from.plusDays(30), null);
        }
    }
}
