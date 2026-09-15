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
 *   <li>需要先有一个 EmbeddingModel bean（此处用 OpenAI 兼容的 SiliconFlow embedding）</li>
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
     * 硅基流动 SiliconFlow embedding（OpenAI 兼容模式，BAAI/bge-m3）。
     * <p>
     * 与 DeepSeek chat 分离：chat 走 spring.ai.openai.*（base-url=deepseek.com），
     * 这里用独立的 rag.embedding.* 指向 SiliconFlow，两个模型互不干扰。
     * base-url 不带 /v1：Spring AI 用默认 embeddings-path=/v1/embeddings 追加。
     * api-key 缺失直接启动失败（fail-fast，避免运行期 401 难以排查）。
     */
    @Bean
    public EmbeddingModel ragEmbeddingModel(
            @Value("${rag.embedding.base-url:https://api.siliconflow.cn}") String baseUrl,
            @Value("${rag.embedding.api-key:}") String apiKey,
            @Value("${rag.embedding.model:BAAI/bge-m3}") String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 rag.embedding.api-key（硅基流动 SILICONFLOW_API_KEY），"
                    + "请用环境变量注入后再开启 rag.vectorstore.enabled");
        }
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
        // 注意：不要给 SiliconFlow 传 dimensions 字段 —— bge-m3 输出固定 1024 维，传了会报 400(错误码 20015)。
        // 向量列维度由 rag.vectorstore.dimensions(=1024) 决定，与 bge-m3 输出一致即可。
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(model)
                .build();
        // MetadataMode.EMBED 与 Spring AI 自动配置默认一致
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options);
    }

    @Bean
    public VectorStore ragVectorStore(@Qualifier("ragEmbeddingModel") EmbeddingModel embeddingModel,
                                      @Value("${rag.vectorstore.dimensions:1536}") int dimensions,
                                      DataSource ragVectorDataSource) {
        // 表名默认 vector_store，可另用 schemaName/tableName 定制（当前版本 builder 未暴露 tableName）
        return PgVectorStore.builder(new JdbcTemplate(ragVectorDataSource), embeddingModel)
                .dimensions(dimensions)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .schemaName("public")
                // 必须显式开启：Spring AI 1.0.0 经 builder 构建时 initializeSchema 默认为 false，
                // 不设会导致启动日志出现 "Skipping the schema initialization for the table: public.vector_store"，
                // 表不会被创建，后续 add/similaritySearch 全部报表不存在。
                .initializeSchema(true)
                .build();
    }
}
