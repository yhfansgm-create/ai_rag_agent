package org.agent.memory.distill;

import lombok.extern.slf4j.Slf4j;
import org.agent.rag.guard.NumericExtractor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 会话压缩的 LLM 实现：内联提示词 + <b>Java 侧的数字检查</b>。
 *
 * <h2>这个类的存在理由：把 RAG-07 附录 A1 的「已知偏差」变成一行可执行断言</h2>
 * A1 把「把总结（摘要）当成解法」列为已知偏差，原文：
 * <blockquote>
 *   摘要<b>有损，且破坏引用归因</b>。上一轮「年利率 1.35%」被摘要成「讨论了存款利率」，
 *   下一轮用户问「刚才那个利率」，模型要么答不出，要么编一个被
 *   {@code NumericGuardrailAdvisor} 拦掉。
 * </blockquote>
 * 校正结论是：<b>摘要只能用来定位，不能用来承载事实；数字按指针回原文取。</b>
 * <p>
 * 而"在提示词里禁止模型记录数字"只是<b>一句话</b>。这个类把那句话变成机制：
 * 产出必须过 {@link NumericExtractor}，命中金额/比例/期限就打回重写一次，
 * 仍不干净就<b>整份丢弃</b>（返回 {@code null}）。
 * 全链路测试 {@code SummaryContainsNumberChainTest} 打桩一句带数字的摘要，
 * 断言它既没落库、也没出现在下一轮的 prompt 里 —— 模型不听话时，闸门仍然在。
 *
 * <h2>⚠️ 为什么复用 {@code NumericExtractor}，而不是在这里另写一个正则</h2>
 * <ol>
 *   <li>它的三类<b>正是</b> A1 说的那三类（金额/比例/期限），不多不少</li>
 *   <li>它的白名单设计已经排除了文号、条款号、计数、序号、日期 ——
 *       所以「讨论了《XX 办法》（银保监发〔2019〕23 号）」<b>不会误报</b>。
 *       这一点很重要：误报会让正常摘要被反复丢弃，而这个功能一旦"经常没有摘要"，
 *       就会被当成没做好而关掉</li>
 *   <li><b>"什么算事实性数字"的判定只该有一个出口。</b> 这句话在本项目已经出现过两次
 *       （输出侧答案校验、现在的摘要校验），两处判据不一致是最坏的结果 ——
 *       会出现"答案里合规的数字，摘录它却违规"这种没人解释得通的组合</li>
 * </ol>
 * ⚠️ 它是 {@link NumericExtractor} 的<b>第二个消费方</b>：从"校验答案"扩到"校验摘要"，
 * 是同一套引用归因方法论的平移，而不是新机制。
 * <p>
 * 这里用 {@code new NumericExtractor()} 而不是注入：它无状态、纯函数，
 * 与 {@code NumericConsistencyChecker} 里的用法一致 —— 于是本类在单测里
 * <b>不需要 Spring</b>，也就不需要一个"其实什么都没测"的 {@code @SpringBootTest}。
 *
 * <h2>⚠️ 绝不复用被装配过的 {@code ChatClient}</h2>
 * 同 {@link LlmFactDistiller}：构造里 {@code ChatClient.builder(chatModel).build()}，
 * 不调 {@code defaultAdvisors(...)}。复用会重入 advisor 链，
 * {@code PermissionAdvisor} 读不到 sessionId 即 fail-closed 抛异常 →
 * 被 catch 兜住 → 表现为「摘要永远没有」，且<b>没有报错</b>。
 */
@Slf4j
@Component
public class LlmSummaryWriter implements SummaryWriter {

    /**
     * 最多问两次：一次原始请求，一次打回重写。
     * <p>
     * 为什么是 2 而不是 3：第二次仍违规说明<b>这个模型在当前提示下就是要写数字</b>，
     * 再问只是把同一件事重试，成本（一次 LLM 往返）换不到新的信息。
     * 而"宁可没有摘要"是本层已经接受的取舍（{@code SummaryWriter} 契约），
     * 所以上限低一点是<b>正确的</b>，不是省钱。
     */
    private static final int MAX_ATTEMPTS = 2;

