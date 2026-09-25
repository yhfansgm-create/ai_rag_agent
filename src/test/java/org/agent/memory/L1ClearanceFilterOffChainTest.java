package org.agent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 与 {@link L1ClearanceChainTest} 的<b>同一个场景</b>，只多了一行配置：
 * {@code rag.memory.clearance-filter-enabled=false}。
 * <p>
 * 它存在的唯一目的就是<b>复现漏洞</b>：证明 §一 表 #2「降级后仍可读」确实是那个开关
 * 在管，而不是别的什么东西碰巧挡住的。同一个场景、同一个断言位置、一正一反两个结果 ——
 * 这是 RAG-07 那张威胁表最直接的可执行证据，也是演示时最有说服力的一段。
 * <p>
 * 单测层面的同一件事见 {@code ChatMemoryServiceTest.filterOffReproducesTheLeak}；
 * 这里多验的是<b>真链路</b>：配置真的被读到了 {@code MemoryAdvisor}。
 */
@SpringBootTest(properties = {
        "rag.vectorstore.enabled=false",
        "rag.memory.clearance-filter-enabled=false"})
@ActiveProfiles("local")
@DisplayName("L1 记忆的权限继承（全链路打桩：过滤关闭 → 漏洞复现）")
class L1ClearanceFilterOffChainTest extends L1ChainScaffold {

    @Test
    @DisplayName("④' 同一个场景、只关掉开关 → 高密级历史照样进 prompt（§一 表 #2 现场复现）")
    void downgradedUserStillReadsHighClearanceHistory() {
        stubRetrieval(List.of(highDoc()));
        assistant.chat(session.sessionId(), session.sessionToken(), Q1);

        stubRetrieval(List.of(lowDoc()));
        iam.downgradeClearance(USER, 1);

        assistant.chat(session.sessionId(), session.sessionToken(), Q3);

        assertThat(prompts).hasSize(2);
        String afterDowngrade = textOf(prompts.get(1));
        assertThat(afterDowngrade).contains(Q3);
        // 与 L1ClearanceChainTest 逐字对应的断言，只差正负：
        // 用户已经被降级，检索侧也拦住了高密级资料，但上一轮的高密级答案还是从历史里进来了
        assertThat(afterDowngrade).contains(Q1).contains(ANSWER);
    }
}
