package org.agent.memory;

import org.agent.api.BankAssistant;
import org.agent.memory.dao.ChatMessageDao;
import org.agent.memory.dao.ChatMessageRow;
import org.agent.permission.CreateSessionResponse;
import org.agent.permission.MockUserPermissionProvider;
import org.agent.permission.PermissionAdvisor;
import org.agent.permission.SessionAuthService;
import org.agent.rag.guard.NumericGuardrailAdvisor;
import org.agent.rag.retrieve.RetrievalAdvisor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * L1 记忆全链路打桩测试的共用脚手架：<b>不直接执行</b>（类名不匹配 Surefire 的
 * {@code *Test} 模式），由两个具体测试类继承，各自只差一个配置项。
 *
 * <h2>为什么值得单独写一个"打桩全链路"</h2>
 * L1 记忆单测（{@code ChatMemoryServiceTest} 等）验的是本类自己的规则，
 * 而下面这些恰恰是单测<b>验不到</b>、只能在真链路里才暴露的东西：
 * <ul>
 *   <li>Spring 实际解析出的 advisor 顺序（{@code ChatClient} 按 {@code getOrder()}
 *       自行排序，与 {@code defaultAdvisors(...)} 的传参顺序无关）</li>
 *   <li>{@code TurnExtractor} 从 {@code response.context()} 而不是原始 request 读检索结果
 *       —— 这条在单测里是靠手工构造"复制后的 context"模拟的，真链路里才见真章</li>
 *   <li>写回真的落到了 MySQL，读窗口真的能把它捞回来</li>
 * </ul>
 *
 * <h2>打桩为什么是干净的</h2>
 * 检索侧<b>不调模型</b>（{@code QueryExpander} 是词典驱动的确定性改写），
 * 所以 {@code openAiChatModel} 收到的调用全部来自"答案生成"这一处，拦截点没有噪声。
 * 向量库用 {@code @MockitoBean}：{@code RetrievalAdvisor} 走
 * {@code ObjectProvider.getIfAvailable()}，测试类上关掉了 {@code rag.vectorstore.enabled}，
 * 于是这是上下文里唯一的 {@code VectorStore}。
 *
 * <h2>打桩内容的两个讲究</h2>
 * ① 资料正文与答案<b>都不含阿拉伯数字</b>：数字校验 advisor 就在链上（order 10），
 * 含数字的答案会被它拦成拒答，那拒答轮就进不了历史，测试会以一种难查的方式失败；
 * ② 每条断言都只依赖"只在历史里出现"的字符串（上一轮的原话与答案），
 * 绝不用当前这轮的问题当证据 —— 当前问题本来就在 prompt 里。
 */
abstract class L1ChainScaffold {

    /** 风控/合规：密级 4，可检索 kb-risk。选它是因为只有密级 ≥ 4 才能让密级 4 的资料过门禁 */
    protected static final String USER = "10087";

    /** 打桩答案。**刻意不含数字**（见类注释 ①），且除了历史之外不会出现在任何地方 */
    protected static final String ANSWER = "按现行规定，需网点运营主管复核后放行。";
    protected static final String Q1 = "大额存单的起存金额是怎么规定的";
    /** 指代上一轮 —— 阶段 0「多轮对话能续上」的用例，答案文字只在历史里 */
    protected static final String Q2 = "那它呢";
    protected static final String Q3 = "换个问题：柜面取现要谁批";

    @Autowired
    protected BankAssistant assistant;
    @Autowired
    protected SessionAuthService auth;
    @Autowired
    protected MockUserPermissionProvider iam;
    @Autowired
    protected ChatMessageDao chatMessageDao;

    @Autowired
    protected PermissionAdvisor permissionAdvisor;
    @Autowired
    protected MemoryAdvisor memoryAdvisor;
    @Autowired
    protected RetrievalAdvisor retrievalAdvisor;
    @Autowired
    protected NumericGuardrailAdvisor numericGuardrailAdvisor;

    @MockitoBean(name = "openAiChatModel")
    protected ChatModel chatModel;
    @MockitoBean
    protected VectorStore vectorStore;