    /**
     * 系统提示词。
     * <p>
     * ⚠️ "不许记录数字"必须放在<b>最前面</b>并显式举例。把禁令埋在第三段之后，
     * 模型会把"摘要要准确"这个更一般的直觉排在它前面 —— 而这个直觉恰恰是我们要压制的。
     */
    private static final String SYSTEM_PROMPT = """
            你是会话摘要器。把给定的历史轮次压缩成一份只用于「定位」的摘要，
            供后续对话判断「用户在说哪一段」。

            ## 最重要的禁令：绝对不要记录任何金额、比例、期限数字

            摘要里出现「1.35%」「20 万元」「3 个工作日」都是错误 —— 哪怕原文里就有。
            原因：数字一旦被写进摘要，它就会在之后的每一轮里以错误的形式传播
            （费率、起存额、期限随时会调整），而用户无法察觉。数字必须回到制度原文去取。

            要表达时只写**主题**：
              正确：「讨论了《大额存单管理办法》的起存金额规定」
              错误：「讨论了《大额存单管理办法》里 20 万元的起存金额」
            制度名称、文号（如 银保监发〔2019〕23 号）、条款号都是允许的，它们不是金额。

            ## 其他要求

            - 只记录主题与术语：聊了哪份制度、哪个业务、哪个概念
            - 不写结论、不写建议、不复制答复内容
            - 用户提出但未获答复的问题，写成「用户询问了 XX（未获答复）」
            - 只输出摘要正文，不要任何解释、标题或列表符号
            - 写成一段连续的文字，不要换行
            """;

    private final ChatClient chatClient;
    private final NumericExtractor numericExtractor;

    public LlmSummaryWriter(@Qualifier("openAiChatModel") ChatModel chatModel) {
        // ⚠️ 见类注释：这里**刻意**不调 defaultAdvisors(...)
        this.chatClient = ChatClient.builder(chatModel).build();
        this.numericExtractor = new NumericExtractor();
    }

    /**
     * 压缩一次，最多两轮。
     *
     * @return 合格的摘要文本；{@code null} = <b>放弃这次压缩</b>
     *         （模型失败 / 两次都违反禁令 / 两次都超长）
     */
    @Override
    public String summarize(CompactionRequest request) {
        if (request == null || request.sourceTurns().isEmpty()) {
            // 没有要折叠的轮次 ⇒ 不产出摘要。返回 null 而不是空串：
            // 空摘要在下游是一个"覆盖了 0 轮的有效摘要"，那会让覆盖区间失去意义
            return null;
        }

        String feedback = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String answer = ask(request, feedback);
            if (answer == null) {
                // 模型这一轮失败 ⇒ 直接放弃，不重试。
                // 重试"模型挂了"是网络层该做的事（超时/退避），不是业务层的循环该做的事 ——
                // 在这里重试会把一次失败变成两次失败，还拖长异步任务占着线程的时间
                log.warn("会话压缩：模型未返回内容，本次压缩放弃（不影响本轮回答）");
                return null;
            }

            String text = singleLine(answer);
            if (text.isEmpty()) {
                feedback = "你上一次的输出是空的。";
                continue;
            }
            if (request.maxChars() > 0 && text.length() > request.maxChars()) {
                // ⚠️ 这里是**打回重写**而不是截断。理由与 MemoryBlockRenderer 一致：
                // 一句被腰斩的「用户询问了《XX 办法》的…」比没有摘要更糟 —— 它是一条
                // 看起来完整、实际含义已经变了的定位信息
                log.warn("会话压缩：输出超长（{} 字符 > 上限 {}），打回重写。第 {} 次",
                        text.length(), request.maxChars(), attempt);
                feedback = "你上一次的输出太长（超过 " + request.maxChars() + " 字）。请压缩到该长度以内。";
                continue;
            }

            List<NumericExtractor.Mention> mentions = numericExtractor.extract(text);
            if (!mentions.isEmpty()) {
                log.warn("会话压缩：输出里出现了事实性数字，打回重写。命中类别={}，第 {} 次",
                        dimensionsOf(mentions), attempt);
                feedback = "你上一次的输出里出现了" + dimensionsOf(mentions)
                        + "。这是绝对禁止的：不要写任何金额、比例、期限数字，"
                        + "只写主题。请重写。";
                continue;
            }
            return text;
        }

