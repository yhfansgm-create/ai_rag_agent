package org.agent.memory;

import org.agent.memory.model.AnswerDecision;
import org.agent.memory.model.CitedChunkRef;
import org.agent.memory.model.TurnFacts;
import org.agent.permission.SessionEnvelope;
import org.agent.rag.guard.NumericGuardrailAdvisor;
import org.agent.rag.retrieve.RetrievalAdvisor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯测试：本次改动的安全约束就落在这个类里，所以断言要盯着"存下去的是什么"。
 */
@DisplayName("TurnExtractor：从一次问答里抽出待落库的事实")
class TurnExtractorTest {

    private static final String QUESTION = "大额存单的起存金额是多少？";

    @Test
    @DisplayName("放行轮：PROCEED、无拒答原因、引文指针非空")
    void proceedTurn() {
        TurnFacts facts = TurnExtractor.extract(
                request(context(), QUESTION),
                envelope(),
                response("起存金额为 20 万元[1]。", context(
                        RetrievalAdvisor.CONTEXT_RETRIEVED_DOCS, List.of(
                                doc("chunk-a", "20 万元起存", "DOC-1", "v2")))));

        assertThat(facts.decision()).isEqualTo(AnswerDecision.PROCEED);
        assertThat(facts.refusalReason()).isNull();
        assertThat(facts.answerText()).isEqualTo("起存金额为 20 万元[1]。");
        assertThat(facts.userMessage()).isEqualTo(QUESTION);
        assertThat(facts.citedRefs()).containsExactly(new CitedChunkRef("DOC-1", "v2", "chunk-a"));
    }

    @Test
    @DisplayName("归属与审计字段取自本轮信封")
    void identityFieldsComeFromEnvelope() {
        TurnFacts facts = TurnExtractor.extract(request(context(), QUESTION), envelope(),
                response("答案", context()));

        assertThat(facts.sessionId()).isEqualTo("sess-1");
        assertThat(facts.userId()).isEqualTo("10086");
        assertThat(facts.authVersion()).isEqualTo(7L);
    }

    @Test
    @DisplayName("检索拒答轮（TEMPORAL_FILTERED）：REFUSED + 理由透传 + 无引文")
    void retrievalRefusalTurn() {
        TurnFacts facts = TurnExtractor.extract(request(context(), QUESTION), envelope(),
                response("未检索到当时有效的资料。", context(
                        RetrievalAdvisor.CONTEXT_REFUSAL_REASON, "TEMPORAL_FILTERED")));

        assertThat(facts.decision()).isEqualTo(AnswerDecision.REFUSED);
        assertThat(facts.refusalReason()).isEqualTo("TEMPORAL_FILTERED");
        assertThat(facts.citedRefs()).isEmpty();
    }

    @Test
    @DisplayName("数字校验拒答轮（UNVERIFIED_NUMBER）：与检索拒答同一口径")
    void numericGuardrailRefusalFallsInSameBucket() {
        // NumericGuardrailAdvisor 写的是同一个 context 键，所以这里一处读取就覆盖两种拒答源
        TurnFacts facts = TurnExtractor.extract(request(context(), QUESTION), envelope(),
                response("被拦下的答案", context(
                        RetrievalAdvisor.CONTEXT_REFUSAL_REASON,
                        NumericGuardrailAdvisor.REFUSAL_UNVERIFIED_NUMBER,
                        RetrievalAdvisor.CONTEXT_RETRIEVED_DOCS,
                        List.of(doc("chunk-a", "原文", "DOC-1", "v2")))));

        assertThat(facts.decision()).isEqualTo(AnswerDecision.REFUSED);
        assertThat(facts.refusalReason()).isEqualTo(NumericGuardrailAdvisor.REFUSAL_UNVERIFIED_NUMBER);
    }

