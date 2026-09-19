package org.agent.App;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 检索效果测试：连真实 PG 向量库 + 硅基流动 embedding（走网络，config 见 application-local.yml）。
 * <p>
 * 与 LoveAppTest 的区别：这里测的是「检索」这一环（向量库能否召回对的 chunk），
 * 只有 {@link #chatEndToEnd()} 会真正调 LLM，且需 DEEPSEEK_API_KEY 环境变量，未配置则跳过。
 * <p>
 * 前置条件：kb-test-data/valid 已入库（POST /kb/ingest 或 RagDocumentIngestService#ingestDir）。
 * 断言基于 kb-test-data 的测试文档（内容为编造数据）。
 * <p>
 * 必须显式 @ActiveProfiles("local")：src/test/resources/application.yml 会遮蔽主配置，
 * 主配置里的 spring.profiles.active=local 在测试期不生效，不加则 MySQL/PG/rag.* 全部读不到。
 * 与 PermissionModuleContextTest 的差别是这里不关 vectorstore —— 本测试正是要连真向量库。
 */
@SpringBootTest
@ActiveProfiles("local")
class RagAppTest {

    @Resource
    private RagApp ragApp;

    @Test
    @DisplayName("检索：柜面大额取现的授权要求 → 首位命中《柜面大额存取款业务规范》")
    void retrievesTellerRule() {
        List<Document> docs = ragApp.retrieve("柜面大额取现需要谁授权？");
        System.out.println("[柜面大额取现] " + RagApp.describeHits(docs));

        assertThat(docs).as("检索结果不应为空（检查知识库是否已入库）").isNotEmpty();
        assertThat(docs.get(0).getMetadata().get("doc_id")).isEqualTo("TELLER-RULE-2025-013");
    }

    @Test
    @DisplayName("检索：语义改写（不含原文关键词）也能召回 → 信贷审批权限")
    void retrievesBySemantics() {
        // 提问刻意避开文档标题用词（"信贷审批授权管理办法"），验证的是向量语义召回而非关键词匹配
        List<Document> docs = ragApp.retrieve("多大的贷款金额需要报总行审批？");
        System.out.println("[贷款审批] " + RagApp.describeHits(docs));

        assertThat(docs).isNotEmpty();
        assertThat(docs).anySatisfy(d ->
                assertThat(d.getMetadata().get("doc_id")).isEqualTo("LOAN-POLICY-2023-002"));
    }

    @Test
    @DisplayName("检索：topK 生效，且命中数不超过库中实际条数")
    void topKLimitsHits() {
        assertThat(ragApp.retrieve("大额取款", 1)).hasSize(1);
        assertThat(ragApp.retrieve("大额取款", 3)).hasSize(3);
        System.out.println("[topK=3] " + RagApp.describeHits(ragApp.retrieve("大额取款", 3)));
    }

    @Test
    @DisplayName("检索：库外问题得分明显低于库内问题（用两者相对比较，不写死阈值）")
    void outOfDomainScoresLower() {
        List<Document> inDomain = ragApp.retrieve("柜面大额取现需要谁授权？");
        List<Document> outDomain = ragApp.retrieve("明天北京天气怎么样，适合去爬山吗？");
        System.out.println("[库外问题] " + RagApp.describeHits(outDomain));

        double bestIn = inDomain.get(0).getScore();
        double bestOut = outDomain.get(0).getScore();
        assertThat(bestOut)
                .as("库外问题的最高相似度(%.4f)应低于库内问题(%.4f)", bestOut, bestIn)
                .isLessThan(bestIn);
        // 同一批结果按相似度降序，顺序可信才能拿 top-1 当答案依据
        assertThat(inDomain).isSortedAccordingTo((a, b) -> Double.compare(b.getScore(), a.getScore()));
    }

    @Test
    @DisplayName("端到端：检索 + LLM 作答，答案应基于知识库资料（需 DEEPSEEK_API_KEY）")
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
    void chatEndToEnd() {
        String answer = ragApp.doChat("柜面大额取现需要谁授权？", UUID.randomUUID().toString());
        System.out.println("[RAG 回答] " + answer);

        assertThat(answer).isNotBlank();
        // 资料：单笔取现 5 万以上构成大额，由网点运营主管复核后放行
        assertThat(answer).contains("主管");
    }
}
