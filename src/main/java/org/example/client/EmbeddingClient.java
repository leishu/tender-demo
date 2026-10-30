package org.example.client;

import java.util.List;

/**
 * 文本向量化客户端。
 *
 * <p>把知识库文本与查询语句转换成稠密向量，供 Elasticsearch 的
 * {@code dense_vector} / kNN 检索使用。</p>
 */
public interface EmbeddingClient {

    /**
     * 是否已配置可用的向量化服务。
     * 未配置时业务侧应降级为纯全文检索。
     */
    boolean isEnabled();

    /**
     * 向量化一条查询语句。
     */
    List<Float> embedQuery(String text);

    /**
     * 批量向量化知识库文档，返回顺序与入参一致。
     */
    List<List<Float>> embedDocuments(List<String> texts);
}