    @Test
    @DisplayName("检索结果与拒答原因从 response 的 context 读，不是从 request 读")
    void factsAreReadFromResponseContext() {
        // 模拟真实链路的形状：检索写进的是"复制后"的那份 context（mutate 的产物），
        // 所以原始 request 的 context 里没有这两个键。只有 response.context() 是可靠的。
        TurnFacts facts = TurnExtractor.extract(request(context(), QUESTION), envelope(),
                response("答案", context(
                        RetrievalAdvisor.CONTEXT_REFUSAL_REASON, "NO_HIT",
                        RetrievalAdvisor.CONTEXT_RETRIEVED_DOCS,
                        List.of(doc("chunk-a", "原文", "DOC-1", "v2")))));

        assertThat(facts.decision()).isEqualTo(AnswerDecision.REFUSED);
        assertThat(facts.citedRefs()).hasSize(1);
    }

    @Test
    @DisplayName("引文指针：doc_id/doc_version 取自 metadata，chunk_id 取 Document.getId()")
    void citedRefFieldsComeFromMetadata() {
        TurnFacts facts = TurnExtractor.extract(request(context(), QUESTION), envelope(),
                response("答案", context(RetrievalAdvisor.CONTEXT_RETRIEVED_DOCS, List.of(
                        doc("chunk-xyz", "正文", "DOC-9", "v11")))));

        assertThat(facts.citedRefs()).containsExactly(new CitedChunkRef("DOC-9", "v11", "chunk-xyz"));
    }

    @Test
    @DisplayName("缺 doc_id 的文档整条跳过（无指针可用，宁缺毋滥）")
    void documentWithoutDocIdIsSkipped() {
        Map<String, Object> noDocId = new HashMap<>();
        noDocId.put("doc_version", "v1");
        TurnFacts facts = TurnExtractor.extract(request(context(), QUESTION), envelope(),
                response("答案", context(RetrievalAdvisor.CONTEXT_RETRIEVED_DOCS, List.of(
                        new Document("chunk-a", "没有 doc_id 的片段", noDocId),
                        doc("chunk-b", "正文", "DOC-2", "v1")))));

        assertThat(facts.citedRefs()).containsExactly(new CitedChunkRef("DOC-2", "v1", "chunk-b"));
    }

    @Test
    @DisplayName("doc_version 缺失时指针仍然保留（只是该字段为 null）")
    void missingDocVersionKeepsRef() {
        TurnFacts facts = TurnExtractor.extract(request(context(), QUESTION), envelope(),
                response("答案", context(RetrievalAdvisor.CONTEXT_RETRIEVED_DOCS, List.of(
                        new Document("chunk-a", "正文", Map.of("doc_id", "DOC-3"))))));

        assertThat(facts.citedRefs()).containsExactly(new CitedChunkRef("DOC-3", null, "chunk-a"));
    }

    @Test
    @DisplayName("biz_date：未传时为今天，传了则取传入值（口径复用 RetrievalAdvisor）")
    void bizDateFollowsRetrievalAdvisor() {
        TurnFacts today = TurnExtractor.extract(request(context(), QUESTION), envelope(),
                response("答案", context()));
        assertThat(today.bizDate()).isEqualTo(LocalDate.now());

        Map<String, Object> ctx = context(RetrievalAdvisor.PARAM_BIZ_DATE, "2026-03-01");
        TurnFacts explicit = TurnExtractor.extract(request(ctx, QUESTION), envelope(),
                response("答案", context()));
        assertThat(explicit.bizDate()).isEqualTo(LocalDate.of(2026, 3, 1));
    }

