package org.agent.playground;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 「恋爱心理专家」单轮问答 demo，<b>不属于银行 RAG 主链路</b>。
 *
 * <h2>它在这里做什么</h2>
 * 这是跟着 Spring AI 教程走通 {@link ChatClient} 基本功时留下的示例，保留在 {@code playground}
 * 包内作参考。它的价值是<b>反面参照</b>：同样用 {@code ChatClient}，它直接用
 * {@code InMemoryChatMemoryRepository}（一个 MAP）当记忆，而主链路 {@code memory} 包
 * 花了整个模块去解决"记忆回放会绕过权限门禁"的问题 —— 两个实现放一起，差距一眼可见。
 *
 * <h2>这里刻意没有做什么</h2>
 * <ul>
 *   <li><b>没有接权限</b>。对话不带任何会话凭证，谁调都能用 —— 这正是它不能进主链路的原因。</li>
 *   <li><b>没有持久化记忆</b>。{@code InMemoryChatMemoryRepository} 随进程消失，重启即失忆。</li>
 * </ul>
 * 业务对话入口在 {@code org.agent.api.BankAssistant}，那个才带三层鉴权与检索。
 */
@Component
@Slf4j
public class LoveApp {

    private final ChatClient chatClient;

    private static final String SYSTEM_PROMPT = "扮演深耕恋爱心理领域的专家。开场向用户表明身份，告知用户可倾诉恋爱难题。" +
            "围绕单身、恋爱、已婚三种状态提问：单身状态询问社交圈拓展及追求心仪对象的困扰；" +
            "恋爱状态询问沟通、习惯差异引发的矛盾；已婚状态询问家庭责任与亲属关系处理的问题。" +
            "引导用户详述事情经过、对方反应及自身想法，以便给出专属解决方案。";

    /**
     * 初始化 ChatClient
     * @param chatModel
     */
    public LoveApp(@Qualifier("openAiChatModel") ChatModel chatModel) {
        // 基于内存的记忆方式 - 实际实现是一个 MAP - 其中是四个 CRUD 方法
        // 注：原 PG 方言 JDBC 记忆在本机无 PG 时跑不通，临时切回内存版；接 PGVector 时恢复 JDBC 记忆并配双数据源
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(10)
                .build();

        chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    public String doChat (String message, String chatId){
        ChatResponse chatResponse = chatClient.prompt().user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call().chatResponse();

        ChatResponseMetadata metadata = chatResponse.getMetadata();
        String context = chatResponse.getResult().getOutput().getText();
        log.info("metadata is :{}", metadata.toString());
        log.info("context is :{}", context);
        return context;
    }

    /**
     * 新语法 - 快速定义一个类
     * @param title
     * @param suggests
     */
    record LoveReport (String title, List<String> suggests){

    }

    /**
     * 结构化输出 -- 输出知道类型数据
     * @param message
     * @param chatId
     * @return
     */
    public LoveReport doChatWithReport (String message, String chatId){
        LoveReport loveReport = chatClient.prompt()
                .system(SYSTEM_PROMPT + "每次对话后都要生成恋爱结果，标题为{用户名}的恋爱报告，内容为建议列表")
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call().entity(LoveReport.class);
        log.info("loveReport is :{}", loveReport);
        return loveReport;
    }
}
