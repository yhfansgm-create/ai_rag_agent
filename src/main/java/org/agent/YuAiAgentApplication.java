package org.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类。默认 profile 见 {@code application.yml}；本地跑用 {@code -Dspring-boot.run.profiles=local}。
 *
 * <h2>⚠️ 它所在的包位置是"承重"的，不要因为"看着空"就挪走</h2>
 * 两个框架机制都以<b>本类所在的包</b>为扫描根，而不是以整个 classpath：
 * <ul>
 *   <li><b>Spring 组件扫描</b>：{@code @Component} / {@code @Service} / {@code @RestController}
 *       / {@code @Configuration} 都在 {@code org.agent} 及其子包下被自动发现</li>
 *   <li><b>MyBatis Mapper 发现</b>：{@code mybatis-spring-boot-starter} 的
 *       {@code AutoConfiguredMapperScannerRegistrar} 从 {@code AutoConfigurationPackages}
 *       （即本类的包）开始扫 {@code @Mapper} 接口。项目里<b>没有</b> {@code @MapperScan}，
 *       全靠这条隐式规则 —— 所以 {@code memory.dao.ChatMessageDao} 和
 *       {@code permission.dao.SessionDao} 才不需要任何显式注册</li>
 * </ul>
 * 本类往上一级（譬如挪进一个新建的 {@code org.agent.boot} 包）会让这两个扫描都只覆盖
 * {@code org.agent.boot} 之下，结果是一堆"Bean 找不到"和"Mapper 未注册"的启动期报错。
 * 子包重构（如把 {@code rag} 拆成 {@code rag.ingest} / {@code rag.retrieve}）不受影响。
 */
@SpringBootApplication
public class YuAiAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(YuAiAgentApplication.class, args);
    }

}
