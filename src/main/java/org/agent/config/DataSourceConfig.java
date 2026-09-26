package org.agent.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 业务库（MySQL {@code fan_ai_agent}）数据源的**显式**装配 —— 会话表、记忆表、审计表都在这儿。
 *
 * <h2>为什么非得自己声明：自动配置会整体退让</h2>
 * {@code DataSourceAutoConfiguration} 的判据是「上下文里已经有用户自定义的 {@code DataSource} 吗」，
 * 有就**整体退让**（连带 {@code spring.datasource.*} 一起不再生效）。于是只要
 * {@code rag.vectorstore.enabled=true}，{@code RagVectorStoreConfig.ragVectorDataSource}
 * 就可能是全上下文**唯一**的 {@code DataSource} —— MyBatis Mapper、{@code JdbcTemplate}、
 * {@code transactionManager} 会一起绑到那个 PG 上。
 * <p>
 * 症状很有欺骗性：**应用照常启动、日志里没有一行异常**，只是所有业务表读写都落在向量库那个库里。
 * 实测探针曾打出 {@code PROBE-DS=[ragVectorDataSource]}、{@code PROBE-URL=jdbc:postgresql://…/rag_kb}。
 *
 * <h2>两套数据源谁做主</h2>
 * <ul>
 *   <li><b>本 bean</b>（{@code spring.datasource.*} = MySQL）= 标 {@link Primary @Primary}。
 *       MyBatis 自动配置、{@code JdbcTemplateAutoConfiguration}、
 *       {@code DataSourceTransactionManagerAutoConfiguration} 三个的判据都是
 *       {@code @ConditionalOnSingleCandidate(DataSource.class)} —— 有多个候选时，
 *       「唯一候选」正是靠 {@code @Primary} 选出来的。</li>
 *   <li><b>PG</b>（{@code rag.vectorstore.datasource.*}）**绝不加 {@code @Primary}**，
 *       且它的消费方必须显式 {@code @Qualifier("ragVectorDataSource")}。
 *       反过来标就等于把这个 bug 反向犯一遍：业务表会被写进向量库。</li>
 * </ul>
 *
 * <h2>两个刻意的写法</h2>
 * <ol>
 *   <li>参数用 {@code @Value} 而不是注入 {@code DataSourceProperties}：与
 *       {@code RagVectorStoreConfig} 保持同一写法，两处能对照着读</li>
 *   <li>{@code spring.datasource.hikari.*} 仍按 Spring Boot 官方「多数据源」的写法绑到连接池上 ——
 *       本项目当前没配这一段，留着是为了将来加池参数时不会被**静默忽略**</li>
 * </ol>
 */
@Configuration
public class DataSourceConfig {

    @Bean(destroyMethod = "close")
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            @Value("${spring.datasource.driver-class-name:com.mysql.cj.jdbc.Driver}") String driverClassName) {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName(driverClassName)
                .url(url)
                .username(username)
                .password(password)
                .build();
    }
}
