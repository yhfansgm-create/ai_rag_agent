package org.agent.rag.retrieve;

import org.agent.permission.PermissionAdvisor;
import org.agent.permission.SessionEnvelope;
import org.agent.rag.TemporalGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索 Advisor 测试：accessFilter 下推向量库 + 旁路校验防越权。
 */
@ExtendWith(MockitoExtension.class)
class RetrievalAdvisorTest {

    @Mock
    private VectorStore vectorStore;
    @Mock
    private CallAdvisorChain chain;

    private RetrievalAdvisor advisor() {
        ObjectProvider<VectorStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(vectorStore);
        return new RetrievalAdvisor(provider, 5);
    }

    private SessionEnvelope tellerEnvelope() {
        return new SessionEnvelope("s1", "10086", "TELLER", "T3", "0101", "0100/0101",
                2, List.of("kb-teller"), 0L, "MOCK", null,
                "kb_id in ['kb-teller'] && doc_level <= 2 && org_codes in ['0100/0101']");
    }

    @Test
    @DisplayName("把 accessFilter 下推给向量库，只把授权文档拼进 prompt")
    void retrievesWithFilterAndDropsUnauthorized() {
        // 授权文档：柜员知识库, 密级2, 授权组织含 0100/0101
        Document ok = new Document("柜面取款须核对身份。",
                Map.of("kb_id", "kb-teller", "doc_level", 2, "org_codes", List.of("0100/0101"), "section", "2.1"));
        // 越权文档：风控知识库/密级4/其他组织 —— 三个维度都不该进
        Document leaked = new Document("风控机密策略。",
                Map.of("kb_id", "kb-risk", "doc_level", 4, "org_codes", List.of("0100/0202"), "section", "X"));

        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(ok, leaked));
        when(chain.nextCall(any())).thenReturn(mock(ChatClientResponse.class));

        Map<String, Object> ctx = new HashMap<>();
        SessionEnvelope env = tellerEnvelope();
        ctx.put(PermissionAdvisor.CONTEXT_ACCESS_FILTER, env.accessFilter());
        ctx.put(PermissionAdvisor.CONTEXT_ENVELOPE, env);
        ChatClientRequest request = new ChatClientRequest(new Prompt("大额取款怎么办理"), ctx);

        advisor().adviseCall(request, chain);

        // 数据锁：过滤条件确实下推给了向量库
        ArgumentCaptor<SearchRequest> sr = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(sr.capture());
        assertThat(sr.getValue().getQuery()).isEqualTo("大额取款怎么办理");
        assertThat(sr.getValue().getFilterExpression()).isNotNull();
        assertThat(sr.getValue().getFilterExpression().toString()).contains("kb-teller");

