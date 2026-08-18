package org.agent.App;

import lombok.extern.log4j.Log4j2;
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

@Component
@Log4j2
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
