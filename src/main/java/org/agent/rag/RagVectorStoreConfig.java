package org.agent.rag;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
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
 * RAG 后端配置 —— 默认关闭，打开 rag.vectorstore.enabled=true 时激活。
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

    /**
     * 阿里云百炼 Qwen embedding（DashScope OpenAI 兼容模式）。
     * <p>
     * 与 DeepSeek chat 分离：chat 走 spring.ai.openai.*（base-url=deepseek.com），
     * 这里用独立的 rag.embedding.* 指向 DashScope，两个模型互不干扰。
     * api-key 缺失直接启动失败（fail-fast，避免运行期 401 难以排查）。
     */
    @Bean
    public EmbeddingModel ragEmbeddingModel(
            @Value("${rag.embedding.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${rag.embedding.api-key:}") String apiKey,
            @Value("${rag.embedding.model:text-embedding-v3}") String model,
            @Value("${rag.embedding.dimensions:1024}") int dimensions) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 rag.embedding.api-key（阿里云百炼 DASHSCOPE_API_KEY），"
                    + "请用环境变量注入后再开启 rag.vectorstore.enabled");
        }
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(model)
                .dimensions(dimensions)
                .build();
        // MetadataMode.EMBED 与 Spring AI 自动配置默认一致
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options);
    }

    @Bean
    public VectorStore ragVectorStore(EmbeddingModel embeddingModel,
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
