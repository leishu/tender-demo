package org.example.model.knowledge;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@TableName("t_knowledge_base")
@Schema(description = "知识库元数据")
public class KnowledgeBase {

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID，自增")
    private Long id;

    @Schema(description = "知识库名称，业务分组标识")
    private String name;

    @Schema(description = "知识库描述")
    private String description;

    @Schema(description = "该知识库下文档总数")
    private Long documentCount;

    @Schema(description = "该知识库文档的向量维度")
    private Integer embeddingDimension;

    @Schema(description = "是否启用向量化")
    private Boolean embeddingEnabled;

    @Schema(description = "使用的 embedding 提供者 / 模型")
    private String embeddingModel;

    @Schema(description = "创建时间（epoch 毫秒）")
    private Long createdAt;

    @Schema(description = "最近更新时间（epoch 毫秒）")
    private Long updatedAt;

    @Schema(description = "软删除标记")
    private Boolean deleted;
}