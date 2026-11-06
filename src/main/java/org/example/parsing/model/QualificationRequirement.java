package org.example.parsing.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 资质要求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_qualification_requirement")
@Schema(description = "招标资质要求")
public class QualificationRequirement extends TenderModel {
    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    /** 关联的 TenderRequirements 主键 */
    @Schema(description = "关联的 TenderRequirements 主键")
    private Long tenderRequirementId;

    /** 企业ID */
    @Schema(description = "企业ID")
    private Long enterpriseId;

    /** 资质类型，如 ISO9001、施工总承包一级 */
    @Schema(description = "资质类型，如 ISO9001、施工总承包一级", example = "施工总承包一级")
    private String type;
    /** 资质等级 */
    @Schema(description = "资质等级", example = "壹级")
    private String level;
    /** 有效期要求（原文若说明则填写，否则标注“未明确（原文未提供）”） */
    @Schema(description = "有效期要求（原文若说明则填写，否则标注“未明确（原文未提供）”）")
    private String validRequirement;
    /** 原文出处片段 */
    @Schema(description = "原文出处片段")
    private String source;
}