    /** 依次收到的每一份 Prompt。JUnit 每个测试方法新建实例，所以不需要清理 */
    protected final List<Prompt> prompts = new ArrayList<>();

    protected CreateSessionResponse session;

    @BeforeEach
    void openSession() {
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            prompts.add(invocation.getArgument(0));
            return new ChatResponse(List.of(new Generation(new AssistantMessage(ANSWER))));
        });
        session = auth.createSession(USER);
    }

    @AfterEach
    void closeSession() {
        auth.closeSession(session.sessionId());
        iam.reset();
    }

    // ------------------------------------------------------------------
    // 共用用例
    // ------------------------------------------------------------------

    @Test
    @DisplayName("① 第一轮：答案落库两行，clearance_level = 该轮引用资料的密级")
    void firstTurnIsPersistedWithDocClearance() {
        stubRetrieval(List.of(highDoc()));

        assistant.chat(session.sessionId(), session.sessionToken(), Q1);

        List<ChatMessageRow> rows = chatMessageDao.selectRecentTurns(session.sessionId(), 10);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(ChatMessageRow::clearanceLevel).containsExactly(4, 4);
        // 落库的用户原话是**未被 augment() 增强**的那一份 —— 这同时是"MemoryAdvisor 排在
        // RetrievalAdvisor 之前"的行为证据：若顺序反了，这里存的会带【请仅基于下列授权资料作答】
        // 前缀和整段检索原文。比断言 getOrder() 常量更有说服力，因为它验的是真链路的实际行为
        assertThat(rows.get(0).userMessage()).isEqualTo(Q1);
    }

    @Test
    @DisplayName("② 第二轮：上一轮的原话与答案真的进了 prompt（补上阶段 0 欠的端到端缺口）")
    void secondTurnInjectsFirstTurnHistory() {
        stubRetrieval(List.of(highDoc()));
        assistant.chat(session.sessionId(), session.sessionToken(), Q1);
        assistant.chat(session.sessionId(), session.sessionToken(), Q2);

        assertThat(prompts).hasSize(2);
        String second = textOf(prompts.get(1));
        // 这两个串只在历史里出现：当前这轮的问题（Q2 及其增强）与检索原文都不含它们
        assertThat(second).contains(Q1).contains(ANSWER);
    }

    @Test
    @DisplayName("③ 真链路的 advisor 顺序：Permission < Memory < Retrieval < NumericGuardrail")
    void resolvedAdvisorOrder() {
        assertThat(permissionAdvisor.getOrder()).isLessThan(memoryAdvisor.getOrder());
        assertThat(memoryAdvisor.getOrder()).isLessThan(retrievalAdvisor.getOrder());
        assertThat(retrievalAdvisor.getOrder()).isLessThan(numericGuardrailAdvisor.getOrder());
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    /** 高密级资料：密级 4，只有 10087 能过门禁 */
    protected static Document highDoc() {
        return doc("DOC-H", 4, "大额存单起存金额的相关规定。");
    }

    /** 低密级资料：密级 1，任何有密级的用户都读得到 */
    protected static Document lowDoc() {
        return doc("DOC-L", 1, "柜面取现授权的一般规定。");
    }

    private static Document doc(String docId, int level, String text) {
        return new Document(text, Map.of(
                "kb_id", "kb-risk",
                "doc_level", level,
                "org_codes", List.of("0100/0202"),
                "doc_id", docId,
                "doc_version", "v1"));
    }

    /** 重新打桩向量库的返回内容（覆盖上一次的打桩，供"换一轮资料"用） */
    protected void stubRetrieval(List<Document> docs) {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(docs);
    }

    /** 把一份 Prompt 里所有消息的文本拼起来 —— 历史注入是作为独立消息进去的，必须整体看 */
    protected static String textOf(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        for (Message m : prompt.getInstructions()) {
            if (m.getText() != null) {
                sb.append(m.getText()).append('\n');
            }
        }
        return sb.toString();
    }
}
