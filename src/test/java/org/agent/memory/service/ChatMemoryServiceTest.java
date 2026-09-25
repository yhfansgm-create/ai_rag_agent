package org.agent.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.agent.memory.MemoryProperties;
import org.agent.memory.dao.ChatMessageDao;
import org.agent.memory.dao.ChatMessageRow;
import org.agent.memory.model.AnswerDecision;
import org.agent.memory.model.ChatTurn;
import org.agent.memory.model.CitedChunkRef;
import org.agent.memory.model.TurnFacts;
import org.agent.permission.SessionEnvelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito 测试：不打库。ObjectMapper 用真实的（JSON 往返是本类的职责之一）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatMemoryService：读窗口规则与写回")
class ChatMemoryServiceTest {

    private static final String SID = "sess-1";
    private static final String USER = "10086";
    private static final MemoryProperties PROPS =
            new MemoryProperties(true, 3, 2000, 30);

    @Mock
    private ChatMessageDao dao;

    private ChatMemoryService service() {
        return new ChatMemoryService(dao, new ObjectMapper());
    }

    // ------------------------------------------------------------------
    // 读窗口
    // ------------------------------------------------------------------

    @Test
    @DisplayName("读窗口：时间正序返回，且轮内 user 在 assistant 之前（防整体 reverse 回归）")
    void loadReturnsChronologicalOrder() {
        // 模拟 SQL 的返回顺序：turn_no DESC（最近的轮在前）、轮内 id ASC（user 在前）
        when(dao.selectRecentTurns(anyString(), anyInt())).thenReturn(List.of(
                row(3, "user", AnswerDecision.PROCEED),
                row(3, "assistant", AnswerDecision.PROCEED),
                row(2, "user", AnswerDecision.PROCEED),
                row(2, "assistant", AnswerDecision.PROCEED)));

        List<ChatTurn> turns = service().loadRecentTurns(envelope(USER), PROPS);

        // 轮序被反转成时间正序（2 在 3 之前），但**轮内**顺序没有被翻掉
        assertThat(turns).extracting(ChatTurn::turnNo).containsExactly(2, 3);
        assertThat(turns.get(0).userMessage()).isEqualTo("q2");
        assertThat(turns.get(0).answerText()).isEqualTo("a2");
    }

    @Test
    @DisplayName("读窗口：请求行数 = 窗口轮数 × 2")
    void loadRequestsTwiceTheWindow() {
        when(dao.selectRecentTurns(anyString(), anyInt())).thenReturn(List.of());

        service().loadRecentTurns(envelope(USER), PROPS);

        verify(dao).selectRecentTurns(SID, 6);
    }

    @Test
    @DisplayName("读窗口：拒答轮不进 prompt（库里有，注入的没有）")
    void refusedTurnIsNotInjected() {
        when(dao.selectRecentTurns(anyString(), anyInt())).thenReturn(List.of(
                row(2, "user", AnswerDecision.REFUSED),
                row(2, "assistant", AnswerDecision.REFUSED),
                row(1, "user", AnswerDecision.PROCEED),
                row(1, "assistant", AnswerDecision.PROCEED)));

        List<ChatTurn> turns = service().loadRecentTurns(envelope(USER), PROPS);

        assertThat(turns).extracting(ChatTurn::turnNo).containsExactly(1);
    }

    @Test
    @DisplayName("读窗口：缺 assistant 的残轮整轮丢弃")
    void incompleteTurnIsDropped() {
        when(dao.selectRecentTurns(anyString(), anyInt())).thenReturn(List.of(
                row(2, "user", AnswerDecision.PROCEED),
                row(1, "user", AnswerDecision.PROCEED),
                row(1, "assistant", AnswerDecision.PROCEED)));

        List<ChatTurn> turns = service().loadRecentTurns(envelope(USER), PROPS);

        assertThat(turns).extracting(ChatTurn::turnNo).containsExactly(1);
    }

