package org.agent.memory.dao;

import org.agent.memory.model.AnswerDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 集成测试：连真实 MySQL 验证 chat_message 的读写往返，不调 LLM。
 * <p>
 * <b>前置条件</b>：本机 MySQL(127.0.0.1:3306/fan_ai_agent) 在运行，且已执行
 * {@code sql/chat_message.sql} 建表。
 * <p>
 * 显式关闭 vectorstore：本测试只覆盖记忆表（MySQL），不涉及 RAG 向量库（PG）。
 * <p>
 * 这个测试存在的理由：{@code MyBatis + record 构造映射 + enum 列 + JSON 列 + DATE 列}
 * 是本次改动里唯一有真实不确定性的组合 —— record 的自动构造映射已被 {@code SessionRow}
 * 证明可行，但 enum 与 JSON 两列没验过。先在 DAO 这一层把映射跑通，再往上写业务。
 * <p>
 * 测试数据一律用随机 UUID 当 session_id，所以不需要清理代码，也不会污染其它测试。
 */
@SpringBootTest(properties = "rag.vectorstore.enabled=false")
@ActiveProfiles("local")
class ChatMessageDaoContextTest {

    private static final String USER = "10086";

    @Autowired
    private ChatMessageDao dao;

    /**
     * 只用来验证「漏写 clearance_level 时数据库给什么」。这里拿到的必须是 **MySQL** 那个
     * {@code JdbcTemplate}。
     * <p>
     * ⚠️ 曾经这里写的是「{@code RagVectorStoreConfig} 里的 PG 连接是内联 {@code new JdbcTemplate(...)}、
     * 并没有暴露成 bean，所以自动配置照常为 {@code spring.datasource} 配一个」—— 那是**照着推断写的，
     * 而且推断错了**：那个 PG 连接确实是个 {@code DataSource} bean，探针打出过
     * {@code PROBE-DS=[ragVectorDataSource]}、{@code PROBE-URL=jdbc:postgresql://…/rag_kb}。
     * 真实情况是「用户在，自动配置就整体退让」，本测试能拿到 MySQL 是因为它显式关掉了 vectorstore。
     * <p>
     * 现在无论开关怎么拨，这里都会拿到 MySQL：业务库已在
     * {@code org.agent.config.DataSourceConfig} 里显式声明并标了 {@code @Primary}。
     * 开关仍然关着 —— 本测试只覆盖记忆表，不该顺带要求一个活着的 PG。
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("一轮两行往返：列别名映射正确（含 enum / JSON / DATE / DATETIME 四类列）")
    void insertAndSelectRoundTrip() {
        String sid = UUID.randomUUID().toString();
        dao.insertTurn(List.of(userRow(sid, 1, AnswerDecision.PROCEED),
                assistantRow(sid, 1, AnswerDecision.PROCEED)));

        List<ChatMessageRow> rows = dao.selectRecentTurns(sid, 10);

        assertThat(rows).hasSize(2);
        // 轮内顺序：id ASC 保证 user 行在前
        assertThat(rows.get(0).role()).isEqualTo("user");
        assertThat(rows.get(1).role()).isEqualTo("assistant");

        ChatMessageRow user = rows.get(0);
        assertThat(user.sessionId()).isEqualTo(sid);
        assertThat(user.userId()).isEqualTo(USER);
        assertThat(user.turnNo()).isEqualTo(1);
        assertThat(user.userMessage()).isEqualTo("第1轮提问");
        assertThat(user.answerDecision()).isEqualTo(AnswerDecision.PROCEED);
        assertThat(user.refusalReason()).isNull();
        assertThat(user.bizDate()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(user.clearanceLevel()).isEqualTo(4);
        assertThat(user.permAuthVersion()).isEqualTo(7L);
        // created_at 由数据库默认值填（insert 的列清单里没有它），能被读回来
        assertThat(user.createdAt()).isNotNull();
        assertThat(user.expiresAt()).isNotNull();
        // user 行没有答案也没有引文指针
        assertThat(user.answerText()).isNull();
        assertThat(user.citedChunkRefs()).isNull();

        ChatMessageRow assistant = rows.get(1);
        assertThat(assistant.answerText()).isEqualTo("第1轮回答");
        assertThat(assistant.userMessage()).isNull();
        // JSON 列往返：能原样读回来，且里面只有指针、没有 chunk 正文
        assertThat(assistant.citedChunkRefs())
                .contains("\"doc_id\"", "DOC-1", "\"chunk_id\"", "chunk-a");
    }

    @Test
    @DisplayName("窗口查询：取最近 N 行，按 turn_no 倒序、轮内正序")
    void selectRecentTurnsOrdersByTurnDescThenIdAsc() {
        String sid = UUID.randomUUID().toString();
        for (int turn = 1; turn <= 3; turn++) {
            dao.insertTurn(List.of(userRow(sid, turn, AnswerDecision.PROCEED),
                    assistantRow(sid, turn, AnswerDecision.PROCEED)));
        }

        List<ChatMessageRow> rows = dao.selectRecentTurns(sid, 4);

        // 只取到最近两轮（turn 3、turn 2），且最近的排在最前
        assertThat(rows).extracting(ChatMessageRow::role)
                .containsExactly("user", "assistant", "user", "assistant");
        assertThat(rows).extracting(ChatMessageRow::turnNo)
                .containsExactly(3, 3, 2, 2);
    }

    @Test
    @DisplayName("拒答轮：decision 与 refusalReason 一并落库")
    void refusedTurnRoundTrip() {
        String sid = UUID.randomUUID().toString();
        dao.insertTurn(List.of(userRow(sid, 1, AnswerDecision.REFUSED),
                assistantRow(sid, 1, AnswerDecision.REFUSED)));

        List<ChatMessageRow> rows = dao.selectRecentTurns(sid, 10);

        assertThat(rows.get(0).answerDecision()).isEqualTo(AnswerDecision.REFUSED);
        assertThat(rows.get(0).refusalReason()).isEqualTo("NO_HIT");
    }

    @Test
    @DisplayName("selectMaxTurnNo：空会话返回 0，写入一轮后返回 1")
    void selectMaxTurnNo() {
        String sid = UUID.randomUUID().toString();
        assertThat(dao.selectMaxTurnNo(sid)).isZero();

        dao.insertTurn(List.of(userRow(sid, 1, AnswerDecision.PROCEED),
                assistantRow(sid, 1, AnswerDecision.PROCEED)));

        assertThat(dao.selectMaxTurnNo(sid)).isEqualTo(1);
    }

    @Test
    @DisplayName("uk_session_turn 生效：同一会话同一轮同一角色重复插入被拒（并发重号的防线）")
    void duplicateTurnIsRejected() {
        String sid = UUID.randomUUID().toString();
        List<ChatMessageRow> turn = List.of(userRow(sid, 1, AnswerDecision.PROCEED),
                assistantRow(sid, 1, AnswerDecision.PROCEED));
        dao.insertTurn(turn);

        assertThatThrownBy(() -> dao.insertTurn(turn)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("clearance_level 往返：TINYINT UNSIGNED 映射正常，且不与同轮另一行强制同值")
    void clearanceLevelRoundTrip() {
        String sid = UUID.randomUUID().toString();
        // 同一轮故意给两个不同的值：DAO 只负责搬字节，"同轮两行同密级"是 Service 层的义务，
        // 这里断言 DAO **没有**偷偷替调用方做归一化（否则 Service 的 bug 会被掩盖）
        dao.insertTurn(List.of(userRow(sid, 1, AnswerDecision.PROCEED, 1),
                assistantRow(sid, 1, AnswerDecision.PROCEED, 3)));

        List<ChatMessageRow> rows = dao.selectRecentTurns(sid, 10);

        assertThat(rows).extracting(ChatMessageRow::clearanceLevel).containsExactly(1, 3);
    }

    @Test
    @DisplayName("fail-closed 默认值：原生 INSERT 漏写 clearance_level → 读回 5（谁都读不到）")
    void missingClearanceLevelDefaultsToHighest() {
        String sid = UUID.randomUUID().toString();
        // 故意绕开 DAO：模拟"将来另一条写入路径漏了这列"。这列一旦漏写，
        // 结果是"谁都读不到"而不是"谁都能读到" —— DEFAULT 5 的全部意义就在这里
        jdbcTemplate.update("""
                INSERT INTO chat_message
                    (session_id, user_id, turn_no, role, user_message, answer_decision)
                VALUES (?, ?, 1, 'user', '漏写密级的行', 'PROCEED')
                """, sid, USER);

        List<ChatMessageRow> rows = dao.selectRecentTurns(sid, 10);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).clearanceLevel()).isEqualTo(5);
    }

    @Test
    @DisplayName("区间查询：只取该轮号区间，按轮号升序、轮内 user 在前（会话压缩的输入）")
    void selectTurnRangeTakesExactlyTheRange() {
        String sid = UUID.randomUUID().toString();
        for (int turn = 1; turn <= 5; turn++) {
            dao.insertTurn(List.of(userRow(sid, turn, AnswerDecision.PROCEED),
                    assistantRow(sid, turn, AnswerDecision.PROCEED)));
        }

        // 只取第 2、3 轮 —— 两端的 1 与 4、5 都不能出现（区间是闭区间）
        List<ChatMessageRow> rows = dao.selectTurnRange(sid, 2, 3);

        assertThat(rows).extracting(ChatMessageRow::turnNo).containsExactly(2, 2, 3, 3);
        // ⚠️ 升序，与 selectRecentTurns 的倒序**相反**：压缩要按时间顺序读
        assertThat(rows).extracting(ChatMessageRow::role)
                .containsExactly("user", "assistant", "user", "assistant");
        assertThat(rows.get(0).userMessage()).isEqualTo("第2轮提问");
        assertThat(rows.get(1).answerText()).isEqualTo("第2轮回答");
    }

    @Test
    @DisplayName("区间查询：拒答轮照样返回（压缩与「进不进 prompt」是两套口径）")
    void selectTurnRangeKeepsRefusedTurns() {
        String sid = UUID.randomUUID().toString();
        dao.insertTurn(List.of(userRow(sid, 1, AnswerDecision.REFUSED),
                assistantRow(sid, 1, AnswerDecision.REFUSED)));

        // ⚠️ 与 loadRecentTurns 的关键差别：那边拒答轮整轮丢弃，这边必须拿到它 ——
        // 「用户问了 XX 未获答复」是下一轮"那它呢"唯一的定位线索，
        // 只是答案文案不给压缩器（见 CompactionRequest.SourceTurn）
        List<ChatMessageRow> rows = dao.selectTurnRange(sid, 1, 1);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).answerDecision()).isEqualTo(AnswerDecision.REFUSED);
    }

    @Test
    @DisplayName("区间查询：from > through 返回空（不报错），且不会漏到别的会话去")
    void selectTurnRangeEmptyRangeAndOtherSessions() {
        String sid = UUID.randomUUID().toString();
        String other = UUID.randomUUID().toString();
        dao.insertTurn(List.of(userRow(sid, 1, AnswerDecision.PROCEED),
                assistantRow(sid, 1, AnswerDecision.PROCEED)));
        dao.insertTurn(List.of(userRow(other, 2, AnswerDecision.PROCEED),
                assistantRow(other, 2, AnswerDecision.PROCEED)));

        assertThat(dao.selectTurnRange(sid, 3, 2)).as("空区间").isEmpty();
        // other 会话的第 2 轮不能被这个区间捞到（会话范围由 SQL 限定，不是靠 Java 过滤）
        assertThat(dao.selectTurnRange(sid, 1, 5)).as("只含本会话")
                .extracting(ChatMessageRow::sessionId).containsOnly(sid);
    }

    // ------------------------------------------------------------------
    // 测试数据构造
    // ------------------------------------------------------------------

    private static ChatMessageRow userRow(String sid, int turnNo, AnswerDecision decision) {
        return userRow(sid, turnNo, decision, 4);
    }

    private static ChatMessageRow userRow(String sid, int turnNo, AnswerDecision decision,
                                         int clearanceLevel) {
        return new ChatMessageRow(sid, USER, turnNo, "user",
                "第" + turnNo + "轮提问", null, null,
                decision, reasonOf(decision), LocalDate.of(2026, 9, 25), clearanceLevel, 7L,
                null, LocalDateTime.now().plusDays(30));
    }

    private static ChatMessageRow assistantRow(String sid, int turnNo, AnswerDecision decision) {
        return assistantRow(sid, turnNo, decision, 4);
    }

    private static ChatMessageRow assistantRow(String sid, int turnNo, AnswerDecision decision,
                                              int clearanceLevel) {
        return new ChatMessageRow(sid, USER, turnNo, "assistant",
                null, "第" + turnNo + "轮回答",
                "[{\"doc_id\":\"DOC-1\",\"doc_version\":\"v2\",\"chunk_id\":\"chunk-a\"}]",
                decision, reasonOf(decision), LocalDate.of(2026, 9, 25), clearanceLevel, 7L,
                null, LocalDateTime.now().plusDays(30));
    }

    private static String reasonOf(AnswerDecision decision) {
        return decision == AnswerDecision.REFUSED ? "NO_HIT" : null;
    }
}
