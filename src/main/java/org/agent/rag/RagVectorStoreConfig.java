package org.agent.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * PGVector 向量库配置 —— 默认关闭，打开 rag.vectorstore.enabled=true 时激活。
 * <p>
 * 注意：
 * <ul>
 *   <li>PG 是独立于 MySQL 会话库的第二个数据源（vectorstore.datasource.*）</li>
 *   <li>需要先有一个 EmbeddingModel bean（此处用 OpenAI 兼容的 DashScope embedding）</li>
 *   <li>embedding 涉及文档原文编码，生产环境须评估数据出境合规；测试环境可直接用云端 API</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(prefix = "rag.vectorstore", name = "enabled", havingValue = "true")
public class RagVectorStoreConfig {

    @Bean
    public DataSource ragVectorDataSource(@Value("${rag.vectorstore.datasource.url}") String url,
                                          @Value("${rag.vectorstore.datasource.username}") String username,
                                          @Value("${rag.vectorstore.datasource.password}") String password) {
        return DataSourceBuilder.create().url(url).username(username).password(password).build();
    }

    @Bean
    public VectorStore ragVectorStore(@Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel,
                                      @Value("${rag.vectorstore.dimensions:1536}") int dimensions,
                                      DataSource ragVectorDataSource) {
        // 表名默认 vector_store，可另用 schemaName/tableName 定制（当前版本 builder 未暴露 tableName）
        return PgVectorStore.builder(new JdbcTemplate(ragVectorDataSource), embeddingModel)
                .dimensions(dimensions)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .schemaName("public")
                .build();
    }
}
