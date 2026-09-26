package org.agent.memory;

import org.agent.api.BankAssistant;
import org.agent.memory.dao.ChatMessageDao;
import org.agent.memory.dao.ChatMessageRow;
import org.agent.memory.dao.MemoryChangeLogDao;
import org.agent.memory.dao.MemoryChangeLogRow;
import org.agent.memory.dao.SessionSummaryDao;
import org.agent.memory.dao.UserMemoryDao;
import org.agent.memory.distill.FactDistiller;
import org.agent.memory.distill.SummaryWriter;
import org.agent.memory.model.FactKey;
import org.agent.memory.model.FactScope;
import org.agent.memory.dao.UserMemoryRow;
import org.agent.memory.service.MemoryChangeLogVerifier;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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
 *
 * <h2>阶段 2 的两处扩展（L2 与摘要的链路测试要用的）</h2>
 * <ol>
 *   <li><b>两个 seam 打桩 + 蒸馏线程池改成同线程执行</b>：{@code FactDistiller} 与
 *       {@code SummaryWriter} 用 {@code @MockitoBean} 换掉，{@code memoryDistillExecutor}
 *       的 {@code execute} 改成<b>就地跑</b>。于是"L1 落库 → 提交蒸馏 → 蒸馏真的写了记忆"
 *       这条路走的是<b>真代码</b>，却<b>一次模型调用都不多</b> ——
 *       这一点是硬约束：本类的既有断言 {@code assertThat(prompts).hasSize(2)} 数的是
 *       {@code chatModel.call()} 的次数，蒸馏若真去调模型，会把那个断言从
 *       "这一轮只调了一次模型"改成"这一轮调了几次模型"，验的东西就变味了</li>
 *   <li><b>按用户隔离</b>：见 {@link #openSessionAs(String)} 与 {@link #clearFactsOf(String)}。
 *       L1 的历史按<b>会话</b>存（会话是随机 UUID，天然隔离），L2 的事实按<b>用户</b>存
 *       （跨会话可见，这正是它的用途）—— 而 Mock IAM 只有两名员工。所以需要"正反两个用例
 *       互不污染"的 L2 类必须换用户、并清掉被测用户已有的行，这是本类唯一一处为隔离而加的钩子</li>
 * </ol>
 */
abstract class L1ChainScaffold {

    /** 风控/合规：密级 4，可检索 kb-risk。选它是因为只有密级 ≥ 4 才能让密级 4 的资料过门禁 */
    protected static final String USER = "10087";

    /**
     * 柜员：密级 2，只能检索 kb-teller。
     * <p>
     * 它存在的理由是<b>用户级隔离</b>（见类注释 ②）：L2 的事实按用户存在同一张表里，
     * 两个正反用例若共用用户，先跑的那个写下的行会让后跑的那个"不落库"断言假失败 ——
     * 而且是<b>依赖执行顺序</b>的失败，最难查的那种。
     */
    protected static final String TELLER_USER = "10086";

    /**
     * {@link #tellerDoc()} 的密级，也是柜员画像的 {@code dataClearanceLevel}。
     * <p>
     * 提成常量是为了让 L2 的链路测试能断言"事实的密级 = 该轮资料密级"时
     * <b>引用同一个数</b>，而不是在两处各写一个 2 —— 那种写法在有人调了资料密级之后
     * 会先静默错位、再以"密级抄错了"的面目出现。
     */
    protected static final int TELLER_DOC_LEVEL = 2;

    /**
     * {@link #highDoc()} 的密级，也是默认用户 {@link #USER} 画像的 {@code dataClearanceLevel}。
     * <p>
     * 与 {@link #TELLER_DOC_LEVEL} 同一个理由：L2 的链路测试要断言
     * "事实的密级 = 该轮资料密级"，那就该引用同一个数，而不是在断言里再写一个 4。
     */
    protected static final int HIGH_DOC_LEVEL = 4;

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
    protected UserMemoryDao userMemoryDao;
    @Autowired
    protected SessionSummaryDao sessionSummaryDao;
    @Autowired
    protected MemoryChangeLogDao changeLogDao;
    @Autowired
    protected MemoryChangeLogVerifier changeLogVerifier;
    /**
     * 只用来给 L2 用例清场（{@link #clearFactsOf(String)}）。
     * <p>
     * 它<b>不是</b>绕过 DAO 去读数据 —— 断言一律走 DAO（见 {@link #activeFact(FactKey)} 的
     * 注释）。这里是"库里有别人上次留下的行，而 DAO 按设计不提供删除"的必然结果：
     * {@code user_memory} 是唯一一张<b>按用户</b>存在库里的表（L2 的用途就是跨会话），
     * 而 Mock IAM 只有两名员工，于是"上一次跑测试的用户"与"这一次的"是同一个人。
     */
    @Autowired
    protected JdbcTemplate jdbcTemplate;

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
    /**
     * 事实抽取的 seam。未 stub 时 Mockito 对 {@code List} 返回空表 ⇒
     * <b>既有的 L1 用例一行断言都不用改</b>，L2 只是"没抽到东西"。
     */
    @MockitoBean
    protected FactDistiller factDistiller;
    /**
     * 会话压缩的 seam。未 stub 时返回 {@code null} = "这份摘要不合格" ⇒ 不落库。
     * <p>
     * ⚠️ 别把"摘要里不许出现事实性数字"寄托在这个 mock 上：它是<b>被换掉的那一层</b>。
     * 那条约束的落库前终审在 {@code SessionSummaryService}，正是为了让链路测试
     * 打桩掉压缩器之后<b>仍然验得动</b>它（见 {@code SummaryContainsNumberChainTest}）。
     */
    @MockitoBean
    protected SummaryWriter summaryWriter;

    /**
     * 蒸馏/压缩的线程池，换成 mock 后由 {@link #openSession()} 搞成<b>同线程执行</b>。
     * <p>
     * 这样 {@code scheduleDistill} / {@code scheduleCompaction} 走的仍是真代码
     * （真的提交、真的在"另一个线程"上跑），但执行是确定的、同步的 ——
     * 断言不必 await，也不会出现"任务还没跑完就断言"的偶发失败。
     */
    @MockitoBean(name = "memoryDistillExecutor")
    protected ExecutorService distillExecutor;

    /** 依次收到的每一份 Prompt。JUnit 每个测试方法新建实例，所以不需要清理 */
    protected final List<Prompt> prompts = new ArrayList<>();

    protected CreateSessionResponse session;

    /** 当前会话属于谁。{@link #openSessionAs(String)} 会连会话带它一起换 */
    private String user = USER;

    /** 当前会话的用户 —— {@link #activeFact(FactKey)} 之类的断言辅助靠它对上归属 */
    protected String currentUser() {
        return user;
    }

    @BeforeEach
    void openSession() {
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            prompts.add(invocation.getArgument(0));
            return new ChatResponse(List.of(new Generation(new AssistantMessage(ANSWER))));
        });
        // 同线程执行：提交即执行（见 distillExecutor 的注释）
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(distillExecutor).execute(any(Runnable.class));
        session = auth.createSession(currentUser());
    }

    /**
     * 关掉当前会话、换一名员工重开一个，{@link #currentUser()} 跟着换。
     *
     * <h2>为什么是"按方法换"而不是"子类覆写 {@link #currentUser()}"</h2>
     * 这是本脚手架唯一为<b>隔离</b>而加的钩子（见类注释 ②）。做成按方法换，是因为
     * <b>继承来的三个 L1 用例也要在这个子类里跑</b>：它们用的 {@code highDoc()}
     * （密级 4、{@code kb-risk}、组织 {@code 0100/0202}）只有默认用户 {@link #USER}（10087）
     * 读得到。整类换成密级 2 的柜员之后，那些用例会被 {@code RetrievalAdvisor.passesGate}
     * 拦成拒答、模型根本不被调用，然后以"prompt 里没有上一轮"这种<b>看起来像记忆坏了</b>的
     * 方式失败。按方法换，L1 用例跑的就还是它们原本的那套实况。
     */
    protected void openSessionAs(String userId) {
        auth.closeSession(session.sessionId());
        user = userId;
        session = auth.createSession(userId);
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

    /** 高密级资料：密级 {@link #HIGH_DOC_LEVEL}，只有 {@link #USER}（10087）能过门禁 */
    protected static Document highDoc() {
        return doc("DOC-H", HIGH_DOC_LEVEL, "大额存单起存金额的相关规定。");
    }

    /** 低密级资料：密级 1，任何有密级的用户都读得到 */
    protected static Document lowDoc() {
        return doc("DOC-L", 1, "柜面取现授权的一般规定。");
    }

    /**
     * 柜员库的资料：密级 2、{@code kb-teller}、组织 {@code 0100/0101} ——
     * 三项都与 {@link #TELLER_USER} 的画像一致，所以只有它能过 10086 的门禁。
     * <p>
     * ⚠️ 密级 2 是刻意的：它也是 L2 事实的预期 {@code clearance_level}
     * （该轮引用资料的最高密级），于是"密级取自 {@code TurnFacts} 而不是重算"
     * 这条契约在链路测试里有一个可断言的数字。
     */
    protected static Document tellerDoc() {
        return new Document("柜面取现的授权规定。", Map.of(
                "kb_id", "kb-teller",
                "doc_level", TELLER_DOC_LEVEL,
                "org_codes", List.of("0100/0101"),
                "doc_id", "DOC-T",
                "doc_version", "v1"));
    }

    private static Document doc(String docId, int level, String text) {
        return new Document(text, Map.of(
                "kb_id", "kb-risk",
                "doc_level", level,
                "org_codes", List.of("0100/0202"),
                "doc_id", docId,
                "doc_version", "v1"));
    }

    // ------------------------------------------------------------------
    // 阶段 2 的断言辅助
    // ------------------------------------------------------------------

    /**
     * 取某个事实键<b>当前有效</b>的那一行（没有则 {@code null}）。归属用 {@link #currentUser()}，
     * 于是同一段断言在"换成柜员用户"的子类里仍然指向那个用户自己。
     * <p>
     * ⚠️ 它走的是 {@code selectActiveByKey}—— 那个方法的返回类型里<b>没有</b> {@code id}，
     * 且带 {@code FOR UPDATE}，所以断言拿到的就是写入路径看到的那一行。用别的查询（比如
     * 自己拼条件）就会绕开"读到的与写入看到的是同一条"这层保证。
     */
    protected UserMemoryRow activeFact(FactKey key) {
        return userMemoryDao.selectActiveByKey(currentUser(), FactScope.USER, key.code());
    }

    /** 审计链的当前快照（按 {@code id ASC}）。给"写入前/写入后"对照用 */
    protected List<MemoryChangeLogRow> chainSnapshot() {
        return changeLogDao.selectChain();
    }

    /**
     * 断言本次写入在审计链上<b>恰好留下 {@code expectedNewRows} 行，且这几行自身自洽</b>。
     * {@code expectedNewRows = 0} 就是"什么都没记"（例行的质量过滤不写审计）。
     *
     * <h2>⚠️ 为什么不能断言"全链校验为空"</h2>
     * 这个库里 {@code MemoryChangeLogVerifier.verifyChain()} <b>永远</b>返回一串不一致，
     * 而且是<b>设计使然</b>：{@code MemoryChangeLogDaoContextTest} 为了验列映射，
     * 会<b>故意</b>写进 {@code prev_hash}/{@code hash} 都是随机串的行（{@code "a".repeat(64)} 之类）。
     * 本表是全库共享的 append-only 表，那些行会一直留在链上 ⇒ 全链校验必然报一堆不一致。
     * <p>
     * 这不是"测试数据脏"这种可以忽略的瑕疵，它恰好演示了 {@code MemoryChangeLogVerifier}
     * 类注释里那条诚实边界：<b>全链校验的前提是"链上只有可信写入方"</b>，
     * 而本阶段连"应用层可以不记"都堵不住。想断言"链是干净的"，得先有独立授权的写入方。
     *
     * <h2>所以断言落在"本次新增的那一段"</h2>
     * {@link MemoryChangeLogRow} 的 {@code index} 是它在全链里的下标，所以
     * "本次新增"= 下标 ≥ 写入前的链长。断言这一段里<b>没有任何不一致项</b>，
     * 正是本次写入该负的两条责任：
     * <ol>
     *   <li>{@code PREV_HASH_NOT_LINKED} —— 我的行确实指向了当时那个链尾
     *       （{@code MemoryAuditAppender} 读到的 head hash 就是它）</li>
     *   <li>{@code HASH_MISMATCH} —— 我的行自身的哈希，用<b>读回来的列</b>重算仍然对得上
     *       （这条顺带把"JSON 列被 MySQL 规范化后哈希还能对上"也验了）</li>
     * </ol>
     * 前面那些行可能是坏的，但"上一行本身是坏的"不该由本次写入负责 ——
     * 而且链的连通性用的是<b>存着的</b> hash（见 {@code AuditHashChain.verify}），
     * 所以一个坏锚点也不会把好行连累成"断链"。
     *
     * @param before          写入<b>之前</b>取好的 {@link #chainSnapshot()}
     * @param expectedNewRows 本次写入应当留下的审计行数：INSERT 1 行、SUPERSEDE 2 行、什么都没记 0 行
     */
    protected void assertAppendedRows(List<MemoryChangeLogRow> before, int expectedNewRows) {
        List<MemoryChangeLogRow> after = chainSnapshot();
        assertThat(after.size() - before.size())
                .as("本次写入在审计链上留下的行数")
                .isEqualTo(expectedNewRows);
        if (expectedNewRows == 0) {
            return;
        }
        int firstNew = before.size();
        assertThat(changeLogVerifier.verifyChain())
                .as("本次追加的行必须接上前一条、且自身哈希重算一致")
                .noneMatch(mismatch -> mismatch.index() >= firstNew);
    }

    /**
     * 清掉某个用户当前的 L2 事实（<b>只清业务行，审计行不动</b>）。返回删掉几行。
     *
     * <h2>为什么链路的 L2 用例<b>必须</b>有这一步</h2>
     * L1 的历史按会话存，会话是随机 UUID ⇒ 天然隔离、跑多少次都一样。
     * L2 的事实<b>按用户存</b>（跨会话可见，这正是它的用途）—— 于是"上一次跑留下的那一行"
     * 会一直躺在库里。后果不只是"多了一行"：写入侧的规则是"同键同值 ⇒ REFRESH
     * （<b>不改 source_*</b>）"，所以第二次跑时 {@code row.sourceSessionId() == 本次会话}
     * 这条断言必然失败 —— 一个<b>只在第二次跑才出现</b>的失败，而"再跑一遍看看"
     * 恰恰是所有人遇到失败时的第一反应。
     * <p>
     * 删的只是业务行。{@code memory_change_log} 是 append-only 的，这里顺手说明了两张表
     * 在职责上是分开的：审计行指着的 {@code fact_uid} 消失了，但哈希链本身仍然自洽 ——
     * {@link MemoryChangeLogVerifier#verifyChain()} 校验的是链，不是外键。
     */
    protected int clearFactsOf(String userId) {
        return jdbcTemplate.update("DELETE FROM user_memory WHERE owner_id = ?", userId);
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
