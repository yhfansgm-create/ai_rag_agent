package org.agent.memory.distill;

import lombok.extern.slf4j.Slf4j;
import org.agent.memory.model.FactDraft;
import org.agent.memory.model.FactKey;
import org.agent.memory.model.RecalledFact;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * L2 事实抽取的 LLM 实现：一次 {@code .entity()} 调用 + 内联提示词。
 *
 * <h2>⚠️ 绝不复用被装配过的 {@code ChatClient} —— 这是本类最容易改坏的地方</h2>
 * 构造参数是 {@code @Qualifier("openAiChatModel") ChatModel}，在构造函数里
 * {@code ChatClient.builder(chatModel).build()}，<b>不调 {@code defaultAdvisors(...)}</b>。
 * <p>
 * 抄 {@code BankAssistant} 的写法去 {@code defaultAdvisors(permissionAdvisor, ...)}
 * 会产生一个<b>静默失败</b>：{@code PermissionAdvisor} 读不到 {@code PARAM_SESSION_ID}
 * 会 fail-closed 抛异常 → 异常被本类的 catch 兜住 → 表现是「L2 永远不写」，
 * 而日志里只有一条看起来像"模型抽不出东西"的 WARN。<b>没有报错，只是永远不工作</b>，
 * 而排查方向会完全错（去看提示词、看模型、看 grounding）。
 * <p>
 * 而且从设计上讲也不该复用：那条链上有权限判定、检索、数字校验 —— 它们都是
 * <b>面向"回答用户"这个目标</b>的。抽取是一次<b>内部工具调用</b>，与回答无关。
 *
 * <h2>为什么提示词是内联 text block，不抽到资源文件</h2>
 * <ul>
 *   <li>词表由 {@link FactKey#renderForPrompt} 渲染 —— <b>加一个枚举值就自动出现在提示词里</b>，
 *       不存在"忘了同步"这种状态。抽到 {@code .st} 文件就重新引入了漂移的可能</li>
 *   <li>仓库现在没有任何 {@code .st} 模板，为一个类引入一套模板机制不划算</li>
 * </ul>
 * 代价是改词表要重新编译。这个代价是<b>应当</b>付的：词表是安全边界，它变了本就该重新走一遍测试。
 *
 * <h2>⚠️ 解析失败 = <b>整份丢弃</b>，绝不用正则去抢救</h2>
 * {@code BeanOutputConverter.convert()} 抛异常时，本类返回<b>空列表</b>并打 WARN。
 * 不写"从散文里抠 JSON"的兜底逻辑：救回来的半截 JSON 一定比丢掉更危险 ——
 * 丢掉只是少记一条，救回来的是把一个<b>模型没说全的东西</b>当成事实写进未来所有轮的 prompt。
 */
@Slf4j
@Component
public class LlmFactDistiller implements FactDistiller {

    /**
     * 系统提示词。三个占位符由 {@link #buildSystemPrompt()} 用 {@code replace} 填。
     * <p>
     * ⚠️ 用 {@code replace("{AUTO}")} 而不是 {@code String.format}：词表是可变的，
     * 哪天有人在某个 {@code licenseTerms} 里加了一个 {@code %}，{@code format}
     * 会当场抛 {@code MissingFormatArgumentException} —— 而那时看到的错是
     * "抽取器启动失败"，没人会想到原因是词表里多了一个百分号。
     * <b>两个可变部分之间不该用格式串耦合。</b>
     */
    private static final String SYSTEM_PROMPT = """
            你是银行内部助手的事实抽取器。你的唯一职责是：判断「用户本轮的原话」里有没有
            值得长期记住的、关于**用户本人**的稳定信息。

            ## 三条硬规则（违反任何一条，整份结果都会被作废）

            1. 只能复述，不能推测。value 必须能在用户原话里逐字找到；
               不许概括、不许补全、不许换算单位、不许改写措辞。
            2. 提问不构成事实。用户问「大额存单利率多少」时，他没有表达任何偏好。
               只有当用户在**说自己**（例如「我主要做对公」「我常跑 XX 支行」）时才算。
            3. 只能用下面给出的 key。词表外的概念一律不要输出，哪怕它看起来很合理。

            ## 可以记录的 key（key（含义）；用户原话里会出现的线索词）

            {AUTO}

            ## 需要人工确认，本轮不要输出

            {CONFIRM}

            ## 永远不要输出

            {FORBIDDEN}
            这些是金额/比例/期限阈值。数字必须留在制度原文里 —— 被记住的数字在费率调整
            之后就是错的，而它还会被注入之后的每一轮对话。

            ## 输出

            只输出 JSON，不要任何解释文字。一条都没有时输出 {"facts":[]}。
            confidence 是你自己对这个判断的把握程度（0 到 1 之间），它不影响任何决定。
            """;

    private final ChatClient chatClient;

    public LlmFactDistiller(@Qualifier("openAiChatModel") ChatModel chatModel) {
        // ⚠️ 见类注释：这里**刻意**不调 defaultAdvisors(...)
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    /**
     * 抽一轮。
     * <p>
     * ⚠️ 每个失败分支都返回空列表并打 WARN，<b>绝不外抛</b> —— 契约见 {@link FactDistiller}。
     * 日志里刻意<b>不带用户原话</b>：日志不该成为新的泄露面（同 {@code FactRedlines}
     * "绝不返回命中原值"的立场）。
     */
    @Override
    public List<FactDraft> distill(DistillRequest request) {
        if (request == null || isBlank(request.userMessage())) {
            return List.of();
        }
        try {
            DistillResult result = chatClient.prompt()
                    .system(buildSystemPrompt())
                    .user(buildUserPrompt(request))
                    .call()
                    .entity(DistillResult.class);

            if (result == null) {
                log.warn("L2 抽取：模型返回了空对象，本轮无候选");
                return List.of();
            }
            return toDrafts(result);
        } catch (Exception e) {
            // 模型调用失败与解析失败走同一个出口：对调用方而言都是"这一轮没抽出事实"。
            // 但必须留下 WARN —— 记忆的失败可以接受，记忆的失败**不可见**不可以
            log.warn("L2 抽取失败，本轮跳过（不影响回答）", e);
            return List.of();
        }
    }

    /**
     * 候选 → {@code FactDraft}。
     * <p>
     * <b>不做任何过滤或校验</b>：空 key、空 value、不认识的 key 全部原样交给
     * {@code FactWritePolicy}，由它给出具名拒绝理由（{@code VALUE_EMPTY} /
     * {@code UNKNOWN_KEY}）并进审计。在这里顺手滤掉，那些候选就永远不会出现在审计里。
     */
    private static List<FactDraft> toDrafts(DistillResult result) {
        List<FactDraft> drafts = new ArrayList<>();
        for (DistillResult.Candidate candidate : result.candidates()) {
            drafts.add(toDraft(candidate));
        }
        return List.copyOf(drafts);
    }

    /**
     * ⚠️ {@code candidate} 可能<b>是 null</b>（模型输出 {@code {"facts":[null]}}）。
     * 不跳过它，而是造一条 key/value 全空的 draft —— 于是它会得到
     * {@code UNKNOWN_KEY} 这条具名理由，在审计里看得见"模型回了一个 null"。
     * 静默跳过的话，一次真实的模型异常输出就不会留下任何痕迹。
     */
    private static FactDraft toDraft(DistillResult.Candidate candidate) {
        if (candidate == null) {
            return new FactDraft(null, null, null);
        }
        return new FactDraft(candidate.key(), candidate.value(),
                toConfidence(candidate.confidence()));
    }

    /**
     * 置信度转换。
     * <p>
     * ⚠️ 挡 NaN / Infinity 是<b>第二道防线</b>，{@code BigDecimal.valueOf(Double.NaN)}
     * 会抛 {@code NumberFormatException}，而它会把整份结果拖垮。
     * <p>
     * ⚠️ 但要说实话：<b>当前配置下这条路到不了</b>。Spring AI 的
     * {@code BeanOutputConverter} 用的 ObjectMapper 拒绝 {@code NaN} 字面量，
     * 所以含 NaN 的输出在解析阶段就已经整份失败了（有测试锁住这个实际行为）。
     * 保留这道兜底的理由是：那取决于<b>第三方 mapper 的配置</b>，一次依赖升级就可能变；
     * 而这里失守的代价是"一个不参与判定的字段让整份结果作废"——
     * 一个便宜的 if 换掉一个难查的失败模式。
     */
    private static BigDecimal toConfidence(Double confidence) {
        if (confidence == null || confidence.isNaN() || confidence.isInfinite()) {
            return null;
        }
        return BigDecimal.valueOf(confidence);
    }

    /** 词表渲染。三个档位分别渲染 —— 把 CONFIRM/FORBIDDEN 显式列成"不要输出"，比缺席更能约束模型 */
    static String buildSystemPrompt() {
        return SYSTEM_PROMPT
                .replace("{AUTO}", FactKey.renderForPrompt(FactKey.Tier.AUTO).strip())
                .replace("{CONFIRM}", FactKey.renderForPrompt(FactKey.Tier.CONFIRM).strip())
                .replace("{FORBIDDEN}", FactKey.renderForPrompt(FactKey.Tier.FORBIDDEN).strip());
    }

    /**
     * 用户消息：业务日期 + 已知事实 + 本轮原话。
     * <p>
     * ⚠️ 这里<b>没有</b>历史、<b>没有</b>检索原文、<b>没有</b>答案 —— 见 {@link DistillRequest}。
     * 有一条单测（{@code LlmFactDistillerTest}）锁住这一点：它断言提示词里
     * 不含答案文本、也不含检索层那个 {@code 【请仅基于下列授权资料作答】} 前缀。
     * 那既证明"蒸馏没走 advisor 链"，也证明"答案没有进入抽取输入"。
     * <p>
     * ⚠️ 已知事实进了提示词，这是<b>第二个注入面</b>（第一个是用户原话）。
     * 之所以可以接受：它们的值最终仍要过 {@code FactGrounding}（必须在用户原话里
     * 找到要素），一段被污染的已知事实不可能凭自己变成一条新记忆。
     */
    static String buildUserPrompt(DistillRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("业务日期：").append(request.bizDate() == null ? "未指定" : request.bizDate()).append('\n');

        List<RecalledFact> known = request.knownFacts();
        if (known.isEmpty()) {
            sb.append("已知事实：（无）\n");
        } else {
            sb.append("已知事实（这些已经记住了，不要重复输出）：\n");
            for (RecalledFact fact : known) {
                sb.append("- ").append(fact.renderLine()).append('\n');
            }
        }

        sb.append("用户本轮原话（这是你唯一的事实来源）：\n");
        sb.append(request.userMessage());
        return sb.toString();
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /**
     * 检索层的增强前缀。放在这里是为了让测试与实现共用同一个字面量 ——
     * 断言"蒸馏的提示词里不该出现检索原文"时，若两边各写一份字符串，
     * 改了提示词而没改测试，那条断言会<b>永远通过</b>（一个假通过）。
     */
    static final String RETRIEVAL_AUGMENT_PREFIX = "【请仅基于下列授权资料作答】";
}
