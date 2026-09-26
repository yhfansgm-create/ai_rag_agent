package org.agent.memory.service;

import org.agent.memory.SemanticMemoryProperties;
import org.agent.memory.dao.UserMemoryDao;
import org.agent.memory.dao.UserMemoryRow;
import org.agent.memory.distill.DistillRequest;
import org.agent.memory.distill.FactDistiller;
import org.agent.memory.model.AnswerDecision;
import org.agent.memory.model.CitedChunkRef;
import org.agent.memory.model.FactDraft;
import org.agent.memory.model.FactKey;
import org.agent.memory.model.FactScope;
import org.agent.memory.model.MemoryStatus;
import org.agent.memory.model.RecalledFact;
import org.agent.memory.model.TurnFacts;
import org.agent.permission.SessionEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Mockito 测试：不打库、不打模型。
 *
 * <p>执行器用<b>同线程</b>的（{@code Runnable::run}），于是"异步"这件事在测试里变成同步 ——
 * 既走真代码路径（提交 → 任务体 → 写入），又不需要 await、不产生任何额外模型调用。
 * 这是本阶段所有链路测试共用的手法（见 {@code L1ChainScaffold}）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SemanticMemoryService：L2 的读出口与写的提交")
class SemanticMemoryServiceTest {

    private static final String SID = "sess-1";
    private static final String USER = "10086";

    /**
     * 默认的"上次命中时间"。<b>固定值而不是 {@code now()}</b>：同一批行时间相同时
     * 排序是稳定的（{@code List.sort} 保证），于是一批同源的行顺序可预测 ——
     * 用 {@code now()} 的话每行差几微秒，"谁在前"就变成了一个碰运气的断言。
     */
    private static final LocalDateTime BASE_HIT = LocalDateTime.of(2026, 9, 20, 10, 0);

    @Mock
    private UserMemoryDao dao;

    @Mock
    private FactDistiller distiller;

    @Mock
    private MemoryFactWriter factWriter;

