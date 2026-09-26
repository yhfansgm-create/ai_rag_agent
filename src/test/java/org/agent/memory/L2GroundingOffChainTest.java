package org.agent.memory;

import org.agent.memory.dao.MemoryChangeLogRow;
import org.agent.memory.dao.UserMemoryRow;
import org.agent.memory.model.FactDraft;
import org.agent.memory.model.FactKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 复述验证<b>关掉</b>时的全链路（对应 {@code rag.memory.semantic.grounding-enabled=false}）：
 * <b>漏洞现场复现</b>，与 {@link L2SemanticMemoryChainTest} 那个类逐条配对。
 *
 * <h2>它和配对类的关系：一行配置之差</h2>
 * 同一个会撒谎的蒸馏器 stub、同一句<b>只是提问</b>的用户原话、同一个键，
 * 唯一差别是本类把复述验证关掉了。于是：
 * <ul>
 *   <li>{@link L2SemanticMemoryChainTest}（验证开启）⇒ 零行 —— 模型猜的东西进不来</li>
 *   <li><b>本类（验证关闭）⇒ 落库，并在下一轮被当成"该用户自述的稳定信息"注入 prompt</b></li>
 * </ul>
 * 两条合起来才是"这个防护真的在起作用"的完整证据。只写前一条的话，它证明的只是
 * "这一轮恰好没写"；加上后一条，"是那道验证拦住的"才被排除了所有其它解释。
 * <p>
 * 这个手法与阶段 1 的 {@code L1ClearanceFilterOffChainTest}
 * （关掉密级过滤 ⇒ 降级用户又能读到旧轮的高密级结论）<b>逐字对应</b>：
 * <b>能关掉一个防护，才证明那个防护真的在起作用</b> —— 这句话是本项目对
 * "怎么让人相信这道防线不是装饰"的一贯回答，两个阶段各示范一次。
 *
 * <h2>为什么断言"注入"这一步是必要的</h2>
 * 只断言"库里有一行"只证明写进去了；而这个漏洞的<b>危害</b>恰恰在于它会被注入未来所有
 * 轮次 —— 用户只是问了一句「网点取现要谁批」，此后每一轮对话都会被告知
 * 「常用机构：网点」。危害在读取侧，所以证据也要落在读取侧。
 *
 * <h2>用户与清场</h2>
 * 用默认用户 {@link #USER}（10087，密级 4，能读 {@code highDoc()}）。
 * {@link #resetFacts()} 的理由与配对类同：L2 的事实按用户存，
 * 上一次跑留下的同值行会让这次写成 REFRESH（{@code source_*} 不变）而不是 INSERT。
 */
@SpringBootTest(properties = {
        "rag.vectorstore.enabled=false",
        "rag.memory.semantic.grounding-enabled=false"})
@ActiveProfiles("local")
@DisplayName("L2 语义记忆（全链路打桩：复述验证关闭 → 漏洞现场）")
class L2GroundingOffChainTest extends L1ChainScaffold {

    /** <b>只是提问，不是自述</b>：含许可词 {@code 网点}，但整句没有任何自述信号 */
    private static final String MERE_QUESTION = "网点取现要谁批";

    /** 下一轮的问题。只用来证明"上一轮记下的东西真的进了 prompt" */
    private static final String FOLLOW_UP = "那它要多久";

    /** 模型声称的值 —— 它确实是用户原话的子串，所以只有第 3 步（自述信号）拦得住它 */
    private static final String CLAIMED_ORG = "网点";

    @BeforeEach
    void resetFacts() {
        clearFactsOf(USER);
    }

    @Test
    @DisplayName("★ 关掉复述验证之后，同一条「模型猜出来的偏好」落库、并被当成用户自述注入下一轮")
    void ungroundedFactIsWrittenWhenGroundingIsOff() {
        when(factDistiller.distill(any()))
                .thenReturn(List.of(new FactDraft(FactKey.PREFERRED_ORG.code(), CLAIMED_ORG,
                        new BigDecimal("0.9"))));
        stubRetrieval(List.of(highDoc()));
        List<MemoryChangeLogRow> chainBefore = chainSnapshot();

        assistant.chat(session.sessionId(), session.sessionToken(), MERE_QUESTION);

        UserMemoryRow row = activeFact(FactKey.PREFERRED_ORG);
        assertThat(row).isNotNull();
        assertThat(row.factValue()).isEqualTo(CLAIMED_ORG);
        assertThat(row.ownerId()).isEqualTo(USER);
        // 密级照旧继承本轮资料 —— **关掉验证不放松权限**，两份数据是正交的两件事。
        // 这一条正是"关掉一个防护"能安全做对比演示的前提
        assertThat(row.clearanceLevel()).isEqualTo(HIGH_DOC_LEVEL);
        assertThat(row.sourceSessionId()).isEqualTo(session.sessionId());
        assertThat(row.sourceTurnNo()).isEqualTo(1);
        // 该键没有有效行 ⇒ INSERT：审计上<b>只留一行</b> WRITE。
        // 这一步同时反证了 clearFactsOf 真的清干净了 —— 否则这里会是 SUPERSEDE + WRITE 两行
        assertAppendedRows(chainBefore, 1);

        assistant.chat(session.sessionId(), session.sessionToken(), FOLLOW_UP);

        // 危害落点：模型<b>猜</b>出来的偏好，此后每一轮都作为"用户自述"进入 prompt。
        // 用户从没说过自己常跑网点 —— 这个串只可能来自 L2 事实块
        assertThat(textOf(prompts.get(1)))
                .contains(FactKey.PREFERRED_ORG.label() + "：" + CLAIMED_ORG);
        // 第二轮蒸馏交回的是**同一条**事实 ⇒ 不是重复劳动，是"用户重申了它"这个信号：
        // 走 REFRESH（不新增行、不改 source_*，只把 expires_at 往后推），审计上多一条
        // 带 REFRESH_SAME_VALUE 原因的 WRITE —— 于是"续期"这件事也是可审计的
        assertAppendedRows(chainBefore, 2);
        assertThat(chainSnapshot())
                .anyMatch(log -> row.factUid().equals(log.factUid())
                        && "REFRESH_SAME_VALUE".equals(log.reason()));
    }
}
