package org.agent.config;

import org.agent.rag.config.RagVectorStoreConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 装配回归测试：**两套数据源同时在场时，谁被注入给「按类型要一个 DataSource」的消费方**。
 *
 * <p>为什么值得单独测：这个装配错了**不会报错**。`DataSourceAutoConfiguration` 的判据是
 * 「有没有用户自定义的 DataSource」，一旦向量库那个 PG 成了唯一候选，MyBatis / {@code JdbcTemplate}
 * / 事务管理器会一起绑过去 —— 应用照常启动、日志没有一行异常，只有业务表读写悄悄落在错误的库上
 * （实测探针 {@code PROBE-DS=[ragVectorDataSource]} 就是这么发现的）。
 * 静默的装配错误只能靠一条会响的测试来守。
 *
 * <p>**不连任何数据库**：Hikari 的池是**第一次 {@code getConnection()} 时才启动**的，
 * 这里只创建 bean、不取连接；PG 那份直接用 mock。所以这条测试在任何机器、任何网络下都能跑，
 * 不依赖 MySQL / PG / 密钥 —— 否则它就会像其它上下文测试一样被"需要真实环境"劝退，
 * 而恰恰是那些测试**看不见**这个 bug（它们都关掉了 vectorstore）。
 */
class DataSourceWiringTest {

    /**
     * 假的环境：属性从 Map 来，不读 {@code application-local.yml}。
     * 于是这条测试既不需要机密文件，也不会被本机配置的改动带偏。
     */
    private static AnnotationConfigApplicationContext contextWithBothDataSources() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "spring.datasource.url", "jdbc:mysql://127.0.0.1:3306/fan_ai_agent",
                "spring.datasource.username", "someone",
                "spring.datasource.password", "not-a-real-password",
                "spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver")));
        ctx.registerBean("propertyConfigurer", PropertySourcesPlaceholderConfigurer.class,
                PropertySourcesPlaceholderConfigurer::new);
        // 只为了凑出「第二个候选」，永远取不到连接
        ctx.registerBean("ragVectorDataSource", DataSource.class, () -> mock(DataSource.class));
        ctx.register(DataSourceConfig.class);
        ctx.refresh();
        return ctx;
    }

    @Test
    @DisplayName("按类型要 DataSource 时拿到的是业务库（MySQL），不是向量库（PG）")
    void typeInjectionResolvesToBusinessDataSource() {
        try (AnnotationConfigApplicationContext ctx = contextWithBothDataSources()) {
            // 消费方（MyBatis / JdbcTemplate / 事务管理器）用的就是这个解析方式
            DataSource byType = ctx.getBean(DataSource.class);

            assertThat(byType).isSameAs(ctx.getBean("dataSource"));
            assertThat(byType).isInstanceOf(com.zaxxer.hikari.HikariDataSource.class);
            assertThat(((com.zaxxer.hikari.HikariDataSource) byType).getJdbcUrl())
                    .startsWith("jdbc:mysql://");
            // @Primary 的另一半：PG 那份**不是**主角，只能按名字取
            assertThat(byType).isNotSameAs(ctx.getBean("ragVectorDataSource"));
        }
    }

    @Test
    @DisplayName("向量库的消费方必须写死 @Qualifier，否则会被 @Primary 抢走业务库")
    void vectorStoreConsumerIsQualifiedByBeanName() throws NoSuchMethodException {
        Method beanMethod = RagVectorStoreConfig.class.getMethod("ragVectorStore",
                EmbeddingModel.class, int.class, DataSource.class);
        Qualifier qualifier = beanMethod.getParameters()[2].getAnnotation(Qualifier.class);

        // 少写这个限定符不会抛异常，只会把向量写进业务库 —— 所以让它在编译后立刻响
        assertThat(qualifier).as("ragVectorStore 的 DataSource 参数缺少 @Qualifier").isNotNull();
        assertThat(qualifier.value()).isEqualTo("ragVectorDataSource");
    }
}