        // 两次都没过 —— 宁可让窗口滑走，也不让一份违规的摘要进入未来所有轮次的上下文
        log.warn("会话压缩：连续 {} 次输出不合格，本次摘要丢弃（窗口内容将不再有摘要覆盖）", MAX_ATTEMPTS);
        return null;
    }

    /**
     * 问一次模型。
     * <p>
     * ⚠️ 失败返回 {@code null}（不外抛）—— 契约见 {@link SummaryWriter}。
     */
    private String ask(CompactionRequest request, String feedback) {
        try {
            return chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(buildUserPrompt(request, feedback))
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("会话压缩：模型调用失败（不影响本轮回答）", e);
            return null;
        }
    }

    /**
     * 用户消息：已有摘要 + 新增轮次 +（重写时）上一版不合格的说明。
     * <p>
     * ⚠️ 重写说明里<b>只给类别，不给命中的原值</b>。
     * "把那个数字告诉模型让它避开"听起来更有效，但那等于把敏感数值又抄了一遍到
     * 请求体里 —— 而这个请求会经第三方 API。这与 {@code FactRedlines}
     * "绝不返回命中原值"、以及审计里 {@code GUARD_REJECT} 只放
     * {@code {factKey, pattern, valueLength}} 是同一个立场：
     * <b>复核链路上的每一处都不该成为新的泄露面。</b>
     * 实测类别提示（"金额/比例/期限"）已经足够让模型改掉。
     */
    static String buildUserPrompt(CompactionRequest request, String feedback) {
        StringBuilder sb = new StringBuilder();
        sb.append("已有摘要（若为空，说明这是第一版）：\n")
                .append(request.previousSummary() == null ? "（无）" : request.previousSummary())
                .append("\n\n新增轮次（按轮号升序）：\n");
        for (CompactionRequest.SourceTurn turn : request.sourceTurns()) {
            sb.append(turn.renderForPrompt()).append('\n');
        }
        sb.append("\n请输出一份覆盖上面全部内容的摘要，不超过 ")
                .append(request.maxChars()).append(" 字。");
        if (feedback != null) {
            sb.append("\n\n注意：").append(feedback);
        }
        return sb.toString();
    }

    /**
     * 命中类别的可读名称。
     * <p>
     * <b>只返回类别，不返回命中片段</b> —— 见 {@link #buildUserPrompt} 的说明。
     * 输出形如 {@code 金额/比例}，按首次出现的顺序去重：顺序稳定，
     * 日志里两次 WARN 才好比对（不稳定的顺序会让"是不是同一件事"变得不好判断）。
     */
    private static String dimensionsOf(List<NumericExtractor.Mention> mentions) {
        StringBuilder sb = new StringBuilder();
        for (NumericExtractor.Mention mention : mentions) {
            String name = switch (mention.dimension()) {
                case MONEY -> "金额";
                case PERCENT -> "比例";
                case DURATION -> "期限";
            };
            if (sb.indexOf(name) < 0) {
                if (sb.length() > 0) {
                    sb.append('/');
                }
                sb.append(name);
            }
        }
        return sb.toString();
    }

    /**
     * 压成单行：把所有空白（含换行、制表、全角空格）折叠成一个半角空格。
     * <p>
     * 两个理由：
     * <ul>
     *   <li><b>注入形状</b>：摘要块是"一段声明 + 若干行"的结构，而区块的结构是我们
     *       唯一的形状防线。模型输出里的多行内容看起来就像"又开了一个区块"，
     *       等于让模型有机会伪造结构</li>
     *   <li><b>预算可算</b>：{@code maxChars} 是给一段连续文本的预算，多行文本在
     *       渲染时还要额外算换行，账目对不上</li>
     * </ul>
     * ⚠️ 刻意<b>不</b>复用 {@code FactValueNormalizer}：那个是为<b>比对</b>而设计的
     * （NFKC + 去标点 + 转小写），用在摘要上会把一段给人看的话变成一串词 ——
     * 而摘要的用途恰恰是给人（和模型）看懂"在说哪一段"。
     */
    private static String singleLine(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                // 前导空白不产生空格（否则 strip 之后还得再判一次）
                pendingSpace = sb.length() > 0;
                continue;
            }
            if (pendingSpace) {
                sb.append(' ');
                pendingSpace = false;
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
