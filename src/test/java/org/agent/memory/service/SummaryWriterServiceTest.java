package org.agent.memory.service;

import org.agent.memory.dao.SessionSummaryDao;
import org.agent.memory.dao.SessionSummaryRow;
import org.agent.memory.model.MemoryActorType;
import org.agent.memory.model.MemoryChangeAction;
import org.agent.memory.model.MemoryEntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Mockito 测试：不打库。验的是"取代旧版 + 插入新版 + 两条审计"这段事务段的行为。
 *
 * <p>⚠️ 顺序（先取代、后插入）是<b>唯一键强制的</b>，不是风格问题 ——
 * {@code uk_session_active} 建在生成列 {@code active_session_id} 上，
 * 先 INSERT 的话旧版还没盖终点章，唯一键会当场把新版拒掉，
 * 于是"继续压缩"这个最该被支持的操作反而永远失败。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SummaryWriterService：取代 + 插入 + 配对审计")
class SummaryWriterServiceTest {

    private static final String SID = "sess-1";
    private static final String USER = "10086";
    private static final String TEXT = "上一轮讨论了《大额存单管理办法》的办理条件";

    @Mock
    private SessionSummaryDao dao;

    @Mock
    private MemoryAuditAppender auditAppender;

    private SummaryWriterService service() {
        return new SummaryWriterService(dao, auditAppender);
    }

    @Test
    @DisplayName("第一版摘要：插入 + 一条 WRITE（before 为 null，不取代任何东西）")
    void firstVersionInsertsAndWrites() {
        SessionSummaryRow incoming = row("s2", 1, 4);

        service().write(null, incoming);

        verify(dao).insert(incoming);
        verify(dao, never()).supersede(any(), any(), any());

        ArgumentCaptor<Map<String, Object>> after = mapCaptor();
        verify(auditAppender).append(eq(MemoryEntityType.SUMMARY), eq("s2"),
                eq(MemoryChangeAction.WRITE), isNull(), after.capture(),
                eq(MemoryActorType.AUTO), isNull(), isNull());
        // ⚠️ summaryText 是**要**进快照的：这一层存在的理由正是"事后能复现当时模型看到了什么"
        assertThat(after.getValue()).containsEntry("summaryText", TEXT)
                .containsEntry("coversFromTurnNo", 1)
                .containsEntry("coversThroughTurnNo", 4)
                .containsEntry("clearanceLevel", 2);
    }

    @Test
    @DisplayName("★ 第二版：先取代后插入，且两条审计配对（旧 SUPERSEDE、新 WRITE）")
    void secondVersionSupersedesThenInserts() {
        SessionSummaryRow previous = row("s1", 1, 4);
        SessionSummaryRow incoming = row("s2", 1, 6);
        when(dao.supersede(eq("s1"), any(), eq("s2"))).thenReturn(1);

        service().write(previous, incoming);

        InOrder order = inOrder(dao);
        order.verify(dao).supersede(eq("s1"), any(), eq("s2"));
        order.verify(dao).insert(incoming);

        ArgumentCaptor<Map<String, Object>> before = mapCaptor();
        ArgumentCaptor<Map<String, Object>> after = mapCaptor();
        verify(auditAppender).append(eq(MemoryEntityType.SUMMARY), eq("s1"),
                eq(MemoryChangeAction.SUPERSEDE), before.capture(), after.capture(),
                eq(MemoryActorType.AUTO), isNull(), eq("SUPERSEDED_BY_COMPACTION"));
        // 取代的 before/after **都是旧版**：新版由配对的那条 WRITE 记录，
        // 两条日志合起来才是一次完整的取代
        assertThat(before.getValue()).containsEntry("summaryText", TEXT)
                .containsEntry("validTo", null);
        assertThat(after.getValue()).containsEntry("supersededBy", "s2");
        assertThat(after.getValue().get("validTo")).isNotNull();
        // 旧版不删：版本化的摘要就是"复现当时模型看到了什么"这条缺口在记忆层的补法。
        // ⚠️ 这条断言没法写成 verify(dao, never()).delete(...) —— `SessionSummaryDao`
        // 里**根本没有** delete 方法（接口形状防误用），"不物理删除"由那个不存在的方法保证

        verify(auditAppender).append(eq(MemoryEntityType.SUMMARY), eq("s2"),
                eq(MemoryChangeAction.WRITE), isNull(), any(),
                eq(MemoryActorType.AUTO), isNull(), isNull());
    }

    @Test
    @DisplayName("取代落空（旧版已被并发取代）：★ 不插新版、不写日志、返回 null")
    void supersedeFailureAbortsTheWrite() {
        SessionSummaryRow previous = row("s1", 1, 4);
        SessionSummaryRow incoming = row("s2", 1, 6);
        when(dao.supersede(eq("s1"), any(), eq("s2"))).thenReturn(0);

        assertThat(service().write(previous, incoming)).isNull();

        // 接着插会得到两条有效摘要（一条是别人插的、一条是我的），
        // 而"一个会话一份摘要"正是覆盖完备性的前提
        verify(dao, never()).insert(any());
        verifyNoInteractions(auditAppender);
    }

    @Test
    @DisplayName("新版为 null：不碰数据库（压缩器已经判过「这份不合格」了）")
    void nullIncomingTouchesNothing() {
        assertThat(service().write(row("s1", 1, 4), null)).isNull();

        verifyNoInteractions(dao);
        verifyNoInteractions(auditAppender);
    }

    // ------------------------------------------------------------------
    // 测试数据构造
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    private static SessionSummaryRow row(String uid, int coversFrom, int coversThrough) {
        LocalDateTime now = LocalDateTime.now();
        return new SessionSummaryRow(uid, SID, USER, coversFrom, coversThrough, 2,
                TEXT, coversThrough - coversFrom, now, null, null,
                7L, now.plusDays(30), null);
    }
}
