package org.agent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.agent.permission.PermissionAdvisor;
import org.agent.permission.SessionAuthException;
import org.agent.permission.SessionAuthService;
import org.agent.permission.SessionEnvelope;
import org.agent.permission.UserPermissionProfile;
import org.agent.permission.UserPermissionProvider;
import org.agent.permission.dao.SessionDao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RAG 检索防越权 —— 原理测试。
 * <p>
 * 核心思想：<b>真正的数据锁必须在检索时执行</b>，而不仅是建会话时鉴权。
 * 三道防线：
 * <ol>
 *   <li><b>画像派生</b>：过滤条件由会话权限画像（kb_id 列表 + 密级 + 组织）生成，不依赖人工维护</li>
 *   <li><b>下推执行</b>：过滤条件下推给向量库，只检索授权范围内的 chunk</li>
 *   <li><b>门禁 + 脱敏</b>：检索后逐条复核（kb/密级/组织），对密级不足者的代码块脱敏或丢弃</li>
 * </ol>
 * 全链路 fail-closed：任一环节不满足 → 该 chunk 不得以原始形态进入 prompt。
 */
class RetrievalPermissionTest {

    // ---------- 夹具 ----------

    /** 构造"会话权限信封"，accessFilter 与画像一致（同 SessionAuthService.buildAccessFilter 产物） */
    private static SessionEnvelope envelope(int clearance, String orgPath, List<String> kbIds) {
        String f = "kb_id in ['" + String.join("', '", kbIds) + "'] && doc_level <= " + clearance
                + " && org_codes in ['" + orgPath + "']";
        return new SessionEnvelope("s1", "10086", "TELLER", "T3", "0101", orgPath,
                clearance, kbIds, 0L, "MOCK", null, f);
    }

    /** 构造带检索元数据的 chunk；orgs/isCode 传 null 表示缺该元数据（模拟脏数据，Document 不接受 null 值所以不放入） */
    private static Document doc(String kb, int level, Object orgs, Boolean isCode) {
        Map<String, Object> m = new HashMap<>();
        m.put("kb_id", kb);
        m.put("doc_level", level);
        if (orgs != null) {
            m.put("org_codes", orgs);
        }
        if (isCode != null) {
            m.put("is_code", isCode);
        }
        return new Document("机密内容", m);
    }

    private static SessionAuthService service() {
        return new SessionAuthService(mock(SessionDao.class), mock(UserPermissionProvider.class), new ObjectMapper());
    }

