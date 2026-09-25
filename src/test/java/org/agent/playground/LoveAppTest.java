package org.agent.playground;

import jakarta.annotation.Resource;
import org.agent.playground.LoveApp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

/**
 * 真实 LLM 冒烟测试：需要配置 DEEPSEEK_API_KEY 环境变量（走网络），
 * 未配置时跳过，避免标准单测套件依赖外部密钥/网络。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
public class LoveAppTest {
    @Resource
    private LoveApp loveApp;

    @Test
    void testChat() {
        String chatId = UUID.randomUUID().toString();
        // 第一轮
        String message = "你好，我是程序员鱼皮";
        String answer = loveApp.doChat(message, chatId);
        // 第二轮
        message = "我想让另一半（编程导航）更爱我";
        answer = loveApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        // 第三轮
        message = "我的另一半叫什么来着？刚跟你说过，帮我回忆一下";
        answer = loveApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
    }

}
