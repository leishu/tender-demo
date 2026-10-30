package org.example.model.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 知识库文档，对应 Elasticsearch 中的一条记录。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "知识库文档，对应 Elasticsearch 中的一条记录")
public class KnowledgeDocument {

    /** 文档 ID，写入时由业务侧生成，同时作为 ES 的 _id */
    @Schema(description = "文档 ID，写入时由业务侧生成，同时作为 ES 的 _id")
    private String id;

    /** 所属知识库 ID（MySQL 自增主键），用于 term filter 分组 */
    @Schema(description = "所属知识库 ID（MySQL 自增主键），用于 term filter 分组")
    private Long knowledgeBaseId;

    /** 原始文本内容，全文检索字段 */
    @Schema(description = "原始文本内容，全文检索字段")
    private String content;

    /** 创建时间（epoch 毫秒） */
    @Schema(description = "创建时间（epoch 毫秒）")
    private Long createdAt;

    /** 文本向量，dense_vector 字段，检索时不需要返回 */
    @Schema(description = "文本向量，dense_vector 字段，检索时不需要返回", hidden = true)
    private List<Float> embedding;
}