    /** 让向量库返回给定文档，走完整 adviseCall，返回最终进入上下文的文档列表 */
    private List<Document> runAdvisor(List<Document> returned, SessionEnvelope env) {
        VectorStore store = mock(VectorStore.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<VectorStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(mock(ChatClientResponse.class));
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(returned);

        Map<String, Object> ctx = new HashMap<>();
        ctx.put(PermissionAdvisor.CONTEXT_ACCESS_FILTER, env.accessFilter());
        ctx.put(PermissionAdvisor.CONTEXT_ENVELOPE, env);
        ChatClientRequest request = new ChatClientRequest(new Prompt("取款流程"), ctx);
        new RetrievalAdvisor(provider, 5).adviseCall(request, chain);

        @SuppressWarnings("unchecked")
        List<Document> kept = (List<Document>) request.context().get(RetrievalAdvisor.CONTEXT_RETRIEVED_DOCS);
        return kept == null ? List.of() : kept;
    }

    // ---------- 原理 1：过滤条件由会话权限画像派生 ----------

    @Test
    @DisplayName("原理1：检索过滤条件由权限画像派生，编码 kb_id/密级/组织 三个维度（不过滤代码——代码走脱敏）")
    void accessFilterDerivedFromProfile() {
        // 柜员：密级2、仅 kb-teller
        String teller = service().buildAccessFilter(new UserPermissionProfile(
                "10086", "TELLER", "T3", "0101", "0100/0101", 2, List.of("kb-teller"), 0L));
        assertThat(teller)
                .contains("kb_id in ['kb-teller']")
                .contains("doc_level <= 2")
                .contains("org_codes in ['0100/0101']")
                .doesNotContain("is_code");

        // 绝密级用户：密级5、kb-core
        String topSecret = service().buildAccessFilter(new UserPermissionProfile(
                "90001", "AUDITOR", "M1", "0000", "0100/0000", 5, List.of("kb-core"), 0L));
        assertThat(topSecret)
                .contains("kb_id in ['kb-core']")
                .contains("doc_level <= 5")
                .doesNotContain("is_code");
    }

    @Test
    @DisplayName("原理1b：过滤条件语法合法，可直接下推 Spring AI 向量库执行")
    void filterIsValidSpringAiExpression() {
        SessionEnvelope env = envelope(2, "0100/0101", List.of("kb-teller"));
        // 传入 filterExpression(String) 即走 Spring AI 语法解析，非法语法在此直接抛异常
        SearchRequest sr = SearchRequest.builder()
                .query("大额取款")
                .filterExpression(env.accessFilter())
                .build();
        assertThat(sr.getFilterExpression()).isNotNull();
        assertThat(sr.getFilterExpression().toString())
                .contains("kb-teller")
                .contains("org_codes")
                .doesNotContain("is_code");
    }

    @Test
    @DisplayName("原理1c：会话无可检索知识库 → 过滤条件构建即拒绝（fail-closed，不许进检索）")
    void emptyKbIdsRejectedAtFilterBuild() {
        assertThatThrownBy(() -> service().buildAccessFilter(new UserPermissionProfile(
                "10086", "TELLER", "T3", "0101", "0100/0101", 2, List.of(), 0L)))
                .isInstanceOf(SessionAuthException.class)
                .extracting(e -> ((SessionAuthException) e).getCode())
                .isEqualTo(SessionAuthException.Code.UNAUTHORIZED);
    }

    // ---------- 原理 2 + 3：下推 + 门禁复核 ----------

    @Test
    @DisplayName("原理2/3：过滤条件下推向量库；即便向量库返回超集（过滤失效），越权 chunk 仍被门禁丢弃")
    void bypassCheckDropsUnauthorizedEvenIfStoreTooBroad() {
        SessionEnvelope teller = envelope(2, "0100/0101", List.of("kb-teller"));
        // 模拟"向量库故障/过滤未生效"返回超集：5 个 chunk 里只有第 1 个真正授权
        List<Document> superset = List.of(
                doc("kb-teller", 2, List.of("0100/0101"), false),  // 授权
                doc("kb-risk", 4, List.of("0100/0202"), false),    // kb_id 越权
                doc("kb-teller", 4, List.of("0100/0101"), false),  // 密级越权
                doc("kb-teller", 2, List.of("0100/0202"), false),  // 组织越权
                doc("kb-teller", 2, null, false));                 // 元数据缺失

        VectorStore store = mock(VectorStore.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<VectorStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(mock(ChatClientResponse.class));
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(superset);

        Map<String, Object> ctx = new HashMap<>();
        ctx.put(PermissionAdvisor.CONTEXT_ACCESS_FILTER, teller.accessFilter());
        ctx.put(PermissionAdvisor.CONTEXT_ENVELOPE, teller);
        ChatClientRequest request = new ChatClientRequest(new Prompt("大额取款怎么办理"), ctx);
        new RetrievalAdvisor(provider, 5).adviseCall(request, chain);

        // ① 过滤条件确实下推给了向量库（不含 is_code —— 代码 chunk 需可检索以便脱敏）
        ArgumentCaptor<SearchRequest> sr = ArgumentCaptor.forClass(SearchRequest.class);
        verify(store).similaritySearch(sr.capture());
        assertThat(sr.getValue().getFilterExpression().toString())
                .contains("kb-teller")
                .doesNotContain("is_code");

        // ② 门禁复核后，只有真正授权的 chunk 进入上下文
        @SuppressWarnings("unchecked")
        List<Document> kept = (List<Document>) request.context().get(RetrievalAdvisor.CONTEXT_RETRIEVED_DOCS);
        assertThat(kept).hasSize(1);
    }

    // ---------- 原理 4：门禁维度矩阵 ----------

    @Test
    @DisplayName("原理4：门禁维度矩阵 —— kb_id/密级/组织 任一不符即拒绝，元数据缺失也拒绝；代码标签不拦门禁")
    void gateDimensionMatrix() {
        RetrievalAdvisor advisor = new RetrievalAdvisor(mock(ObjectProvider.class), 5);
        SessionEnvelope teller = envelope(2, "0100/0101", List.of("kb-teller"));

        // 全维度匹配 → 放行
        assertThat(advisor.passesGate(doc("kb-teller", 2, List.of("0100/0101"), false), teller)).isTrue();
        // 多组织清单里含匹配项 → 放行
        assertThat(advisor.passesGate(doc("kb-teller", 2, List.of("0100/0103", "0100/0101"), false), teller)).isTrue();
        // org_codes 以逗号字符串存储 → 放行
        assertThat(advisor.passesGate(doc("kb-teller", 2, "0100/0101,0100/0103", false), teller)).isTrue();
        // 含代码标签不影响门禁（代码走脱敏分支）→ 放行
        assertThat(advisor.passesGate(doc("kb-teller", 2, List.of("0100/0101"), true), teller)).isTrue();

        // kb_id 不在会话可检索列表 → 拒绝
        assertThat(advisor.passesGate(doc("kb-risk", 2, List.of("0100/0101"), false), teller)).isFalse();
        // 密级超过上限 → 拒绝
        assertThat(advisor.passesGate(doc("kb-teller", 3, List.of("0100/0101"), false), teller)).isFalse();
        // 组织清单不含用户 orgPath → 拒绝
        assertThat(advisor.passesGate(doc("kb-teller", 2, List.of("0100/0202"), false), teller)).isFalse();
        // 缺 org_codes 元数据 → 拒绝（fail-closed）
        assertThat(advisor.passesGate(doc("kb-teller", 2, null, false), teller)).isFalse();
    }

    // ---------- 原理 5：代码块脱敏（方案 C） ----------

    @Test
    @DisplayName("原理5a：密级不足 → 代码块脱敏为占位符，正文保留，原始代码不进上下文")
    void codeRedactionForLowClearanceUser() {
        SessionEnvelope lv3 = envelope(3, "0100/0101", List.of("kb-teller"));
        String raw = "本节验证接口正确性，代码如下：\n```java\nString secret = \"S3CRET_KEY\";\n```\n其余正文。";
        Document codeDoc = new Document(raw, Map.of(
                "kb_id", "kb-teller", "doc_level", 2, "org_codes", List.of("0100/0101"), "is_code", true));

        List<Document> kept = runAdvisor(List.of(codeDoc), lv3);

        assertThat(kept).hasSize(1);
        String text = kept.get(0).getText();
        assertThat(text)
                .contains("本节验证接口正确性")        // 正文保留
                .contains(CodeRedactor.PLACEHOLDER)   // 占位符出现
                .doesNotContain("S3CRET_KEY");        // 原始代码被隐藏
    }

    @Test
    @DisplayName("原理5b：密级5 → 代码原文放行")
    void level5SeesRawCode() {
        SessionEnvelope lv5 = envelope(5, "0100/0000", List.of("kb-core"));
        Document codeDoc = new Document("```java\nString secret = \"S3CRET_KEY\";\n```",
                Map.of("kb_id", "kb-core", "doc_level", 5, "org_codes", List.of("0100/0000"), "is_code", true));

        List<Document> kept = runAdvisor(List.of(codeDoc), lv5);

        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).getText()).contains("S3CRET_KEY");
    }

