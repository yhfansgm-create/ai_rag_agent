package org.agent.memory.service;

import org.agent.memory.MemoryProperties;
import org.agent.memory.SummaryProperties;
import org.agent.memory.dao.ChatMessageDao;
import org.agent.memory.dao.ChatMessageRow;
import org.agent.memory.dao.SessionSummaryDao;
import org.agent.memory.dao.SessionSummaryRow;
import org.agent.memory.distill.CompactionRequest;
import org.agent.memory.distill.SummaryWriter;
import org.agent.memory.model.AnswerDecision;
import org.agent.memory.model.SessionSummary;
import org.agent.permission.SessionEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Mockito 测试：不打库、不打模型。执行器同线程（{@code Runnable::run}），
 * 于是"异步"在测试里是同步的 —— 走真代码路径，不需要 await。
 *
 * <p>L1 的窗口取 {@code MemoryProperties(true, 3, 2000, 30)} ⇒ {@code promptRowLimit() == 6}，
 * 所以"窗口里有 3 轮"就等于最近 6 行。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SessionSummaryService：摘要的读出口与压缩的提交")
class SessionSummaryServiceTest {

    private static final String SID = "sess-1";
    private static final String USER = "10086";
    private static final MemoryProperties L1_PROPS = new MemoryProperties(true, 3, 2000, 30);
    private static final String SUMMARY_TEXT = "上一轮讨论了《大额存单管理办法》的办理条件";

    @Mock
    private SessionSummaryDao summaryDao;

    @Mock
    private ChatMessageDao chatMessageDao;

    @Mock
    private SummaryWriter compactor;

    @Mock
    private SummaryWriterService summaryWriterService;

