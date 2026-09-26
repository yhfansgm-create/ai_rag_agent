package org.agent.memory.service;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Mockito 测试：不打库。验的是 {@code MemoryFactWriter} 的<b>三种写入形态</b>与
 * 审计的配对关系 —— 这是事务段的行为，事务本身（{@code @Transactional}）由
 * Spring 的代理负责，单测不重复验它（那是集成测试的事）。
 *
 * <p>⚠️ {@code MemoryFactWriter} 是本项目<b>第一个</b>事务边界，它的正确性有两半：
 * 一半是"写对"（本测试），另一半是"事务边界没有被往上挪、把 LLM 调用包进来"（类注释里的硬约束）。
 * 后者没法单测，只能靠代码审查 —— 所以那段注释写得比代码还长。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemoryFactWriter：三种写入形态 + 审计配对")
class MemoryFactWriterTest {

    private static final String USER = "10086";
    private static final String SID = "sess-1";
    private static final String GROUNDED_MESSAGE = "我主要做对公业务，帮我看看对公开户流程";

    @Mock
    private UserMemoryDao dao;

    @Mock
    private MemoryAuditAppender auditAppender;

    private MemoryFactWriter writer() {
        return new MemoryFactWriter(dao, auditAppender);
    }

    // ------------------------------------------------------------------
    // 三种写入形态
    // ------------------------------------------------------------------