    @Test
    @DisplayName("原理5c：已标记 is_code 但无围栏可脱敏 → 整块丢弃（宁缺毋滥）")
    void taggedCodeWithoutFenceIsDropped() {
        SessionEnvelope lv3 = envelope(3, "0100/0101", List.of("kb-teller"));
        // 声明为代码但脱敏器定位不到围栏代码块（纯文字或裸代码）：无法证明已清干净 → fail-closed 丢弃
        Document tricky = new Document("校验逻辑见下方表格。", Map.of(
                "kb_id", "kb-teller", "doc_level", 2, "org_codes", List.of("0100/0101"), "is_code", true));

        List<Document> kept = runAdvisor(List.of(tricky), lv3);

        assertThat(kept).isEmpty();
    }

    @Test
    @DisplayName("原理5d：is_code=true 的裸代码 chunk（无围栏）→ 整块丢弃，正文也不给")
    void bareCodeChunkDroppedForLowClearanceUser() {
        SessionEnvelope lv3 = envelope(3, "0100/0101", List.of("kb-teller"));
        // 裸 SQL 被入库 sniffer 打标 is_code=true；检索侧无围栏可脱敏 → 整块丢弃
        Document bareCode = new Document(
                "统计口径如下：\nSELECT cust_no, bal FROM t_deposit WHERE bal > 10000;\n口径由运营部维护。",
                Map.of("kb_id", "kb-teller", "doc_level", 2, "org_codes", List.of("0100/0101"), "is_code", true));

        List<Document> kept = runAdvisor(List.of(bareCode), lv3);

        assertThat(kept).isEmpty();
    }
}
