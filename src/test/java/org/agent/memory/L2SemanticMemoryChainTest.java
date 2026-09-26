package org.agent.memory;

import org.agent.memory.dao.MemoryChangeLogRow;
import org.agent.memory.dao.UserMemoryRow;
import org.agent.memory.distill.DistillRequest;
import org.agent.memory.model.FactDraft;
import org.agent.memory.model.FactKey;
import org.agent.memory.model.FactScope;
import org.agent.memory.model.MemoryChangeAction;
import org.agent.memory.model.MemoryEntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L2 语义记忆的全链路打桩（复述验证<b>开启</b>，即默认配置）：本阶段最有价值的一对测试。
 *
 * <h2>它的套路是：<b>让模型说谎</b></h2>
 * 蒸馏器被 {@code @MockitoBean} 换掉，并被 stub 成"无论用户说什么，它都声称抽到了一条事实"。
 * 于是"记忆有没有落库"这件事<b>只可能由 Java 侧的复述验证决定</b> ——
 * 而不是靠"模型这轮碰巧没提"。
 * <p>
 * 这一点是这对测试的全部价值所在。一个"模型没提所以没写"的用例什么都证明不了
 * （它连验证被调用过都没证明）；而"模型提了、值也确实在原话里出现过、仍然被拒"
 * 才把 {@code FactGrounding} 第 3 步（自述信号）从注释里的承诺变成了可执行证据。
 * 阶段 1 在 {@code min} 上踩过的正是"看起来像检查、实际什么都不做"那个坑（空操作），
 * 这里不能犯第二次。
 *
 * <h2>为什么用例内要换用户、还要清场</h2>
 * L1 的历史按<b>会话</b>存（会话是随机 UUID，天然隔离），L2 的事实按<b>用户</b>存
 * （跨会话可见，这正是它的用途）—— 而 Mock IAM 只有两名员工。若与
 * {@link L2GroundingOffChainTest} 共用用户（那个类会写下一条 {@code preferred_org}），
 * 本类"零行"的断言就会<b>依赖执行顺序</b>地假失败，那是最难查的一类失败。
 * <p>
 * 换用户是 {@link #openSessionAs(String)}（刻意的<b>按方法</b>换，理由见它的注释：
 * 继承来的 L1 用例要跑在默认用户那套实况上），清场是 {@link #clearFactsOf(String)}
 * （同一个用户上一次跑留下的行会让写入走 REFRESH 而不是 INSERT，断言随之失真）。
 * 两个方法的详细论证分别在 {@code L1ChainScaffold} 的类注释 ② 与 {@code clearFactsOf} 上。
 *
 * <h2>两个用例的键是刻意分开的</h2>
 * 正例写 {@code business_line}、反例断言 {@code preferred_org} 零行 ——
 * 于是同一个类里两个方法谁先跑都不影响对方（JUnit 不保证方法顺序）。
 */
@SpringBootTest(properties = "rag.vectorstore.enabled=false")
@ActiveProfiles("local")
@DisplayName("L2 语义记忆（全链路打桩：复述验证开启）")
class L2SemanticMemoryChainTest extends L1ChainScaffold {

    /**
     * 用户<b>自述</b>的一句话。三个要件都齐：
     * 许可词 {@code 对公}（{@code FactKey.BUSINESS_LINE} 的第一个许可词）、
     * 自述信号 {@code 我…主要}（复述验证第 3 步）、
     * 值要素 {@code 对公}（第 4 步，须是归一化原话的子串）。
     */
    private static final String SELF_NARRATION = "我主要做对公业务，帮我看看对公开户要哪些材料";

    /**
     * <b>只是提问，不是自述</b>。它含许可词 {@code 网点}（{@code PREFERRED_ORG} 的第一个许可词），
     * 所以能过第 2 步；但整句里没有任何自述信号，且值 {@code 网点} 又确实是原话的子串 ——
     * 于是它<b>只</b>能被第 3 步拦下。这正是 RAG-07 缺口 5 那个取舍的正面用法：
     * 拿"提问"与"自述"的差别当判据。
     */
    private static final String MERE_QUESTION = "网点取现要谁批";

    /** 第二轮的问题。它只在"下一轮真的注入了"这件事上被用到，内容不重要 */
    private static final String FOLLOW_UP = "那它要多久";

    /** 模型声称的值。正例反例都在原话里找得到它 —— 见 {@link #MERE_QUESTION} 的注释 */
    private static final String CLAIMED_LINE = "对公";
    private static final String CLAIMED_ORG = "网点";

    @Test
    @DisplayName("① 自述的事实被写入：密级取自该轮资料、来源指向真实轮次，并在下一轮注入 prompt")
    void groundedFactIsWrittenAndInjectedNextTurn() {
        openSessionAs(TELLER_USER);
        clearFactsOf(TELLER_USER);
        when(factDistiller.distill(any())).thenReturn(List.of(claim(FactKey.BUSINESS_LINE, CLAIMED_LINE)));
        stubRetrieval(List.of(tellerDoc()));
        List<MemoryChangeLogRow> chainBefore = chainSnapshot();

        assistant.chat(session.sessionId(), session.sessionToken(), SELF_NARRATION);

        UserMemoryRow row = activeFact(FactKey.BUSINESS_LINE);
        assertThat(row).isNotNull();
        // 归属**只**来自本轮实况信封（FactDraft 里根本没有 owner 字段，见其类注释）
        assertThat(row.ownerId()).isEqualTo(TELLER_USER);
        assertThat(row.scope()).isEqualTo(FactScope.USER);
        assertThat(row.factValue()).isEqualTo(CLAIMED_LINE);
        // 密级**不重算**：它就是 L1 那一轮算好的那个数（该轮引用资料的最高密级）。
        // 断成常量而不是"再算一遍"，是为了锁住"一个公式一个出口"这条契约
        assertThat(row.clearanceLevel()).isEqualTo(TELLER_DOC_LEVEL);
        // 无源不写：来源必须指向真的存在的那一轮
        assertThat(row.sourceSessionId()).isEqualTo(session.sessionId());
        assertThat(row.sourceTurnNo()).isEqualTo(1);
        assertThat(row.expiresAt()).isNotNull();
        assertThat(row.validTo()).isNull();
        // 审计：这条事实留下了 WRITE 记录。
        // ⚠️ 断言必须落在**这一条的 fact_uid** 上 —— memory_change_log 是全库共享的，
        //    "表里有 WRITE 行"在任何一次别的用例跑过之后都成立，那是个假通过
        assertThat(chainSnapshot())
                .anyMatch(log -> row.factUid().equals(log.factUid())
                        && log.entityType() == MemoryEntityType.FACT
                        && log.action() == MemoryChangeAction.WRITE);
        // 一次 INSERT 只留一行，且它接得上前一条（全链在这个库里永远不可能为空，见该方法的注释）
        assertAppendedRows(chainBefore, 1);

        assistant.chat(session.sessionId(), session.sessionToken(), FOLLOW_UP);

        String second = textOf(prompts.get(1));
        // 这个串只在 L2 事实块里可能出现：本轮问题、检索原文、上一轮原话都不含
        // 「业务条线：」这个由词表标签拼出来的前缀（第一轮原话里只有裸的"对公"）
        assertThat(second).contains(FactKey.BUSINESS_LINE.label() + "：" + CLAIMED_LINE);
    }

    @Test
    @DisplayName("② ★ 同一个会撒谎的模型：用户只是问了一句，同一条事实被拒（验证真的能失败）")
    void modelProposedFactIsRejectedWhenNotGroundedInUserUtterance() {
        openSessionAs(TELLER_USER);
        clearFactsOf(TELLER_USER);
        // ⚠️ 与 ① 用的是**同一个 stub**：模型依旧声称它抽到了一条事实。
        //    所以"零行"这件事只可能由 FactGrounding 第 3 步造成 —— 这正是要证明的
        when(factDistiller.distill(any())).thenReturn(List.of(claim(FactKey.PREFERRED_ORG, CLAIMED_ORG)));
        stubRetrieval(List.of(tellerDoc()));
        List<MemoryChangeLogRow> chainBefore = chainSnapshot();

        assistant.chat(session.sessionId(), session.sessionToken(), MERE_QUESTION);

        // 先证明这一轮**真的走到了蒸馏**：少了这一步，下面的"零行"可能只是因为
        // 整条 L2 通路压根没跑（L2 关掉、轮号没拿到、异常被吞）—— 那是假通过
        ArgumentCaptor<DistillRequest> captor = ArgumentCaptor.forClass(DistillRequest.class);
        verify(factDistiller).distill(captor.capture());
        assertThat(captor.getValue().userMessage()).isEqualTo(MERE_QUESTION);
        // 顺带锁住另一条契约：蒸馏的输入里**没有答案**（避免了"把模型编的答案抄成记忆"）
        assertThat(captor.getValue().toString()).doesNotContain(ANSWER);

        assertThat(activeFact(FactKey.PREFERRED_ORG)).isNull();
        // NOT_GROUNDED 是例行的质量过滤，**刻意不写审计**（见 MemoryChangeAction 的注释）——
        // 所以这里是"一行都没记"，而不是"链里有一条被拒记录"
        assertAppendedRows(chainBefore, 0);

        assistant.chat(session.sessionId(), session.sessionToken(), FOLLOW_UP);
        assertThat(textOf(prompts.get(1)))
                .doesNotContain(FactKey.PREFERRED_ORG.label() + "：");
    }

    /** 一条"模型提出的候选"。用 {@code BigDecimal} 是 {@code FactDraft} 的字段类型 */
    private static FactDraft claim(FactKey key, String value) {
        return new FactDraft(key.code(), value, new BigDecimal("0.9"));
    }
}