    @Test
    @DisplayName("clearance_level：有资料取资料密级的 max（可低于信封密级），无资料回退信封密级")
    void clearanceLevelFollowsCitedDocs() {
        // 无资料 → 回退本轮信封密级（此例信封为 2）
        TurnFacts noDocs = TurnExtractor.extract(request(context(), QUESTION), envelope(),
                response("答案", context()));
        assertThat(noDocs.clearanceLevel()).isEqualTo(2);

        // 有资料 → 走"取 max"那条分支。密级 1 ≠ 回退值 2，才能证明真的读了资料，
        // 而不是碰巧回退到同一个数
        TurnFacts lowOnly = TurnExtractor.extract(request(context(), QUESTION), envelope(),
                response("答案", context(RetrievalAdvisor.CONTEXT_RETRIEVED_DOCS,
                        List.of(docWithLevel("chunk-a", 1), docWithLevel("chunk-b", 1)))));
        assertThat(lowOnly.clearanceLevel()).isEqualTo(1);

        // 1 与 2 混在一起 → 2。取 min 的话这里会是 1（那正是 D2 洗密级通道的方向）
        TurnFacts mixed = TurnExtractor.extract(request(context(), QUESTION), envelope(),
                response("答案", context(RetrievalAdvisor.CONTEXT_RETRIEVED_DOCS,
                        List.of(docWithLevel("chunk-a", 1), docWithLevel("chunk-b", 2)))));
        assertThat(mixed.clearanceLevel()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // 安全断言：本功能最核心的一条
    // ------------------------------------------------------------------

    @Test
    @DisplayName("安全：落库的 user_message 不含 augment() 的增强前缀，也没有检索原文")
    void storedUserMessageIsNeverAugmented() {
        // 原始 request 上是用户原话（本 advisor 的 order 早于检索，所以检索还没动手）
        ChatClientRequest original = request(context(), QUESTION);

        // 检索之后的那一份就不一样了：augment() 把用户消息换成了"原问题 + 约束 + 带编号的资料"
        String augmented = QUESTION + "\n\n【请仅基于下列授权资料作答，禁止编造。】\n[1] 起存金额 20 万元……";
        ChatClientRequest afterRetrieval = request(context(), augmented);

        TurnFacts fromOriginal = TurnExtractor.extract(original, envelope(),
                response("答案", context()));
        TurnFacts fromAugmented = TurnExtractor.extract(afterRetrieval, envelope(),
                response("答案", context()));

        // 抽的是前者：只有用户原话
        assertThat(fromOriginal.userMessage()).isEqualTo(QUESTION);
        assertThat(fromOriginal.userMessage()).doesNotContain("请仅基于下列授权资料作答");
        // 对照组：若把增强后的那一份传进来，检索原文就进历史了 —— 这正是 order 不能后移的原因
        assertThat(fromAugmented.userMessage()).contains("起存金额 20 万元");
    }

    @Test
    @DisplayName("answerText 各层为 null 时不抛异常")
    void nullAnswerIsTolerated() {
        TurnFacts facts = TurnExtractor.extract(request(context(), QUESTION), envelope(),
                new ChatClientResponse(null, new HashMap<>()));

        assertThat(facts.answerText()).isNull();
        assertThat(facts.decision()).isEqualTo(AnswerDecision.PROCEED);
    }

    // ------------------------------------------------------------------
    // 测试数据构造
    // ------------------------------------------------------------------

    private static Map<String, Object> context(Object... kv) {
        Map<String, Object> ctx = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            ctx.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return ctx;
    }

    private static ChatClientRequest request(Map<String, Object> ctx, String userText) {
        return new ChatClientRequest(new Prompt(List.of(
                new SystemMessage("你是银行内部助手。"), new UserMessage(userText))), ctx);
    }

    private static ChatClientResponse response(String text, Map<String, Object> ctx) {
        return new ChatClientResponse(
                new ChatResponse(List.of(new Generation(new AssistantMessage(text)))), ctx);
    }

    private static Document doc(String chunkId, String text, String docId, String version) {
        return new Document(chunkId, text, Map.of("doc_id", docId, "doc_version", version));
    }

    /** 带 {@code doc_level} 的资料，用于密级断言 */
    private static Document docWithLevel(String chunkId, int level) {
        return new Document(chunkId, "正文",
                Map.of("doc_id", "DOC-" + chunkId, "doc_version", "v1", "doc_level", level));
    }

    private static SessionEnvelope envelope() {
        return new SessionEnvelope("sess-1", "10086", "TELLER", "P3", "0101", "0100/0101",
                2, List.of("kb-teller"), 7L, "MOCK",
                LocalDateTime.now().plusHours(1), "kb_id in ['kb-teller']");
    }
}
