package org.example.parsing.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 评分点。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_scoring_item")
@Schema(description = "招标评分点")
public class ScoringItem extends TenderModel {
    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    /** 关联的 TenderRequirements 主键 */
    @Schema(description = "关联的 TenderRequirements 主键")
    private Long tenderRequirementId;

    /** 评分点 id */
    @Schema(description = "评分点 id", example = "SC-01")
    private String itemId;
    /** 评分点名称 */
    @Schema(description = "评分点名称", example = "施工组织设计")
    private String name;
    /** 分值 */
    @Schema(description = "分值", example = "10")
    private Integer score;
    /** 评审标准 */
    @Schema(description = "评审标准")
    private String criterion;
    /** 建议承载章节 */
    @Schema(description = "建议承载章节", example = "施工组织设计")
    private String suggestedChapter;
    /** 原文出处片段 */
    @Schema(description = "原文出处片段")
    private String source;
}