    @Test
    @DisplayName("读窗口：user_id 与本轮信封不符的行被丢弃（会话串号/脏数据兜底）")
    void foreignOwnerIsDropped() {
        when(dao.selectRecentTurns(anyString(), anyInt())).thenReturn(List.of(
                foreignRow(2, "user"),
                foreignRow(2, "assistant"),
                row(1, "user", AnswerDecision.PROCEED),
                row(1, "assistant", AnswerDecision.PROCEED)));

        List<ChatTurn> turns = service().loadRecentTurns(envelope(USER), PROPS);

        assertThat(turns).extracting(ChatTurn::turnNo).containsExactly(1);
    }

    @Test
    @DisplayName("读窗口：引文指针被解析回来")
    void citedRefsAreParsed() {
        when(dao.selectRecentTurns(anyString(), anyInt())).thenReturn(List.of(
                row(1, "user", AnswerDecision.PROCEED),
                row(1, "assistant", AnswerDecision.PROCEED)));

        List<ChatTurn> turns = service().loadRecentTurns(envelope(USER), PROPS);

        assertThat(turns.get(0).citedRefs())
                .containsExactly(new CitedChunkRef("D1", "v1", "c1"));
    }

    @Test
    @DisplayName("读窗口：DAO 抛异常向上抛 —— 隔离边界不在这里，是刻意的")
    void daoFailurePropagates() {
        when(dao.selectRecentTurns(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service().loadRecentTurns(envelope(USER), PROPS))
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------
    // 写回
    // ------------------------------------------------------------------

    @Test
    @DisplayName("写回：空会话写 turn_no=1，已有 4 轮则写 5")
    void turnNoIsMaxPlusOne() {
        when(dao.selectMaxTurnNo(SID)).thenReturn(0);
        service().appendTurn(facts(AnswerDecision.PROCEED, null), PROPS);
        assertThat(capturedRows().get(0).turnNo()).isEqualTo(1);

        when(dao.selectMaxTurnNo(SID)).thenReturn(4);
        service().appendTurn(facts(AnswerDecision.PROCEED, null), PROPS);
        assertThat(capturedRows().get(0).turnNo()).isEqualTo(5);
    }

    @Test
    @DisplayName("写回：一轮两行、turn_no 相同、角色分别为 user/assistant")
    void writesTwoRowsSharingTurnNo() {
        when(dao.selectMaxTurnNo(SID)).thenReturn(0);

        service().appendTurn(facts(AnswerDecision.PROCEED, null), PROPS);

        List<ChatMessageRow> rows = capturedRows();
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(ChatMessageRow::turnNo).containsExactly(1, 1);
        assertThat(rows).extracting(ChatMessageRow::role).containsExactly("user", "assistant");
        assertThat(rows.get(0).userMessage()).isEqualTo("问题");
        assertThat(rows.get(0).answerText()).isNull();
        assertThat(rows.get(1).answerText()).isEqualTo("回答");
        assertThat(rows.get(1).userMessage()).isNull();
    }

    @Test
    @DisplayName("写回：轮级字段两行一致（整轮在 SQL 里是可判定的单位）")
    void turnLevelFieldsAreIdenticalOnBothRows() {
        when(dao.selectMaxTurnNo(SID)).thenReturn(0);

        service().appendTurn(facts(AnswerDecision.REFUSED, "NO_HIT"), PROPS);

        List<ChatMessageRow> rows = capturedRows();
        for (ChatMessageRow row : rows) {
            assertThat(row.answerDecision()).isEqualTo(AnswerDecision.REFUSED);
            assertThat(row.refusalReason()).isEqualTo("NO_HIT");
            assertThat(row.bizDate()).isEqualTo(LocalDate.of(2026, 9, 25));
            assertThat(row.permAuthVersion()).isEqualTo(7L);
            assertThat(row.sessionId()).isEqualTo(SID);
            assertThat(row.userId()).isEqualTo(USER);
        }
    }

    @Test
    @DisplayName("写回：两行由一次 insertTurn 提交（单语句 = 一轮原子，不会出现半轮）")
    void oneInsertCallCarriesBothRows() {
        when(dao.selectMaxTurnNo(SID)).thenReturn(0);

        service().appendTurn(facts(AnswerDecision.PROCEED, null), PROPS);

        verify(dao, times(1)).insertTurn(any());
    }

    @Test
    @DisplayName("写回：expires_at = now + retention-days；retention-days=0 时为 null")
    void expiresAtFollowsRetentionDays() {
        when(dao.selectMaxTurnNo(SID)).thenReturn(0);

        service().appendTurn(facts(AnswerDecision.PROCEED, null), PROPS);
        assertThat(capturedRows().get(0).expiresAt()).isAfter(LocalDateTime.now().plusDays(29));

        service().appendTurn(facts(AnswerDecision.PROCEED, null),
                new MemoryProperties(true, 3, 2000, 0));
        assertThat(capturedRows().get(0).expiresAt()).isNull();
    }

    @Test
    @DisplayName("写回：cited_chunk_refs 只存指针字段，不含 chunk 正文")
    void citedRefsJsonCarriesPointersOnly() {
        when(dao.selectMaxTurnNo(SID)).thenReturn(0);

        service().appendTurn(facts(AnswerDecision.PROCEED, null), PROPS);

        String json = capturedRows().get(1).citedChunkRefs();
        assertThat(json).contains("docId", "D1", "docVersion", "v1", "chunkId", "c1");
        // 指针里不可能出现正文 —— 因为 CitedChunkRef 根本没有承载正文的字段，
        // 这条断言是防将来有人往那个 record 上加 text
        assertThat(json).doesNotContain("text", "正文");
    }

    @Test
    @DisplayName("写回：无引文时 JSON 列为 null（而不是空数组字符串）")
    void noRefsWritesNull() {
        when(dao.selectMaxTurnNo(SID)).thenReturn(0);

        service().appendTurn(new TurnFacts(SID, USER, LocalDate.of(2026, 9, 25), 7L,
                "问题", "回答", AnswerDecision.PROCEED, null, List.of()), PROPS);

        assertThat(capturedRows().get(1).citedChunkRefs()).isNull();
    }

    // ------------------------------------------------------------------
    // 测试数据构造
    // ------------------------------------------------------------------

    /** 取**最后一次** insertTurn 收到的行；同一个测试里多次调用 appendTurn 时按最后一次断言 */
    @SuppressWarnings("unchecked")
    private List<ChatMessageRow> capturedRows() {
        ArgumentCaptor<List<ChatMessageRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(dao, org.mockito.Mockito.atLeastOnce()).insertTurn(captor.capture());
        return captor.getValue();
    }

    private static TurnFacts facts(AnswerDecision decision, String reason) {
        return new TurnFacts(SID, USER, LocalDate.of(2026, 9, 25), 7L,
                "问题", "回答", decision, reason, List.of(new CitedChunkRef("D1", "v1", "c1")));
    }

    private static ChatMessageRow row(int turnNo, String role, AnswerDecision decision) {
        return newRow(turnNo, role, decision, USER);
    }

    private static ChatMessageRow foreignRow(int turnNo, String role) {
        return newRow(turnNo, role, AnswerDecision.PROCEED, "99999");
    }

    private static ChatMessageRow newRow(int turnNo, String role, AnswerDecision decision, String userId) {
        boolean isUser = "user".equals(role);
        return new ChatMessageRow(SID, userId, turnNo, role,
                isUser ? "q" + turnNo : null,
                isUser ? null : "a" + turnNo,
                isUser ? null : "[{\"docId\":\"D1\",\"docVersion\":\"v1\",\"chunkId\":\"c1\"}]",
                decision, decision == AnswerDecision.REFUSED ? "NO_HIT" : null,
                LocalDate.of(2026, 9, 25), 7L,
                LocalDateTime.now(), LocalDateTime.now().plusDays(30));
    }

    private static SessionEnvelope envelope(String userId) {
        return new SessionEnvelope(SID, userId, "TELLER", "P3", "0101", "0100/0101",
                2, List.of("kb-teller"), 7L, "MOCK",
                LocalDateTime.now().plusHours(1), "kb_id in ['kb-teller']");
    }
}
