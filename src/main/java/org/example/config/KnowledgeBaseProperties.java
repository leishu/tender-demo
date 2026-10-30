package org.example.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 知识库配置。
 *
 * <p>对应 {@code application.yml} 中的 {@code knowledge-base} 节点，
 * 包含 Elasticsearch 连接信息与向量化（Embedding）模型参数。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "knowledge-base")
public class KnowledgeBaseProperties {

    /** 知识库索引名 */
    private String indexName = "knowledge_base";

    private Es es = new Es();

    private Embedding embedding = new Embedding();

    @Getter
    @Setter
    public static class Es {

        /** ES 节点地址，支持多个，如 http://host1:9200,http://host2:9200 */
        private List<String> uris = List.of("http://127.0.0.1:9200");

        /** 基础认证用户名，为空时不启用 */
        private String username;

        /** 基础认证密码 */
        private String password;

        /** API Key 认证，优先级高于用户名密码 */
        private String apiKey;
    }

    @Getter
    @Setter
    public static class Embedding {

        /** 向量化服务提供者：es（ES 内置 inference）/ dashscope（阿里云 DashScope） */
        private String provider = "es";

        // ====== DashScope 专用 ======

        private String baseUrl = "https://dashscope.aliyuncs.com";

        private String path = "/api/v1/services/embeddings/text-embedding/text-embedding";

        private String apiKey;

        private String model = "text-embedding-v3";

        // ====== ES Inference 专用 ======

        /**
         * ES inference endpoint ID，默认为 ES 9.x 内置的 multilingual-e5-small。
         * 可用 `/_inference` 查看所有端点。
         */
        private String esEndpointId = ".multilingual-e5-small-elasticsearch";

        // ====== 通用 ======

        /**
         * 向量维度，必须与 ES 索引中 dense_vector 的 dims 一致。
         * ES 内置 multilingual-e5-small 为 384 维；DashScope text-embedding-v3 为 1024 维。
         */
        private int dimension = 384;

        /** 单次请求的最大文本条数 */
        private int batchSize = 100;
    }
}