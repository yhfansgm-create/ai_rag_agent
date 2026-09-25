package org.agent.memory.dao;

import org.agent.memory.model.AnswerDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
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

    // ------------------------------------------------------------------
    // 测试数据构造
    // ------------------------------------------------------------------

    private static ChatMessageRow userRow(String sid, int turnNo, AnswerDecision decision) {
        return new ChatMessageRow(sid, USER, turnNo, "user",
                "第" + turnNo + "轮提问", null, null,
                decision, reasonOf(decision), LocalDate.of(2026, 9, 25), 7L,
                null, LocalDateTime.now().plusDays(30));
    }

    private static ChatMessageRow assistantRow(String sid, int turnNo, AnswerDecision decision) {
        return new ChatMessageRow(sid, USER, turnNo, "assistant",
                null, "第" + turnNo + "轮回答",
                "[{\"doc_id\":\"DOC-1\",\"doc_version\":\"v2\",\"chunk_id\":\"chunk-a\"}]",
                decision, reasonOf(decision), LocalDate.of(2026, 9, 25), 7L,
                null, LocalDateTime.now().plusDays(30));
    }

    private static String reasonOf(AnswerDecision decision) {
        return decision == AnswerDecision.REFUSED ? "NO_HIT" : null;
    }
}