    private final ExecutorService sameThread = new AbstractExecutorService() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    };

    private SessionSummaryService service;

    @BeforeEach
    void setUp() {
        service = new SessionSummaryService(summaryDao, chatMessageDao, compactor,
                summaryWriterService, sameThread, SummaryProperties.defaults());
    }

    // ------------------------------------------------------------------
    // 读：整份摘要按本轮实况信封判定
    // ------------------------------------------------------------------

    @Test
    @DisplayName("读摘要：可读的那一份返回，且密级判据是本轮信封（不是行里的快照）")
    void readableSummaryIsReturned() {
        when(summaryDao.selectActive(anyString(), any())).thenReturn(row("s1", 1, 4, 2));

        SessionSummary summary = service.loadSummary(envelope(USER, 2));

        assertThat(summary).isNotNull();
        assertThat(summary.summaryText()).isEqualTo(SUMMARY_TEXT);
        assertThat(summary.covers(2)).isTrue();
        assertThat(summary.covers(4)).isFalse();
    }

    @Test
    @DisplayName("读摘要：★ 密级高于本轮信封 → 整份丢弃（不是截断、不是部分保留）")
    void summaryAboveEnvelopeIsDroppedWhole() {
        when(summaryDao.selectActive(anyString(), any())).thenReturn(row("s1", 1, 4, 2));

        // 摘要把高密级内容压缩后长期携带 —— 不继承密级的话它是一条比 L1 更宽的路
        assertThat(service.loadSummary(envelope(USER, 2))).isNotNull();
        assertThat(service.loadSummary(envelope(USER, 1))).isNull();
    }

    @Test
    @DisplayName("读摘要：归属不符的丢弃（会话串号兜底）")
    void foreignOwnerIsDropped() {
        when(summaryDao.selectActive(anyString(), any())).thenReturn(row("s1", 1, 2, 2));

        assertThat(service.loadSummary(envelope("99999", 4))).isNull();
    }

    @Test
    @DisplayName("读摘要：已取代 / 已过期 / 退化区间 / 超预算 一律丢弃")
    void staleDegenerateAndOversizedSummariesAreDropped() {
        when(summaryDao.selectActive(anyString(), any()))
                .thenReturn(supersededRow("s1", 1, 2));
        assertThat(service.loadSummary(envelope(USER, 2))).isNull();

        // 区间为空 ⇒ 它没有任何定位能力，而它"存在"会让压缩以为已经覆盖过、永久停摆
        when(summaryDao.selectActive(anyString(), any())).thenReturn(row("s2", 2, 2, 2));
        assertThat(service.loadSummary(envelope(USER, 2))).isNull();

        when(summaryDao.selectActive(anyString(), any()))
                .thenReturn(row("s3", 1, 2, 2, LocalDateTime.now().minusSeconds(1)));
        assertThat(service.loadSummary(envelope(USER, 2))).isNull();

        when(summaryDao.selectActive(anyString(), any()))
                .thenReturn(row("s4", 1, 2, 2, null, "长".repeat(600 + 1)));
        assertThat(service.loadSummary(envelope(USER, 2))).isNull();
    }

    @Test
    @DisplayName("读摘要：关掉摘要开关时不碰数据库（L1 仍是硬滑窗 —— 第 3 轮以前整段消失可复现）")
    void disabledDoesNothing() {
        SessionSummaryService off = new SessionSummaryService(summaryDao, chatMessageDao,
                compactor, summaryWriterService, sameThread, SummaryProperties.disabled());

        assertThat(off.loadSummary(envelope(USER, 4))).isNull();
        off.scheduleCompaction(envelope(USER, 4), L1_PROPS);

        verifyNoInteractions(summaryDao);
        verifyNoInteractions(compactor);
    }

    // ------------------------------------------------------------------
    // 压缩：主触发（有轮次滑出窗口）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("压缩：窗口 [4,6]、覆盖为空 ⇒ 折叠 [1,3]，密级取被折叠轮次的 max")
    void slidOutTurnsAreCompacted() {
        when(chatMessageDao.selectRecentTurns(SID, 6)).thenReturn(turns(4, 6, 2));
        when(chatMessageDao.selectTurnRange(SID, 1, 3)).thenReturn(turnRows(1, 3, 2));
        when(compactor.summarize(any())).thenReturn(SUMMARY_TEXT);
        when(summaryWriterService.write(any(), any())).thenAnswer(call -> call.getArgument(1));

        service.scheduleCompaction(envelope(USER, 4), L1_PROPS);

        SessionSummaryRow written = capturedWrite();
        assertThat(written.coversFromTurnNo()).isEqualTo(1);
        // 开区间上界 = "下一轮从哪开始"，于是它恰好接上 L1 窗口的起点 4
        assertThat(written.coversThroughTurnNo()).isEqualTo(4);
        assertThat(written.clearanceLevel()).isEqualTo(2);
        assertThat(written.sourceTurnCount()).isEqualTo(3);
        assertThat(written.sessionId()).isEqualTo(SID);
        assertThat(written.userId()).isEqualTo(USER);
        assertThat(written.permAuthVersion()).isEqualTo(7L);
        // 留存期由 Java 按配置算，模型无权输出
        assertThat(written.expiresAt()).isAfter(LocalDateTime.now().plusDays(29));
        // 第一版摘要：没有被取代的对象
        verify(summaryWriterService).write(null, written);
    }

    @Test
    @DisplayName("压缩：上一版摘要的文本被折进请求，且区间从上一版的 through 接着算")
    void compactionIsIncremental() {
        when(summaryDao.selectActive(anyString(), any())).thenReturn(row("s1", 1, 3, 2));
        when(chatMessageDao.selectRecentTurns(SID, 6)).thenReturn(turns(5, 7, 2));
        when(chatMessageDao.selectTurnRange(SID, 3, 4)).thenReturn(turnRows(3, 4, 2));
        when(compactor.summarize(any())).thenReturn(SUMMARY_TEXT);

        service.scheduleCompaction(envelope(USER, 4), L1_PROPS);

        ArgumentCaptor<CompactionRequest> request = ArgumentCaptor.forClass(CompactionRequest.class);
        verify(compactor).summarize(request.capture());
        // 不传上一版的话，第二次压缩要么只看新滑出的几轮（丢掉更早的定位能力），
        // 要么把已摘要过的轮次重读一遍（输入翻倍，而摘要的有损性会让重复压缩越压越糊）
        assertThat(request.getValue().previousSummary()).isEqualTo(SUMMARY_TEXT);
        assertThat(capturedWrite().coversFromTurnNo()).isEqualTo(3);
        assertThat(capturedWrite().coversThroughTurnNo()).isEqualTo(5);
    }

    @Test
    @DisplayName("压缩：★ 密级取 max(被折叠轮次, 上一版摘要) —— 否则高密级内容会降级搭车")
    void clearanceTakesMaxWithPreviousVersion() {
        when(summaryDao.selectActive(anyString(), any())).thenReturn(row("s1", 1, 3, 4));
        when(chatMessageDao.selectRecentTurns(SID, 6)).thenReturn(turns(5, 7, 1));
        when(chatMessageDao.selectTurnRange(SID, 3, 4)).thenReturn(turnRows(3, 4, 1));
        when(compactor.summarize(any())).thenReturn(SUMMARY_TEXT);

        service.scheduleCompaction(envelope(USER, 4), L1_PROPS);

        // 上一版的文本被折进了新版，若只取本次轮次的 max（=1），
        // 一份密级 4 的旧摘要就通过"再压缩一次"洗成了密级 1
        assertThat(capturedWrite().clearanceLevel()).isEqualTo(4);
    }

    @Test
    @DisplayName("压缩：没有新轮次滑出窗口时不调模型（绝大多数轮次的正常路径）")
    void nothingSlidOutMeansNoModelCall() {
        when(chatMessageDao.selectRecentTurns(SID, 6)).thenReturn(turns(1, 3, 2));

        service.scheduleCompaction(envelope(USER, 4), L1_PROPS);

        // 窗口起点 1 = 覆盖起点 ⇒ 没有滑出的轮次。这条路径必须廉价：
        // 它每轮都会走一次，若在这里调模型，成本与延迟都会失控
        verifyNoInteractions(compactor);
        verify(summaryWriterService, never()).write(any(), any());
    }

    @Test
    @DisplayName("压缩：已覆盖的轮次不重复压缩（上一版的 through 就是本次的起点）")
    void alreadyCoveredTurnsAreNotRecompacted() {
        when(summaryDao.selectActive(anyString(), any())).thenReturn(row("s1", 1, 6, 2));
        when(chatMessageDao.selectRecentTurns(SID, 6)).thenReturn(turns(4, 6, 2));

        service.scheduleCompaction(envelope(USER, 4), L1_PROPS);

        verifyNoInteractions(compactor);
    }

    // ------------------------------------------------------------------
    // 压缩：不合格就整份丢弃（A1 那条已知偏差的机械化落实）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("压缩：★ 压缩器返回 null（模型失败或两次都违规）⇒ 不落库，也不取代旧版")
    void nullSummaryIsNotWritten() {
        when(summaryDao.selectActive(anyString(), any())).thenReturn(row("s1", 1, 3, 2));
        when(chatMessageDao.selectRecentTurns(SID, 6)).thenReturn(turns(5, 7, 2));
        when(chatMessageDao.selectTurnRange(SID, 3, 4)).thenReturn(turnRows(3, 4, 2));
        when(compactor.summarize(any())).thenReturn(null);

        service.scheduleCompaction(envelope(USER, 4), L1_PROPS);

        // 落一份差的会取代掉上一版好的 —— 于是"压缩失败"这个瞬时问题
        // 会升格成"上一版的定位能力也没了"这个持久问题
        verify(summaryWriterService, never()).write(any(), any());
    }

    @Test
    @DisplayName("压缩：★ 空白文本同样不落库（null 是一个有意义的结果，空串不是退路）")
    void blankSummaryIsNotWritten() {
        when(chatMessageDao.selectRecentTurns(SID, 6)).thenReturn(turns(4, 6, 2));
        when(chatMessageDao.selectTurnRange(SID, 1, 3)).thenReturn(turnRows(1, 3, 2));
        when(compactor.summarize(any())).thenReturn("   ");

        service.scheduleCompaction(envelope(USER, 4), L1_PROPS);

        verify(summaryWriterService, never()).write(any(), any());
    }

    @Test
    @DisplayName("压缩：超预算整份丢弃，绝不截断（被腰斩的定位信息会被当成真的用）")
    void oversizedSummaryIsNotWritten() {
        when(chatMessageDao.selectRecentTurns(SID, 6)).thenReturn(turns(4, 6, 2));
        when(chatMessageDao.selectTurnRange(SID, 1, 3)).thenReturn(turnRows(1, 3, 2));
        when(compactor.summarize(any())).thenReturn("长".repeat(600 + 1));

        service.scheduleCompaction(envelope(USER, 4), L1_PROPS);

        verify(summaryWriterService, never()).write(any(), any());
    }

    @Test
    @DisplayName("★ 压缩：产出含事实性数字 ⇒ 整份丢弃（A1 校正的落库前终审，打桩压缩器也拦得住）")
    void summaryContainingNumbersIsNeverPersisted() {
        when(chatMessageDao.selectRecentTurns(SID, 6)).thenReturn(turns(4, 6, 2));
        when(chatMessageDao.selectTurnRange(SID, 1, 3)).thenReturn(turnRows(1, 3, 2));
        // 模型没听话：提示词里写了"禁止记录金额/比例/期限"，它还是记了个金额
        when(compactor.summarize(any())).thenReturn("上一轮讨论了大额存单起存金额 20 万元");

        service.scheduleCompaction(envelope(USER, 4), L1_PROPS);

        // ⚠️ 先证明**真的走到了那道闸门** —— 上面那几个 stub 一旦对不上，
        //    scheduleCompaction 会在更早的地方早返回，而下面的 never() 照样通过。
        //    这个坑本项目已经踩过两次（"通过得没有意义"比失败更难发现）
        verify(compactor).summarize(any());

        // ⚠️ 这条断言之所以能成立，是因为检查放在**本类**而不是只放在 LlmSummaryWriter 里：
        //    压缩器在这里是被打桩掉的，若闸门在 seam 实现内部，这条测试就什么也证明不了 ——
        //    它只会证明"我打桩的那个东西没有检查"。放在落库这一侧之后，
        //    "摘要不许夹带事实性数字"才是**这一层的契约**，而不是某个实现的属性
        verify(summaryWriterService, never()).write(any(), any());
    }

    @Test
    @DisplayName("压缩：压缩器抛异常不外抛（任务体不在 advisor 的栈上）")
    void compactorFailureDoesNotPropagate() {
        when(chatMessageDao.selectRecentTurns(SID, 6)).thenReturn(turns(4, 6, 2));
        when(chatMessageDao.selectTurnRange(SID, 1, 3)).thenReturn(turnRows(1, 3, 2));
        when(compactor.summarize(any())).thenThrow(new IllegalStateException("模型炸了"));

        assertThatCode(() -> service.scheduleCompaction(envelope(USER, 4), L1_PROPS))
                .doesNotThrowAnyException();
        verify(summaryWriterService, never()).write(any(), any());
    }

    // ------------------------------------------------------------------
    // 压缩：输入怎么构造
    // ------------------------------------------------------------------

    @Test
    @DisplayName("压缩输入：★ 拒答轮只贡献「未获答复」标记，答案文本不进去（不给它答案，也不强化拒答倾向）")
    void refusedTurnContributesOnlyAPlaceholder() {
        when(chatMessageDao.selectRecentTurns(SID, 6)).thenReturn(turns(4, 6, 2));
        when(chatMessageDao.selectTurnRange(SID, 1, 3)).thenReturn(List.of(
                newRow(1, "user", AnswerDecision.PROCEED, 2),
                newRow(1, "assistant", AnswerDecision.PROCEED, 2),
                newRow(2, "user", AnswerDecision.REFUSED, 2),
                newRow(2, "assistant", AnswerDecision.REFUSED, 2),
                newRow(3, "user", AnswerDecision.PROCEED, 2)));
        when(compactor.summarize(any())).thenReturn(SUMMARY_TEXT);

        service.scheduleCompaction(envelope(USER, 4), L1_PROPS);

        ArgumentCaptor<CompactionRequest> request = ArgumentCaptor.forClass(CompactionRequest.class);
        verify(compactor).summarize(request.capture());
        List<CompactionRequest.SourceTurn> turns = request.getValue().sourceTurns();
        assertThat(turns).extracting(CompactionRequest.SourceTurn::turnNo).containsExactly(1, 2, 3);

        CompactionRequest.SourceTurn refused = turns.get(1);
        assertThat(refused.answered()).isFalse();
        assertThat(refused.answerText()).isNull();
        assertThat(refused.renderForPrompt()).contains("未获答复");
        // "用户问了 XX"要留下 —— 下一轮用户说"那它呢"时这是唯一的定位线索
        assertThat(refused.renderForPrompt()).contains("q2");
    }

    @Test
    @DisplayName("压缩输入：残轮（只有 user 行）不进压缩输入")
    void leftoverHalfTurnIsSkipped() {
        when(chatMessageDao.selectRecentTurns(SID, 6)).thenReturn(turns(4, 6, 2));
        when(chatMessageDao.selectTurnRange(SID, 1, 3)).thenReturn(List.of(
                newRow(1, "user", AnswerDecision.PROCEED, 2),
                newRow(2, "assistant", AnswerDecision.PROCEED, 2)));
        when(compactor.summarize(any())).thenReturn(SUMMARY_TEXT);

        service.scheduleCompaction(envelope(USER, 4), L1_PROPS);

        ArgumentCaptor<CompactionRequest> request = ArgumentCaptor.forClass(CompactionRequest.class);
        verify(compactor).summarize(request.capture());
        assertThat(request.getValue().sourceTurns())
                .extracting(CompactionRequest.SourceTurn::turnNo).containsExactly(1);
    }

    @Test
    @DisplayName("压缩输入：区间内有洞 ⇒ 覆盖区间按**实读到的**轮次算（宁可窄，不可假）")
    void coverageFollowsActuallyReadTurns() {
        when(chatMessageDao.selectRecentTurns(SID, 6)).thenReturn(turns(4, 6, 2));
        // 第 2 轮整轮缺失（行被清理 / 轮号有洞）
        when(chatMessageDao.selectTurnRange(SID, 1, 3)).thenReturn(List.of(
                newRow(1, "user", AnswerDecision.PROCEED, 2),
                newRow(1, "assistant", AnswerDecision.PROCEED, 2),
                newRow(3, "user", AnswerDecision.PROCEED, 2),
                newRow(3, "assistant", AnswerDecision.PROCEED, 2)));
        when(compactor.summarize(any())).thenReturn(SUMMARY_TEXT);

        service.scheduleCompaction(envelope(USER, 4), L1_PROPS);

        // 按公式写就是"声称覆盖了第 2 轮"，而那一轮从没被读到 ——
        // 而"每一轮要么在窗口里、要么在摘要里"这条完备性正是靠覆盖区间成立的
        assertThat(capturedWrite().coversFromTurnNo()).isEqualTo(1);
        assertThat(capturedWrite().coversThroughTurnNo()).isEqualTo(4);
    }

    @Test
    @DisplayName("压缩输入：★ 行序被打乱时仍按轮号升序压缩（覆盖区间不建立在「SQL 记得排序」之上）")
    void turnOrderIsNormalisedRegardlessOfRowOrder() {
        when(chatMessageDao.selectRecentTurns(SID, 6)).thenReturn(turns(4, 6, 2));
        // 故意给一个倒序的结果 —— 真实 SQL 是 ASC，但把安全相关的区间建立在
        // "我记得那个 @Select 里写了 ORDER BY" 之上，是一处迟早会坏的耦合
        when(chatMessageDao.selectTurnRange(SID, 1, 3)).thenReturn(turns(1, 3, 2));
        when(compactor.summarize(any())).thenReturn(SUMMARY_TEXT);

        service.scheduleCompaction(envelope(USER, 4), L1_PROPS);

        ArgumentCaptor<CompactionRequest> request = ArgumentCaptor.forClass(CompactionRequest.class);
        verify(compactor).summarize(request.capture());
        assertThat(request.getValue().sourceTurns())
                .extracting(CompactionRequest.SourceTurn::turnNo).containsExactly(1, 2, 3);
        assertThat(capturedWrite().coversFromTurnNo()).isEqualTo(1);
        assertThat(capturedWrite().coversThroughTurnNo()).isEqualTo(4);
    }

    @Test
    @DisplayName("压缩输入：超长文本截断并带可见标记（不让模型去补完半句话）")
    void longTextIsTruncatedWithAMarker() {
        when(chatMessageDao.selectRecentTurns(SID, 6)).thenReturn(turns(4, 6, 2));
        when(chatMessageDao.selectTurnRange(SID, 1, 3)).thenReturn(turnRows(1, 3, 2));
        when(compactor.summarize(any())).thenReturn(SUMMARY_TEXT);
        // 上限配成 1 只是为了让断言一眼看得清"截断了、且带了标记"
        SessionSummaryService tiny = new SessionSummaryService(summaryDao, chatMessageDao,
                compactor, summaryWriterService, sameThread,
                new SummaryProperties(true, 600, 8000, 0.7, 1, 30));

        tiny.scheduleCompaction(envelope(USER, 4), L1_PROPS);

        ArgumentCaptor<CompactionRequest> request = ArgumentCaptor.forClass(CompactionRequest.class);
        verify(compactor).summarize(request.capture());
        assertThat(request.getValue().sourceTurns().get(0).userMessage()).isEqualTo("q（后略）");
        assertThat(request.getValue().sourceTurns().get(0).answerText()).isEqualTo("a（后略）");
    }

    @Test
    @DisplayName("压缩：本会话当前摘要归属他人 ⇒ 放弃整次压缩（不改写别人的行，也不编造区间）")
    void foreignPreviousSummaryAbortsCompaction() {
        when(summaryDao.selectActive(anyString(), any())).thenReturn(row("s1", 1, 2, 3, "99999"));

        service.scheduleCompaction(envelope(USER, 4), L1_PROPS);

        verifyNoInteractions(chatMessageDao);
        verifyNoInteractions(compactor);
    }

    @Test
    @DisplayName("压缩：窗口内一行都没有时什么都不做（空会话 / 早于 L1 写入）")
    void emptyWindowDoesNothing() {
        when(chatMessageDao.selectRecentTurns(SID, 6)).thenReturn(List.of());

        service.scheduleCompaction(envelope(USER, 4), L1_PROPS);

        verifyNoInteractions(compactor);
    }

    // ------------------------------------------------------------------
    // 测试数据构造
    // ------------------------------------------------------------------

    /** 取最后一次 write 的第二个参数（新版摘要） */
    private SessionSummaryRow capturedWrite() {
        ArgumentCaptor<SessionSummaryRow> incoming = ArgumentCaptor.forClass(SessionSummaryRow.class);
        verify(summaryWriterService).write(any(), incoming.capture());
        return incoming.getValue();
    }

    /** 最近 N 行：一轮两行，轮号从 {@code from} 到 {@code through} 倒序（照 SQL 的返回顺序） */
    private static List<ChatMessageRow> turns(int from, int through, int clearance) {
        List<ChatMessageRow> rows = new ArrayList<>();
        for (int turnNo = through; turnNo >= from; turnNo--) {
            rows.add(newRow(turnNo, "user", AnswerDecision.PROCEED, clearance));
            rows.add(newRow(turnNo, "assistant", AnswerDecision.PROCEED, clearance));
        }
        return rows;
    }

    /** 轮号区间内的行，轮号升序（照 selectTurnRange 的返回顺序） */
    private static List<ChatMessageRow> turnRows(int from, int through, int clearance) {
        List<ChatMessageRow> rows = new ArrayList<>();
        for (int turnNo = from; turnNo <= through; turnNo++) {
            rows.add(newRow(turnNo, "user", AnswerDecision.PROCEED, clearance));
            rows.add(newRow(turnNo, "assistant", AnswerDecision.PROCEED, clearance));
        }
        return rows;
    }

    private static ChatMessageRow newRow(int turnNo, String role, AnswerDecision decision,
                                        int clearanceLevel) {
        boolean isUser = "user".equals(role);
        return new ChatMessageRow(SID, USER, turnNo, role,
                isUser ? "q" + turnNo : null,
                isUser ? null : "a" + turnNo,
                null, decision, decision == AnswerDecision.REFUSED ? "NO_HIT" : null,
                LocalDate.of(2026, 9, 25), clearanceLevel, 7L,
                LocalDateTime.now(), LocalDateTime.now().plusDays(30));
    }

    /**
     * 当前有效的一版摘要：{@code coversFrom} / {@code coversThrough} 是<b>左闭右开</b>区间
     * （与 {@code SessionSummary.covers} 同一语义），{@code clearance} 是密级快照。
     * <p>
     * ⚠️ 形参顺序与 record 一致，所以调用点读起来是"从哪轮、到下轮、密级多少" ——
     * 写反了会让本该压缩的用例悄悄走到"没有轮次滑出窗口"这条早退分支，
     * 而它的断言（{@code verifyNoInteractions}）照样通过。<b>所以每个用例都断言了
     * 落库那行的覆盖区间</b>，让写反这件事在断言上撞出来。
     */
    private static SessionSummaryRow row(String uid, int coversFrom, int coversThrough,
                                         int clearance) {
        return row(uid, coversFrom, coversThrough, clearance, USER);
    }

    private static SessionSummaryRow row(String uid, int coversFrom, int coversThrough,
                                         int clearance, String userId) {
        return new SessionSummaryRow(uid, SID, userId, coversFrom, coversThrough, clearance,
                SUMMARY_TEXT, Math.max(coversThrough - coversFrom, 0),
                LocalDateTime.now(), null, null,
                7L, LocalDateTime.now().plusDays(30), LocalDateTime.now());
    }

    private static SessionSummaryRow row(String uid, int coversFrom, int coversThrough,
                                         int clearance, LocalDateTime expiresAt) {
        return new SessionSummaryRow(uid, SID, USER, coversFrom, coversThrough, clearance,
                SUMMARY_TEXT, 1, LocalDateTime.now(), null, null,
                7L, expiresAt, LocalDateTime.now());
    }

    private static SessionSummaryRow row(String uid, int coversFrom, int coversThrough,
                                         int clearance, LocalDateTime expiresAt, String text) {
        return new SessionSummaryRow(uid, SID, USER, coversFrom, coversThrough, clearance,
                text, 1, LocalDateTime.now(), null, null,
                7L, expiresAt, LocalDateTime.now());
    }

    private static SessionSummaryRow supersededRow(String uid, int from, int through) {
        return new SessionSummaryRow(uid, SID, USER, from, through, 2,
                SUMMARY_TEXT, 1, LocalDateTime.now(), LocalDateTime.now(), "next-uid",
                7L, LocalDateTime.now().plusDays(30), LocalDateTime.now());
    }

    private static SessionEnvelope envelope(String userId, int clearance) {
        return new SessionEnvelope(SID, userId, "TELLER", "P3", "0101", "0100/0101",
                clearance, List.of("kb-teller"), 7L, "MOCK",
                LocalDateTime.now().plusHours(1), "kb_id in ['kb-teller']");
    }
}
