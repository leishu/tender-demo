package org.example.model.knowledge;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库检索命中结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "知识库检索命中结果")
public class KnowledgeSearchHit {

    @Schema(description = "文档 ID")
    private String id;

    @Schema(description = "命中的文本内容")
    private String content;

    @Schema(description = "创建时间（epoch 毫秒）")
    private Long createdAt;

    @Schema(description = "混合检索得分（RRF 融合后的分数，越大越相关）")
    private Double score;
}
