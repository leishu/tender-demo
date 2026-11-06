package org.example.matching.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 单条人员要求的匹配结果。
 */
@Data
@Schema(description = "人员要求匹配结果")
public class PersonnelMatchResult {

    @Schema(description = "判别字段，固定为 personnelMatch", example = "personnelMatch")
    private String entityType;

    @Schema(description = "对应 t_personnel_requirement 主键")
    private Long requirementId;

    @Schema(description = "招标岗位名称", example = "项目经理")
    private String role;

    @Schema(description = "招标人数要求", example = "1")
    private Integer requiredQuantity;

    @Schema(description = "招标证书/职称要求", example = "一级建造师（机电工程）")
    private String certificate;

    @Schema(description = "招标文件原文出处")
    private String source;

    @Schema(description = "整体匹配状态")
    private MatchStatus status;

    @Schema(description = "满足条件的候选人数")
    private Integer matchedCount;

    @Schema(description = "候选人列表")
    private List<PersonnelMatchCandidate> candidates;

    @Schema(description = "整体匹配结论")
    private String reason;
}
