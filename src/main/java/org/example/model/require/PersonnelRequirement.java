package org.example.model.require;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 人员（关键岗位）要求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_personnel_requirement")
@Schema(description = "招标人员（关键岗位）要求")
public class PersonnelRequirement extends TenderModel {
    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    /** 关联的 TenderRequirements 主键 */
    @Schema(description = "关联的 TenderRequirements 主键")
    private Long tenderRequirementId;

    /** 企业ID */
    @Schema(description = "企业ID")
    private Long enterpriseId;

    /** 岗位名称，如 项目经理、技术负责人 */
    @Schema(description = "岗位名称，如 项目经理、技术负责人", example = "项目经理")
    private String role;
    /** 人数要求 */
    @Schema(description = "人数要求", example = "1")
    private Integer quantity;
    /** 证书 / 职称要求，如 一级建造师 */
    @Schema(description = "证书 / 职称要求，如 一级建造师", example = "一级建造师")
    private String certificate;
    /** 原文出处片段 */
    @Schema(description = "原文出处片段")
    private String source;
}