package org.agent.memory;

import org.agent.memory.dao.ChatMessageRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全链路打桩（过滤<b>开启</b>，即默认配置）：驱动真实的 {@code BankAssistant.chat()}，
 * 走完 Permission → Memory → Retrieval → NumericGuardrail 整条 advisor 链，
 * 向量库与模型都打桩，只有 MySQL 是真的。
 * <p>
 * 需要本机 MySQL 在运行、且已执行 {@code sql/chat_message.sql}（含阶段 1 的
 * {@code clearance_level} 列）。会话用随机 UUID，所以不需要清理。
 * <p>
 * 另一个具体类 {@link L1ClearanceFilterOffChainTest} 只差一个配置项，用来复现漏洞。
 */
@SpringBootTest(properties = "rag.vectorstore.enabled=false")
@ActiveProfiles("local")
@DisplayName("L1 记忆的权限继承（全链路打桩：过滤开启）")
class L1ClearanceChainTest extends L1ChainScaffold {

    @Test
    @DisplayName("④ 降级后读不到旧轮的高密级历史 —— §一 表 #2「降级后仍可读」被堵住")
    void downgradedUserCannotReadHighClearanceHistory() {
        // 第一轮：密级 4 的用户拿到密级 4 的资料，整轮以密级 4 落库
        stubRetrieval(List.of(highDoc()));
        assistant.chat(session.sessionId(), session.sessionToken(), Q1);
        assertThat(chatMessageDao.selectRecentTurns(session.sessionId(), 10))
                .extracting(ChatMessageRow::clearanceLevel).containsExactly(4, 4);

        // 权限被降级到 1；同时换掉打桩内容，让降级后的检索仍能成功
        // （密级 1 的资料过得了新门禁）—— 否则这一轮会直接拒答，模型根本不被调用，
        // 也就无从断言"prompt 里有什么"，测试会以假阴性的方式通过
        stubRetrieval(List.of(lowDoc()));
        iam.downgradeClearance(USER, 1);

        assistant.chat(session.sessionId(), session.sessionToken(), Q3);

        assertThat(prompts).hasSize(2);
        String afterDowngrade = textOf(prompts.get(1));
        // 先证明这一轮真的走到了模型：当前问题在 prompt 里。少了这一步，
        // 下面的"不含"可能只是因为整轮被拒答，属于假阴性
        assertThat(afterDowngrade).contains(Q3);
        // 核心断言：检索侧拦住了密级 4 的资料，记忆侧也没让它从历史里绕回来。
        // 不这么做的话，SessionAuthService 每次请求实时校验 auth_version 的 TOCTOU 防护
        // 会被这条历史旁路整个绕过去
        assertThat(afterDowngrade).doesNotContain(Q1).doesNotContain(ANSWER);
    }
}
