package org.agent.rag;

import org.agent.permission.PermissionAdvisor;
import org.agent.permission.SessionEnvelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
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
}