    @Test
    @DisplayName("无有效行：新增 + 一条 WRITE（before 为 null）")
    void insertsWhenNoActiveRow() {
        when(dao.selectActiveByKey(USER, FactScope.USER, FactKey.BUSINESS_LINE.code()))
                .thenReturn(null);
        when(dao.countActive(USER)).thenReturn(0);

        FactWritePolicy.Decision decision = writer().writeCandidate(ctx(), draft("对公"));

        assertThat(decision.allowed()).isTrue();
        UserMemoryRow row = capturedInsert();
        assertThat(row.ownerId()).isEqualTo(USER);
        assertThat(row.factKey()).isEqualTo(FactKey.BUSINESS_LINE.code());
        assertThat(row.factValue()).isEqualTo("对公");
        // ⚠️ 本阶段唯一能产生的 scope 就是 USER（GLOBAL 声明但读写皆拒，DEPT 无写入路径）
        assertThat(row.scope()).isEqualTo(FactScope.USER);
        assertThat(row.orgPath()).isNull();
        // 被取代时也**不改** status：它表达生命周期/健康度，"是否为当前"由 valid_to 表达
        assertThat(row.status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(row.sourceSessionId()).isEqualTo(SID);
        assertThat(row.sourceTurnNo()).isEqualTo(3);
        // 留存期由 Java 按词表算，**模型无权输出 expires_at**
        assertThat(row.expiresAt()).isAfter(LocalDateTime.now().plusDays(364));

        ArgumentCaptor<Map<String, Object>> after = mapCaptor();
        verify(auditAppender).append(eq(MemoryEntityType.FACT), eq(row.factUid()),
                eq(MemoryChangeAction.WRITE), isNull(), after.capture(),
                eq(MemoryActorType.AUTO), isNull(), isNull());
        assertThat(after.getValue()).containsEntry("factValue", "对公");
    }

    @Test
    @DisplayName("有有效行、值相同：REFRESH 续期，**不新增行**，审计 after 只改了 expiresAt")
    void refreshesSameValueWithoutInserting() {
        UserMemoryRow existing = row("f1", "对公");
        when(dao.selectActiveByKey(USER, FactScope.USER, FactKey.BUSINESS_LINE.code()))
                .thenReturn(existing);
        when(dao.refresh(eq("f1"), any(), any())).thenReturn(1);

        writer().writeCandidate(ctx(), draft("对公"));

        verify(dao).refresh(eq("f1"), any(), any());
        verify(dao, never()).insert(any());
        // 用户在新会话里重申同一件事，是"它现在仍然为真"的最强证据 ——
        // 那是一次真实的状态更新，必须留痕，但不应新增一行
        verify(dao, never()).countActive(anyString());

        ArgumentCaptor<Map<String, Object>> before = mapCaptor();
        ArgumentCaptor<Map<String, Object>> after = mapCaptor();
        verify(auditAppender).append(eq(MemoryEntityType.FACT), eq("f1"),
                eq(MemoryChangeAction.WRITE), before.capture(), after.capture(),
                eq(MemoryActorType.AUTO), isNull(), eq("REFRESH_SAME_VALUE"));
        assertThat(after.getValue()).containsEntry("factValue", "对公");
        // 这条日志读起来就该是"保质期从 X 推到了 Y"，diff 一眼看得出来。
        // ⚠️ 断言"推后了"而不是"变了" —— 拿两个 now() 算出来的时间做不等断言是**假通过**：
        // 这台机器的时钟精度下，两次 now() 真的会算出逐纳秒相同的值
        String beforeExpiry = String.valueOf(before.getValue().get("expiresAt"));
        String afterExpiry = String.valueOf(after.getValue().get("expiresAt"));
        assertThat(LocalDateTime.parse(afterExpiry)).isAfter(LocalDateTime.parse(beforeExpiry));
    }

    @Test
    @DisplayName("有有效行、值不同：★ 先取代、后插入（唯一键不允许两条有效行），审计两条")
    void supersedesWhenValueChanged() {
        UserMemoryRow existing = row("f1", "对公");
        when(dao.selectActiveByKey(USER, FactScope.USER, FactKey.BUSINESS_LINE.code()))
                .thenReturn(existing);
        when(dao.supersede(eq("f1"), any(), any())).thenReturn(1);

        writer().writeCandidate(ctx("我主要做对私业务"), draft("对私"));

        // 顺序是强制的不是偏好：uk_owner_active 建在生成列 active_key 上，
        // 先 INSERT 的话旧行还没盖终点章，唯一键会当场把新行拒掉 ——
        // 于是"改口"这个最该被支持的操作反而永远失败
        InOrder order = inOrder(dao);
        order.verify(dao).supersede(eq("f1"), any(), any());
        order.verify(dao).insert(any());

        UserMemoryRow inserted = capturedInsert();
        assertThat(inserted.factValue()).isEqualTo("对私");
        // 取代/续期都算"不新增"，配额对它们无条件放行 —— 否则用户会卡在"满了改不了口"
        verify(dao, never()).countActive(anyString());

        ArgumentCaptor<Map<String, Object>> before = mapCaptor();
        ArgumentCaptor<Map<String, Object>> after = mapCaptor();
        verify(auditAppender).append(eq(MemoryEntityType.FACT), eq("f1"),
                eq(MemoryChangeAction.SUPERSEDE), before.capture(), after.capture(),
                eq(MemoryActorType.AUTO), isNull(), eq("VALUE_CHANGED"));
        // 取代的 before/after **都是旧行**：新行的诞生由配对的那条 WRITE 记录，
        // 两条日志合起来才是一次完整的取代
        assertThat(before.getValue()).containsEntry("factValue", "对公");
        assertThat(after.getValue()).containsEntry("factValue", "对公")
                .containsEntry("supersededBy", inserted.factUid());
        verify(auditAppender).append(eq(MemoryEntityType.FACT), eq(inserted.factUid()),
                eq(MemoryChangeAction.WRITE), isNull(), any(),
                eq(MemoryActorType.AUTO), isNull(), isNull());
    }

    // ------------------------------------------------------------------
    // 门禁：拒写时一行都不动
    // ------------------------------------------------------------------

    @Test
    @DisplayName("配额到顶：拒写、不写变更日志（什么都没变）")
    void quotaExceededWritesNothing() {
        when(dao.selectActiveByKey(anyString(), any(), anyString())).thenReturn(null);
        when(dao.countActive(USER)).thenReturn(200);

        FactWritePolicy.Decision decision = writer().writeCandidate(ctx(), draft("对公"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reject()).isEqualTo(FactWritePolicy.RejectReason.QUOTA_EXCEEDED);
        verify(dao, never()).insert(any());
        // 例行质量过滤不写审计：它们没有对应的记忆条目，记进去只会把审计表灌满
        // "从未存在过的记忆"，让真正需要审计的写/取代事件被淹没
        verifyNoInteractions(auditAppender);
    }

    @Test
    @DisplayName("★ 复述验证不过：拒写且**不写日志**（模型推测的假事实连痕迹都不该留）")
    void notGroundedWritesNothing() {
        when(dao.selectActiveByKey(anyString(), any(), anyString())).thenReturn(null);
        when(dao.countActive(USER)).thenReturn(0);

        // 用户只是在**问**利率，模型却把"偏好产品=大额存单"当成了事实
        FactWritePolicy.Decision decision = writer()
                .writeCandidate(ctx("大额存单利率多少"), draft("大额存单"));

        assertThat(decision.reject()).isEqualTo(FactWritePolicy.RejectReason.NOT_GROUNDED);
        verify(dao, never()).insert(any());
        verifyNoInteractions(auditAppender);
    }

    @Test
    @DisplayName("★ 内容红线：写 GUARD_REJECT，且快照里**只有 {factKey, pattern, valueLength}、没有值**")
    void redlineWritesGuardRejectWithoutTheValue() {
        when(dao.selectActiveByKey(anyString(), any(), anyString())).thenReturn(null);
        when(dao.countActive(USER)).thenReturn(0);

        FactWritePolicy.Decision decision = writer()
                .writeCandidate(ctx("我的登录密码是 abc"), draft("登录密码"));

        assertThat(decision.reject()).isEqualTo(FactWritePolicy.RejectReason.REDLINE_VALUE);
        verify(dao, never()).insert(any());

        ArgumentCaptor<Map<String, Object>> after = mapCaptor();
        verify(auditAppender).append(eq(MemoryEntityType.FACT), isNull(),
                eq(MemoryChangeAction.GUARD_REJECT), isNull(), after.capture(),
                eq(MemoryActorType.AUTO), isNull(), eq("REDLINE_VALUE"));
        // 把值抄进审计等于把疑似凭证又存了一份 —— **审计本身成了泄露面**
        assertThat(after.getValue()).containsOnlyKeys("factKey", "pattern", "valueLength");
        assertThat(after.getValue()).doesNotContainValue("登录密码");
        assertThat(String.valueOf(after.getValue())).doesNotContain("登录密码");
    }

    @Test
    @DisplayName("未知键 / draft 为 null：不碰数据库（连「这条键有没有有效行」都不该查）")
    void unknownKeyTouchesNothing() {
        FactWritePolicy.Decision unknown = writer()
                .writeCandidate(ctx(), draftOf("no_such_key", "值"));
        FactWritePolicy.Decision nullDraft = writer().writeCandidate(ctx(), null);

        assertThat(unknown.reject()).isEqualTo(FactWritePolicy.RejectReason.UNKNOWN_KEY);
        assertThat(nullDraft.reject()).isEqualTo(FactWritePolicy.RejectReason.UNKNOWN_KEY);
        verifyNoInteractions(dao);
        verifyNoInteractions(auditAppender);
    }

    @Test
    @DisplayName("上下文为 null：直接返回 null，不写任何东西")
    void nullContextWritesNothing() {
        assertThat(writer().writeCandidate(null, draft("对公"))).isNull();
        verifyNoInteractions(dao);
    }

    // ------------------------------------------------------------------
    // 并发：乐观并发保护生效时不许"接着写"
    // ------------------------------------------------------------------

    @Test
    @DisplayName("续期落空（行已被并发取代）：不打审计（那次状态变化没有发生）")
    void refreshZeroAffectedWritesNoAudit() {
        when(dao.selectActiveByKey(anyString(), any(), anyString())).thenReturn(row("f1", "对公"));
        when(dao.refresh(eq("f1"), any(), any())).thenReturn(0);

        writer().writeCandidate(ctx(), draft("对公"));

        verifyNoInteractions(auditAppender);
    }

    @Test
    @DisplayName("取代落空：★ 放弃写新行（否则会得到两条有效行，superseded_by 还会指向链外的键）")
    void supersedeZeroAffectedWritesNoNewRow() {
        when(dao.selectActiveByKey(anyString(), any(), anyString())).thenReturn(row("f1", "对公"));
        when(dao.supersede(eq("f1"), any(), any())).thenReturn(0);

        writer().writeCandidate(ctx("我主要做对私业务"), draft("对私"));

        verify(dao, never()).insert(any());
        verifyNoInteractions(auditAppender);
    }

    // ------------------------------------------------------------------
    // 无源不写：第二道防线（类型层面）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 无来源轮号的 WriteContext 拒绝构造（写进去的 source_turn_no=0 看着像有来源，实际指不回去）")
    void contextWithoutTurnNoIsRejected() {
        assertThatThrownBy(() -> new MemoryFactWriter.WriteContext(USER, SID, 0, null,
                2, 7L, GROUNDED_MESSAGE, true, 200))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // 测试数据构造
    // ------------------------------------------------------------------

    private static MemoryFactWriter.WriteContext ctx() {
        return ctx(GROUNDED_MESSAGE);
    }

    private static MemoryFactWriter.WriteContext ctx(String userMessage) {
        return new MemoryFactWriter.WriteContext(USER, SID, 3, "D1", 2, 7L,
                userMessage, true, 200);
    }

    private static FactDraft draft(String value) {
        return draftOf(FactKey.BUSINESS_LINE.code(), value);
    }

    private static FactDraft draftOf(String rawKey, String value) {
        return new FactDraft(rawKey, value, new BigDecimal("0.9"));
    }

    private UserMemoryRow capturedInsert() {
        ArgumentCaptor<UserMemoryRow> captor = ArgumentCaptor.forClass(UserMemoryRow.class);
        verify(dao).insert(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    /**
     * 一条已存在的有效行。
     * <p>
     * ⚠️ {@code expiresAt} 刻意取 <b>30 天</b>而不是词表算出来的 365 天：
     * 续期用例要断言"到期日被推后了"，若这里也用 365 天，两个时间会来自两次
     * {@code LocalDateTime.now()}（精度可能不足以区分），断言就成了碰运气。
     * 存量行本来就可能是按旧 TTL 写的 —— 这个值反而更贴近真实。
     */
    private static UserMemoryRow row(String uid, String value) {
        LocalDateTime now = LocalDateTime.now();
        return new UserMemoryRow(uid, USER, FactScope.USER, FactKey.BUSINESS_LINE.code(),
                value, 2, null, SID, 1, null, now, null, null, now.plusDays(30),
                MemoryStatus.ACTIVE, null, 7L, now);
    }
}
