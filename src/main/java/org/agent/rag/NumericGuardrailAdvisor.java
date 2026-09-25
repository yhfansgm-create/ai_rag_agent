package org.agent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 输出侧 Guardrails：数字一致性校验（后端防线）。
 * <p>
 * 检索层保证"模型只看到授权资料"，本 Advisor 保证"模型吐出来的数字确实来自那些资料"。
 * 答案里的金额／比例／期限只要有一处无法在检索到的原文中核对，就整个拒答 —— 不经过模型，
 * 直接本地构造响应，与 {@link RetrievalAdvisor} 的四种拒答同一套机制。
 *
 * <h2>为什么是独立 Advisor，而不是塞进 RetrievalAdvisor.auditCitations</h2>
 * <ul>
 *   <li>{@code auditCitations} 只检测打标、**不重写答案**（重写会丢 token 用量元数据）。
 *       本功能需要把响应整个换掉，语义不同</li>
 *   <li>输出侧校验与检索是两件事：不依赖向量库即可复用到别的 ChatClient</li>
 *   <li>独立后 order 语义看得见：本 Advisor 排在 {@link RetrievalAdvisor} 之后（值更大=更靠内），
 *       既拿得到它写进 context 的检索结果，返回时又让 {@code auditCitations} 在外层收尾</li>
 * </ul>
 *
 * <h2>取不到检索结果时跳过校验（fail-open）</h2>
 * 这条**和权限门禁的 fail-closed 相反**，是刻意的：
 * <ul>
 *   <li>没有检索结果就没有可比对的原文，此时"校验"没有判据，拦下来等于凭空拒答</li>
 *   <li>权限放行的代价是越权泄露（不可逆），数字校验放行的代价是一次未核对的回答（可事后审计）。
 *       两者风险不对称，所以策略也不该对称</li>
 *   <li>实际上"跑了检索但没有这个 key"不会发生：RetrievalAdvisor 拒答时根本不调
 *       {@code chain.next*}，所以进到本 Advisor 就一定是检索通过、key 已写入</li>
 * </ul>
 */
@Slf4j
@Component
public class NumericGuardrailAdvisor implements CallAdvisor, StreamAdvisor {

    /** 未通过核对的数值描述列表；非空表示答案被拦，需人工复核 */
    public static final String CONTEXT_NUMERIC_VIOLATIONS = "org.agent.rag.numericViolations";

    public static final String REFUSAL_UNVERIFIED_NUMBER = "UNVERIFIED_NUMBER";

    /**
     * 排在 RetrievalAdvisor（order=0）之后。
     * <p>
     * Advisor 的执行顺序是"值小者在外层"，越靠内越晚拿到请求、越早拿到响应。
     * 本 Advisor 必须在检索之后才能读到 context 里的原文，故取正数。
     */
    public static final int ORDER = 10;

    private static final String TEXT_UNVERIFIED_NUMBER_TEMPLATE =
            "回答中的 %d 处数值（金额／比例／期限）无法在知识库原文中核对，为避免误导，本次不作答。"
                    + "请查阅制度原文，或换个问法。";

    private final NumericConsistencyChecker checker;
    private final boolean enabled;

    @Autowired
    public NumericGuardrailAdvisor(NumericConsistencyChecker checker,
                                   @Value("${rag.guardrails.numeric.enabled:true}") boolean enabled) {
        this.checker = checker;
        this.enabled = enabled;
    }

    /** 测试便捷构造：默认开启 */
    public NumericGuardrailAdvisor() {
        this(new NumericConsistencyChecker(), true);
    }

    /** 测试便捷构造：可指定开关，用于验证"关掉后退化为原行为" */
    public NumericGuardrailAdvisor(boolean enabled) {
        this(new NumericConsistencyChecker(), enabled);
    }

    @Override
    public String getName() {
        return "NumericGuardrailAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        if (!enabled) {
            return response;
        }
        List<String> sources = retrievedTexts(request);
        if (sources == null) {
            return response;
        }
        NumericConsistencyChecker.Result result = checker.check(answerText(response), sources);
        return result.consistent() ? response : refuse(request, result);
    }

    /**
     * 流式路径：先缓冲全部帧，合并出完整答案再校验，通过才原样回放。
     * <p>
     * 代价是失去打字机效果 —— 用户要等生成结束才见字。这是本轮明确选择的取舍：
     * 流式下逐帧吐字时，等发现数字有问题，字早就到用户屏幕上了，拦也来不及。
     * 与 {@link RetrievalAdvisor} 拒答在流式下"退化成单帧整段返回"是同一个思路。
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        if (!enabled) {
            return chain.nextStream(request);
        }
        List<String> sources = retrievedTexts(request);
        if (sources == null) {
            return chain.nextStream(request);
        }
        return chain.nextStream(request)
                .collectList()
                .flatMapMany(frames -> {
                    String answer = frames.stream()
                            .map(NumericGuardrailAdvisor::answerText)
                            .filter(Objects::nonNull)
                            .collect(Collectors.joining());
                    NumericConsistencyChecker.Result result = checker.check(answer, sources);
                    return result.consistent()
                            ? Flux.fromIterable(frames)
                            : Flux.just(refuse(request, result));
                });
    }

    /**
     * 取出本次注入 prompt 的原文片段。
     *
     * @return 无检索上下文时返回 null，调用方据此跳过校验
     */
    private static List<String> retrievedTexts(ChatClientRequest request) {
        Object raw = request.context().get(RetrievalAdvisor.CONTEXT_RETRIEVED_DOCS);
        if (!(raw instanceof List<?> docs) || docs.isEmpty()) {
            return null;
        }
        List<String> texts = new ArrayList<>(docs.size());
        for (Object o : docs) {
            if (o instanceof Document d && d.getText() != null) {
                texts.add(d.getText());
            }
        }
        return texts.isEmpty() ? null : texts;
    }

    /**
     * 本地构造拒答响应，不经过模型。
     * <p>
     * 用户可见文案**只给条数、不回显具体数字**：把模型编造的数值再念一遍给柜员看，
     * 反而可能让那个错误数字被记住。具体是哪个数字写日志、进 context 供审计。
     */
    private ChatClientResponse refuse(ChatClientRequest request, NumericConsistencyChecker.Result result) {
        List<String> descriptions = result.descriptions();
        request.context().put(CONTEXT_NUMERIC_VIOLATIONS, descriptions);
        request.context().put(RetrievalAdvisor.CONTEXT_REFUSAL_REASON, REFUSAL_UNVERIFIED_NUMBER);
        log.warn("数字一致性校验未通过，拦截回答（共提取 {} 处数值）：{}",
                result.checked(), descriptions);

        ChatResponse resp = new ChatResponse(List.of(
                new Generation(new AssistantMessage(refusalText(descriptions.size())))));
        return new ChatClientResponse(resp, request.context());
    }

    static String refusalText(int count) {
        return String.format(TEXT_UNVERIFIED_NUMBER_TEMPLATE, count);
    }

    /** 从响应里取助手文本，各层都可能为 null（与 RetrievalAdvisor.answerText 同样的判空） */
    private static String answerText(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null
                || response.chatResponse().getResult() == null) {
            return null;
        }
        var output = response.chatResponse().getResult().getOutput();
        return output == null ? null : output.getText();
    }
}