    /** 同线程执行器：submit 即在调用线程上跑完 */
    private final ExecutorService sameThread = new java.util.concurrent.AbstractExecutorService() {
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
        public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
            return true;
        }
    };

    private SemanticMemoryProperties props;
    private SemanticMemoryService service;

    @BeforeEach
    void setUp() {
        props = SemanticMemoryProperties.defaults();
        service = new SemanticMemoryService(dao, distiller, factWriter, sameThread, props);
    }

    // ------------------------------------------------------------------
    // 读：策略复核
    // ------------------------------------------------------------------

    @Test
    @DisplayName("召回：可读的行返回，且只为这些行记一次命中")
    void recallReturnsReadableAndTouchesThem() {
        when(dao.selectUserFacts(anyString(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(fact("f1", FactKey.BUSINESS_LINE, "对公", 2),
                        fact("f2", FactKey.PREFERRED_ORG, "0100", 2)));

        List<RecalledFact> facts = service.recall(envelope(2));

        assertThat(facts).extracting(RecalledFact::factValue).containsExactlyInAnyOrder("对公", "0100");
        verify(dao).touchHit(eq(List.of("f1", "f2")), any());
    }

    @Test
    @DisplayName("召回：★ 密级高于本轮信封的行被 Java 侧再滤掉，且**不算命中**")
    void rowAboveEnvelopeIsDroppedAndNotTouched() {
        when(dao.selectUserFacts(anyString(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(fact("f1", FactKey.BUSINESS_LINE, "对公", 4),
                        fact("f2", FactKey.PREFERRED_ORG, "0100", 1)));

        List<RecalledFact> facts = service.recall(envelope(2));

        // 判据是**本轮信封**，不是行里那个快照列。若写成按行里的密级判，等于恒真、等于不过滤
        assertThat(facts).extracting(RecalledFact::factUid).containsExactly("f2");
        // miss 就是 miss：看不见的事实不能被记成"最近常看"，否则污染阶段 4 巡检③的 LRU 判断
        verify(dao).touchHit(eq(List.of("f2")), any());
    }

    @Test
    @DisplayName("召回：★ 同一条行在信封密级 4 下能读到（依据是本轮实况，不是行自己的快照）")
    void sameRowIsReadableUnderHigherEnvelope() {
        when(dao.selectUserFacts(anyString(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(fact("f1", FactKey.BUSINESS_LINE, "对公", 4)));

        assertThat(service.recall(envelope(4))).hasSize(1);
        // 与上一个用例是同一条行、不同的信封 —— 这两条断言合起来把"依据是信封"钉死
        verify(dao).touchHit(eq(List.of("f1")), any());
    }

    @Test
    @DisplayName("召回：非 ACTIVE / 已取代（valid_to 非空）/ 已过期的行一律丢弃")
    void staleRowsAreDropped() {
        UserMemoryRow superseded = withValidTo(fact("f1", FactKey.BUSINESS_LINE, "对公", 2));
        UserMemoryRow deleted = withStatus(fact("f2", FactKey.BUSINESS_LINE, "对私", 2),
                MemoryStatus.USER_DELETED);
        UserMemoryRow expired = withExpiresAt(fact("f3", FactKey.BUSINESS_LINE, "零售", 2),
                LocalDateTime.now().minusSeconds(1));
        when(dao.selectUserFacts(anyString(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(superseded, deleted, expired));

        assertThat(service.recall(envelope(2))).isEmpty();
        // 一条都没返回 ⇒ 一次命中都不该记（touchHit 的空集合会拼出 IN () 语法错误）
        verify(dao, never()).touchHit(anyList(), any());
    }

    @Test
    @DisplayName("召回：密级 0（非法值）判为不可读 —— 与列 DEFAULT 5 同一套 fail-closed")
    void illegalZeroLevelIsNotReadable() {
        when(dao.selectUserFacts(anyString(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(fact("f1", FactKey.BUSINESS_LINE, "对公", 0)));

        assertThat(service.recall(envelope(2))).isEmpty();
    }

    @Test
    @DisplayName("召回：词表解析不出的键丢弃并留 WARN（不静默当空值渲染）")
    void unknownKeyIsDropped() {
        UserMemoryRow unknown = new UserMemoryRow("f1", USER, FactScope.USER, "no_such_key",
                "值", 2, null, SID, 1, null, now(), null, null, null,
                MemoryStatus.ACTIVE, null, 7L, now());
        when(dao.selectUserFacts(anyString(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(unknown));

        assertThat(service.recall(envelope(2))).isEmpty();
    }

    @Test
    @DisplayName("召回：GLOBAL scope 恒拒（声明但读写皆拒，fail-closed）")
    void globalScopeIsAlwaysRejected() {
        UserMemoryRow global = new UserMemoryRow("f1", USER, FactScope.GLOBAL,
                FactKey.BUSINESS_LINE.code(), "对公", 2, null, SID, 1, null,
                now(), null, null, null, MemoryStatus.ACTIVE, null, 7L, now());
        when(dao.selectUserFacts(anyString(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(global));

        assertThat(service.recall(envelope(2))).isEmpty();
    }

    @Test
    @DisplayName("召回：归属不符的行丢弃（USER 比 owner_id）")
    void foreignOwnerIsDropped() {
        UserMemoryRow foreign = new UserMemoryRow("f1", "99999", FactScope.USER,
                FactKey.BUSINESS_LINE.code(), "对公", 2, null, SID, 1, null,
                now(), null, null, null, MemoryStatus.ACTIVE, null, 7L, now());
        when(dao.selectUserFacts(anyString(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(foreign));

        assertThat(service.recall(envelope(2))).isEmpty();
    }

    @Test
    @DisplayName("召回：DEPT 行按 org_path 精确匹配读入，并与 USER 行一起按命中时间倒序")
    void deptFactsAreMergedAndReSorted() {
        // 两段查询各自有序，但合起来不一定有序 —— 不重排的话 DEPT 那一整段永远在尾部，
        // 于是预算裁剪时它总是先被丢掉，而那与"它更不重要"无关，只是拼接顺序的副作用
        when(dao.selectUserFacts(anyString(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(factAt("f1", FactKey.BUSINESS_LINE, "对公",
                        LocalDateTime.now().minusDays(5))));
        when(dao.selectDeptFacts(anyString(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(factAt("d1", FactKey.PREFERRED_ORG, "0100",
                        LocalDateTime.now().minusDays(1))));

        assertThat(service.recall(envelope(2)))
                .extracting(RecalledFact::factUid).containsExactly("d1", "f1");
    }

    @Test
    @DisplayName("召回：信封没有 orgPath 时不发起 DEPT 查询（没有组织就没有部门级可见范围）")
    void noOrgPathMeansNoDeptQuery() {
        when(dao.selectUserFacts(anyString(), anyInt(), any(), anyInt()))
                .thenReturn(List.of());

        service.recall(envelopeWithoutOrgPath());

        verify(dao, never()).selectDeptFacts(anyString(), anyInt(), any(), anyInt());
    }

    @Test
    @DisplayName("召回：返回条数不超过 recall-limit（两段查询各带上限，合起来还要再收）")
    void recallLimitIsEnforcedAcrossBothQueries() {
        SemanticMemoryService limited = service(new SemanticMemoryProperties(true, true, 200, 1, 10, 80, 800, 2, 200));
        when(dao.selectUserFacts(anyString(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(fact("f1", FactKey.BUSINESS_LINE, "对公", 2)));
        when(dao.selectDeptFacts(anyString(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(fact("d1", FactKey.PREFERRED_ORG, "0100", 2)));

        assertThat(limited.recall(envelope(2))).hasSize(1);
    }

    @Test
    @DisplayName("召回：关掉 L2 时不碰数据库（与 L1 的开关互相独立）")
    void disabledDoesNothing() {
        SemanticMemoryService off = service(SemanticMemoryProperties.disabled());

        assertThat(off.recall(envelope(2))).isEmpty();

        verifyNoInteractions(dao);
    }

    @Test
    @DisplayName("召回：信封为 null 时返回空，不抛")
    void nullEnvelopeIsEmpty() {
        assertThat(service.recall(null)).isEmpty();
        verifyNoInteractions(dao);
    }

    // ------------------------------------------------------------------
    // 写：提交蒸馏
    // ------------------------------------------------------------------

    @Test
    @DisplayName("提交蒸馏：无来源轮号（turnNo<=0）不提交 —— 无源不写靠执行顺序保证")
    void noTurnNoMeansNoDistill() {
        service.scheduleDistill(facts(AnswerDecision.PROCEED), 0, envelope(2));

        verifyNoInteractions(distiller);
        verifyNoInteractions(factWriter);
    }

    @Test
    @DisplayName("提交蒸馏：归属、来源、引文指针全部来自信封与该轮，且**不含答案文本**")
    void distillContextComesFromEnvelopeAndTurn() {
        when(distiller.distill(any())).thenReturn(List.of(draft(FactKey.BUSINESS_LINE, "对公")));

        service.scheduleDistill(facts(AnswerDecision.PROCEED), 3, envelope(2));

        ArgumentCaptor<DistillRequest> request = ArgumentCaptor.forClass(DistillRequest.class);
        verify(distiller).distill(request.capture());
        assertThat(request.getValue().userMessage()).isEqualTo("问题");
        assertThat(request.getValue().bizDate()).isEqualTo(LocalDate.of(2026, 9, 25));

        ArgumentCaptor<MemoryFactWriter.WriteContext> ctx =
                ArgumentCaptor.forClass(MemoryFactWriter.WriteContext.class);
        verify(factWriter).writeCandidate(ctx.capture(), any());
        assertThat(ctx.getValue().ownerId()).isEqualTo(USER);
        assertThat(ctx.getValue().turnNo()).isEqualTo(3);
        assertThat(ctx.getValue().clearanceLevel()).isEqualTo(2);
        assertThat(ctx.getValue().refDocId()).isEqualTo("D1");
        assertThat(ctx.getValue().userMessage()).isEqualTo("问题");
    }

    @Test
    @DisplayName("提交蒸馏：读「已知事实」不记命中（那是喂给抽取器的输入，没进任何 prompt）")
    void knownFactsReadDoesNotTouchHits() {
        when(dao.selectUserFacts(anyString(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(fact("f1", FactKey.BUSINESS_LINE, "对公", 2)));
        when(distiller.distill(any())).thenReturn(List.of());

        service.scheduleDistill(facts(AnswerDecision.PROCEED), 3, envelope(2));

        verify(dao, never()).touchHit(anyList(), any());
        // 但"已知事实"照样要过同一条权限出口 —— 否则高密级事实会通过蒸馏这条侧路进模型上下文
        verify(dao).selectUserFacts(anyString(), anyInt(), any(), anyInt());
    }

    @Test
    @DisplayName("提交蒸馏：★ 拒答轮也蒸馏（用户原话与检索成没成功无关）")
    void refusedTurnIsStillDistilled() {
        when(distiller.distill(any())).thenReturn(List.of(draft(FactKey.BUSINESS_LINE, "对公")));

        service.scheduleDistill(facts(AnswerDecision.REFUSED), 3, envelope(2));

        verify(distiller).distill(any());
        verify(factWriter).writeCandidate(any(), any());
    }

    @Test
    @DisplayName("提交蒸馏：蒸馏器抛异常不外抛（任务体不在 advisor 的栈上，外抛会被线程池吞掉）")
    void distillFailureDoesNotPropagate() {
        when(distiller.distill(any())).thenThrow(new IllegalStateException("模型炸了"));

        assertThatCode(() -> service.scheduleDistill(facts(AnswerDecision.PROCEED), 3, envelope(2)))
                .doesNotThrowAnyException();
        verify(factWriter, never()).writeCandidate(any(), any());
    }

    @Test
    @DisplayName("提交蒸馏：一条候选写失败不带走同一轮的其它候选")
    void oneBadCandidateDoesNotKillTheOthers() {
        when(distiller.distill(any())).thenReturn(List.of(
                draft(FactKey.BUSINESS_LINE, "对公"),
                draft(FactKey.PREFERRED_ORG, "0100")));
        doThrow(new IllegalStateException("撞唯一键")).when(factWriter)
                .writeCandidate(any(), any());

        assertThatCode(() -> service.scheduleDistill(facts(AnswerDecision.PROCEED), 3, envelope(2)))
                .doesNotThrowAnyException();

        verify(factWriter, times(2)).writeCandidate(any(), any());
    }

    @Test
    @DisplayName("提交蒸馏：模型没提出任何候选时不写任何东西")
    void emptyDraftsWriteNothing() {
        when(distiller.distill(any())).thenReturn(List.of());

        service.scheduleDistill(facts(AnswerDecision.PROCEED), 3, envelope(2));

        verify(factWriter, never()).writeCandidate(any(), any());
    }

    @Test
    @DisplayName("提交蒸馏：无引文（拒答轮）时 ref_doc_id 为 null，但其余上下文照常")
    void refDocIdIsNullWithoutCitations() {
        when(distiller.distill(any())).thenReturn(List.of(draft(FactKey.BUSINESS_LINE, "对公")));

        service.scheduleDistill(facts(AnswerDecision.REFUSED, List.of()), 3, envelope(2));

        ArgumentCaptor<MemoryFactWriter.WriteContext> ctx =
                ArgumentCaptor.forClass(MemoryFactWriter.WriteContext.class);
        verify(factWriter).writeCandidate(ctx.capture(), any());
        // 指针的用途是阶段 4 巡检①（所依制度已废止），而"我主要做对公"这类偏好本来就不依赖制度
        assertThat(ctx.getValue().refDocId()).isNull();
    }

    @Test
    @DisplayName("提交蒸馏：多份引文只取第一条 doc_id（不拼接 —— 拼接是「记忆层变成第二个知识库」的信号）")
    void onlyFirstDocIdIsKept() {
        when(distiller.distill(any())).thenReturn(List.of(draft(FactKey.BUSINESS_LINE, "对公")));

        service.scheduleDistill(facts(AnswerDecision.PROCEED, List.of(
                new CitedChunkRef("D1", "v1", "c1"),
                new CitedChunkRef("D2", "v1", "c2"))), 3, envelope(2));

        ArgumentCaptor<MemoryFactWriter.WriteContext> ctx =
                ArgumentCaptor.forClass(MemoryFactWriter.WriteContext.class);
        verify(factWriter).writeCandidate(ctx.capture(), any());
        assertThat(ctx.getValue().refDocId()).isEqualTo("D1");
    }

    @Test
    @DisplayName("提交蒸馏：关掉 L2 或 facts 为 null 时什么都不做")
    void disabledOrNullFactsDoNothing() {
        SemanticMemoryService off = service(SemanticMemoryProperties.disabled());

        off.scheduleDistill(facts(AnswerDecision.PROCEED), 3, envelope(2));
        service.scheduleDistill(null, 3, envelope(2));

        verifyNoInteractions(distiller);
        verifyNoInteractions(factWriter);
    }

    // ------------------------------------------------------------------
    // 测试数据构造
    // ------------------------------------------------------------------

    private SemanticMemoryService service(SemanticMemoryProperties custom) {
        return new SemanticMemoryService(dao, distiller, factWriter, sameThread, custom);
    }

    private static FactDraft draft(FactKey key, String value) {
        return new FactDraft(key.code(), value, new BigDecimal("0.9"));
    }

    private static TurnFacts facts(AnswerDecision decision) {
        return facts(decision, List.of(new CitedChunkRef("D1", "v1", "c1")));
    }

    private static TurnFacts facts(AnswerDecision decision, List<CitedChunkRef> refs) {
        return new TurnFacts(SID, USER, LocalDate.of(2026, 9, 25), 7L,
                "问题", "回答", decision, decision == AnswerDecision.REFUSED ? "NO_HIT" : null,
                refs, 2);
    }

    private static UserMemoryRow fact(String uid, FactKey key, String value, int clearance) {
        return factAt(uid, key, value, BASE_HIT, clearance);
    }

    private static UserMemoryRow factAt(String uid, FactKey key, String value, LocalDateTime lastHit) {
        return factAt(uid, key, value, lastHit, 2);
    }

    private static UserMemoryRow factAt(String uid, FactKey key, String value,
                                        LocalDateTime lastHit, int clearance) {
        return new UserMemoryRow(uid, USER, FactScope.USER, key.code(), value, clearance,
                null, SID, 1, null, now(), null, null, null,
                MemoryStatus.ACTIVE, lastHit, 7L, now());
    }

    private static UserMemoryRow withValidTo(UserMemoryRow row) {
        return new UserMemoryRow(row.factUid(), row.ownerId(), row.scope(), row.factKey(),
                row.factValue(), row.clearanceLevel(), row.orgPath(), row.sourceSessionId(),
                row.sourceTurnNo(), row.refDocId(), row.validFrom(), now(), "next-uid",
                row.expiresAt(), row.status(), row.lastHitAt(), row.permAuthVersion(),
                row.createdAt());
    }

    private static UserMemoryRow withStatus(UserMemoryRow row, MemoryStatus status) {
        return new UserMemoryRow(row.factUid(), row.ownerId(), row.scope(), row.factKey(),
                row.factValue(), row.clearanceLevel(), row.orgPath(), row.sourceSessionId(),
                row.sourceTurnNo(), row.refDocId(), row.validFrom(), row.validTo(),
                row.supersededBy(), row.expiresAt(), status, row.lastHitAt(),
                row.permAuthVersion(), row.createdAt());
    }

    private static UserMemoryRow withExpiresAt(UserMemoryRow row, LocalDateTime expiresAt) {
        return new UserMemoryRow(row.factUid(), row.ownerId(), row.scope(), row.factKey(),
                row.factValue(), row.clearanceLevel(), row.orgPath(), row.sourceSessionId(),
                row.sourceTurnNo(), row.refDocId(), row.validFrom(), row.validTo(),
                row.supersededBy(), expiresAt, row.status(), row.lastHitAt(),
                row.permAuthVersion(), row.createdAt());
    }

    private static SessionEnvelope envelope(int clearance) {
        return new SessionEnvelope(SID, USER, "TELLER", "P3", "0101", "0100/0101",
                clearance, List.of("kb-teller"), 7L, "MOCK",
                LocalDateTime.now().plusHours(1), "kb_id in ['kb-teller']");
    }

    private static SessionEnvelope envelopeWithoutOrgPath() {
        return new SessionEnvelope(SID, USER, "TELLER", "P3", "0101", null,
                2, List.of("kb-teller"), 7L, "MOCK",
                LocalDateTime.now().plusHours(1), "kb_id in ['kb-teller']");
    }

    private static LocalDateTime now() {
        return LocalDateTime.now();
    }
}