        // 旁路校验：只有授权文档被拼进 prompt
        ArgumentCaptor<ChatClientRequest> req = ArgumentCaptor.forClass(ChatClientRequest.class);
        verify(chain).nextCall(req.capture());
        String userText = req.getValue().prompt().getUserMessage().getText();
        assertThat(userText).contains("柜面取款须核对身份").doesNotContain("风控机密策略");
        assertThat(req.getValue().context().get(RetrievalAdvisor.CONTEXT_RETRIEVED_DOCS))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .hasSize(1);
    }

    @Test
    @DisplayName("未过权限校验(无 accessFilter) → 直通不检索")
    void noAccessFilter_passesThrough() {
        when(chain.nextCall(any())).thenReturn(mock(ChatClientResponse.class));
        ChatClientRequest request = new ChatClientRequest(new Prompt("随便问问"), new HashMap<>());

        advisor().adviseCall(request, chain);

        verify(chain).nextCall(any());
        assertThat(request.context()).doesNotContainKey(RetrievalAdvisor.CONTEXT_RETRIEVED_DOCS);
    }

    // ---------- 功能 1：时效门禁 ----------

    @Test
    @DisplayName("命中已废止文档 → 剔除并本地拒答，完全不调用模型")
    void expiredDoc_refusesWithoutCallingModel() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc("旧版取款流程。", "2020-01-01", "2021-12-31", "柜面操作规程")));

        ChatClientRequest request = requestWith("大额取款怎么办理", "2026-09-19");
        ChatClientResponse resp = advisor().adviseCall(request, chain);

        verify(chain, never()).nextCall(any());
        assertThat(request.context().get(RetrievalAdvisor.CONTEXT_REFUSAL_REASON))
                .isEqualTo(RetrievalAdvisor.REFUSAL_STALE);
        assertThat(answerOf(resp)).contains("已废止");
    }

    @Test
    @DisplayName("业务日期回溯到废止前 → 同一篇文档仍可用")
    void bizDateBeforeExpiry_docStillUsable() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc("旧版取款流程。", "2020-01-01", "2021-12-31", "柜面操作规程")));
        when(chain.nextCall(any())).thenReturn(mock(ChatClientResponse.class));

        ChatClientRequest request = requestWith("大额取款怎么办理", "2021-06-30");
        advisor().adviseCall(request, chain);

        ArgumentCaptor<ChatClientRequest> req = ArgumentCaptor.forClass(ChatClientRequest.class);
        verify(chain).nextCall(req.capture());
        assertThat(req.getValue().prompt().getUserMessage().getText()).contains("旧版取款流程");
    }

    @Test
    @DisplayName("有效与废止混合 → 只注入有效的，带 [n] 编号与出处，并告知剔除了几篇")
    void mixedStale_onlyValidInjectedWithCitation() {
        Document valid = doc("现行取款流程。", "2022-01-01", null, "柜面操作规程");
        Document expired = doc("旧版取款流程。", "2020-01-01", "2021-12-31", "柜面操作规程");
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(expired, valid));
        when(chain.nextCall(any())).thenReturn(mock(ChatClientResponse.class));

        ChatClientRequest request = requestWith("大额取款怎么办理", "2026-09-19");
        advisor().adviseCall(request, chain);

        ArgumentCaptor<ChatClientRequest> req = ArgumentCaptor.forClass(ChatClientRequest.class);
        verify(chain).nextCall(req.capture());
        String userText = req.getValue().prompt().getUserMessage().getText();
        assertThat(userText)
                .contains("[1] 来源：《柜面操作规程》（D-002） 版本 3 · 2.1")
                .contains("现行取款流程")
                .doesNotContain("旧版取款流程")
                .contains("另有 1 篇相关文件已废止或尚未生效");
        assertThat(req.getValue().context().get(RetrievalAdvisor.CONTEXT_RETRIEVED_DOCS))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(1);
    }

    @Test
    @DisplayName("未声明时效字段 → 放行（存量文档不会因没补字段而整体消失）")
    void undatedDoc_passes() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc("没标时效的文档。", null, null, null)));
        when(chain.nextCall(any())).thenReturn(mock(ChatClientResponse.class));

        advisor().adviseCall(requestWith("随便问问", null), chain);

        verify(chain).nextCall(any());
    }

    // ---------- 功能 2：拒答 + 引用归因 ----------

    @Test
    @DisplayName("检索无命中 → 本地拒答，不把问题丢给模型自由发挥")
    void noHit_refuses() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        ChatClientRequest request = requestWith("不存在的业务", null);
        ChatClientResponse resp = advisor().adviseCall(request, chain);

        verify(chain, never()).nextCall(any());
        assertThat(request.context().get(RetrievalAdvisor.CONTEXT_REFUSAL_REASON))
                .isEqualTo(RetrievalAdvisor.REFUSAL_NO_HIT);
        assertThat(answerOf(resp)).contains("未在您的授权范围内");
    }

    @Test
    @DisplayName("未配置 VectorStore → 拒答（不退回用模型自身知识作答）")
    void noVectorStore_refuses() {
        ObjectProvider<VectorStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        ChatClientRequest request = requestWith("随便问问", null);
        ChatClientResponse resp = new RetrievalAdvisor(provider, 5).adviseCall(request, chain);

        verify(chain, never()).nextCall(any());
        assertThat(request.context().get(RetrievalAdvisor.CONTEXT_REFUSAL_REASON))
                .isEqualTo(RetrievalAdvisor.REFUSAL_KB_UNAVAILABLE);
        assertThat(answerOf(resp)).contains("知识库当前不可用");
    }

    @Test
    @DisplayName("答案引用了不存在的编号 → 记录越界编号，标记需人工复核")
    void citationOutOfRange_recorded() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc("现行取款流程。", "2022-01-01", null, "柜面操作规程")));
        ChatClientResponse modelResp = new ChatClientResponse(
                new ChatResponse(List.of(new Generation(new AssistantMessage("按规程办理[1]，另见[9]")))),
                new HashMap<>());
        when(chain.nextCall(any())).thenReturn(modelResp);

        ChatClientRequest request = requestWith("大额取款怎么办理", "2026-09-19");
        advisor().adviseCall(request, chain);

        assertThat(request.context().get(RetrievalAdvisor.CONTEXT_CITATION_VIOLATIONS))
                .isEqualTo(List.of(9));
    }

    @Test
    @DisplayName("引用编号校验：范围内合法，越界与小技巧(前导零)都会被抓出")
    void invalidCitations_rangeCheck() {
        assertThat(RetrievalAdvisor.invalidCitations("见[1][2]和[3]", 3)).isEmpty();
        assertThat(RetrievalAdvisor.invalidCitations("见[1]和[4]", 3)).containsExactly(4);
        assertThat(RetrievalAdvisor.invalidCitations("没有引用", 3)).isEmpty();
        assertThat(RetrievalAdvisor.invalidCitations("[0]", 3)).containsExactly(0);
    }

    // ---------- 功能 3：多路召回（口语 vs 条文） ----------

    /** 带术语表的 advisor：把"年假"改写成制度用语"带薪年休假" */
    private RetrievalAdvisor advisorWithTerms() {
        ObjectProvider<VectorStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(vectorStore);
        TermDictionary dict = TermDictionary.of(
                List.of(new org.agent.rag.retrieve.TermDictionary.Term("带薪年休假", List.of("年假"))));
        return new RetrievalAdvisor(provider, 5, 15, new CodeRedactor(), new TemporalGate(),
                new QueryExpander(dict, 3), new RrfFusion(RrfFusion.DEFAULT_K));
    }

    @Test
    @DisplayName("口语提问「年假」单路无命中，术语改写那一路救回来 —— 不再误判为无资料")
    void colloquialQuery_rescuedByTermRewrite() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenAnswer(inv -> {
            SearchRequest sr = inv.getArgument(0);
            // 只有制度用语写法能命中：这正是"员工用口语问、制度用条文写"的错位
            return "带薪年休假能休几天".equals(sr.getQuery())
                    ? List.of(doc("带薪年休假每年 5 天。", "2024-01-01", null, "员工休假管理办法"))
                    : List.of();
        });
        when(chain.nextCall(any())).thenReturn(mock(ChatClientResponse.class));

        advisorWithTerms().adviseCall(requestWith("年假能休几天", "2026-09-19"), chain);

        // 两路都发了，且都带同一个权限过滤条件 —— 查询改写不能绕过数据锁
        ArgumentCaptor<SearchRequest> sr = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore, org.mockito.Mockito.times(2)).similaritySearch(sr.capture());
        assertThat(sr.getAllValues()).extracting(SearchRequest::getQuery)
                .containsExactly("年假能休几天", "带薪年休假能休几天");
        assertThat(sr.getAllValues()).allSatisfy(r -> assertThat(r.getFilterExpression()).isNotNull());

        ArgumentCaptor<ChatClientRequest> req = ArgumentCaptor.forClass(ChatClientRequest.class);
        verify(chain).nextCall(req.capture());
        assertThat(req.getValue().prompt().getUserMessage().getText()).contains("带薪年休假每年 5 天");
    }

    @Test
    @DisplayName("所有查询变体都没命中 → 仍拒答（多路召回不放宽拒答标准）")
    void allVariantsMiss_stillRefuses() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        ChatClientRequest request = requestWith("年假能休几天", null);
        advisorWithTerms().adviseCall(request, chain);

        verify(chain, never()).nextCall(any());
        assertThat(request.context().get(RetrievalAdvisor.CONTEXT_REFUSAL_REASON))
                .isEqualTo(RetrievalAdvisor.REFUSAL_NO_HIT);
    }

    @Test
    @DisplayName("两路召回同一篇 → RRF 去重，prompt 里只出现一次，不会重复占额度")
    void duplicateAcrossVariants_deduped() {
        Document same = doc("带薪年休假每年 5 天。", "2024-01-01", null, "员工休假管理办法");
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(same));
        when(chain.nextCall(any())).thenReturn(mock(ChatClientResponse.class));

        advisorWithTerms().adviseCall(requestWith("年假能休几天", "2026-09-19"), chain);

        ArgumentCaptor<ChatClientRequest> req = ArgumentCaptor.forClass(ChatClientRequest.class);
        verify(chain).nextCall(req.capture());
        assertThat(req.getValue().context().get(RetrievalAdvisor.CONTEXT_RETRIEVED_DOCS))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(1);
    }

    @Test
    @DisplayName("截断发生在门禁之后：topK=2 时，前面被密级挡掉的候选不占名额")
    void truncationAfterGate_fillsTopK() {
        ObjectProvider<VectorStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(vectorStore);
        // 高密级在前：若先截断再过滤，用户就只剩 1 篇
        Document secret = new Document("核心账务处理逻辑。",
                Map.of("kb_id", "kb-teller", "doc_level", 5, "org_codes", List.of("0100/0101")));
        Document a = doc("现行取款流程 A。", "2024-01-01", null, null);
        Document b = doc("现行取款流程 B。", "2024-01-01", null, null);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(secret, a, b));
        when(chain.nextCall(any())).thenReturn(mock(ChatClientResponse.class));

        RetrievalAdvisor advisor = new RetrievalAdvisor(provider, 2, 15, new CodeRedactor(),
                new TemporalGate(), new QueryExpander(), new RrfFusion(RrfFusion.DEFAULT_K));
        ChatClientRequest request = requestWith("大额取款怎么办理", null);
        advisor.adviseCall(request, chain);

        assertThat(request.context().get(RetrievalAdvisor.CONTEXT_RETRIEVED_DOCS))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(2);
    }

    // ---------- 测试辅助 ----------

    private static Document doc(String text, String from, String until, String title) {
        Map<String, Object> m = new HashMap<>();
        m.put("kb_id", "kb-teller");
        m.put("doc_level", 2);
        m.put("org_codes", List.of("0100/0101"));
        m.put("section", "2.1");
        m.put("doc_id", "D-002");
        m.put("doc_version", "3");
        if (title != null) {
            m.put("doc_title", title);
        }
        if (from != null) {
            m.put("effective_from", from);
        }
        if (until != null) {
            m.put("effective_until", until);
        }
        return new Document(text, m);
    }

    /** 构造带权限信封与可选业务日期的请求 */
    private ChatClientRequest requestWith(String question, String bizDate) {
        Map<String, Object> ctx = new HashMap<>();
        SessionEnvelope env = tellerEnvelope();
        ctx.put(PermissionAdvisor.CONTEXT_ACCESS_FILTER, env.accessFilter());
        ctx.put(PermissionAdvisor.CONTEXT_ENVELOPE, env);
        if (bizDate != null) {
            ctx.put(RetrievalAdvisor.PARAM_BIZ_DATE, bizDate);
        }
        return new ChatClientRequest(new Prompt(question), ctx);
    }

    private static String answerOf(ChatClientResponse resp) {
        return resp.chatResponse().getResult().getOutput().getText();
    }
}
