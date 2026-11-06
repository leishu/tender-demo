package org.example.matching.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 单条企业资质要求的匹配结果。
 */
@Data
@Schema(description = "企业资质要求匹配结果")
public class QualificationMatchResult {

    @Schema(description = "判别字段，固定为 qualificationMatch", example = "qualificationMatch")
    private String entityType;

    @Schema(description = "对应 t_qualification_requirement 主键")
    private Long requirementId;

    @Schema(description = "招标资质类型", example = "建筑机电安装工程专业承包")
    private String requiredType;

    @Schema(description = "招标资质等级", example = "贰级")
    private String requiredLevel;

    @Schema(description = "招标有效期要求")
    private String validRequirement;

    @Schema(description = "招标文件原文出处")
    private String source;

    @Schema(description = "整体匹配状态")
    private MatchStatus status;

    @Schema(description = "满足条件的候选资质数量")
    private Integer matchedCount;

    @Schema(description = "候选资质列表")
    private List<QualificationMatchCandidate> candidates;

    @Schema(description = "整体匹配结论")
    private String reason;
}
