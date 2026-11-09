package org.example.matching.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 单条人员要求的匹配结果。
 *
 * <p>{@code status} 只依据工具数据可核验的项判定；招标强制项中无法核验的部分
 * （如社保记录、是否担任其他在建工程项目经理）列入 {@code unverifiedRequirements}
 * 并置 {@code requiresManualReview=true}，不因此把 {@code MATCHED} 降级为 {@code PARTIAL}，
 * 避免与“满足人数不足”的语义混淆。</p>
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

    @Schema(description = "是否存在无法核验的强制项；为 true 时该条结论需人工复核")
    private Boolean requiresManualReview;

    @Schema(description = "工具未返回数据、无法核验的招标强制项，如社保记录、是否担任其他在建工程项目经理")
    private List<String> unverifiedRequirements;

    @Schema(description = "候选人列表")
    private List<PersonnelMatchCandidate> candidates;

    @Schema(description = "整体匹配结论")
    private String reason;
}
