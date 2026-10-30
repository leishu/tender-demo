package org.example.model.require;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 招标文件结构化要求（TenderRequirements）。
 * 由 tender-parser 子 Agent 解析招标文件后产出，供后续投标章节生成使用。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tender_requirements")
@Schema(description = "招标文件结构化要求")
public class TenderRequirements extends TenderModel {
    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    /** 企业ID */
    @Schema(description = "企业ID")
    private Long enterpriseId;

    /** 项目名称 */
    @Schema(description = "项目名称")
    private String projectName;
    /** 建设地点 */
    @Schema(description = "建设地点")
    private String location;
    /** 工期要求，如 “180 日历天”；无法确定时标注“未明确（原文未提供）” */
    @Schema(description = "工期要求，如 “180 日历天”；无法确定时标注“未明确（原文未提供）”")
    private String duration;
    /** 预算 / 报价上限（金额 + 币种） */
    @Schema(description = "预算 / 报价上限（金额 + 币种）")
    private String budget;

    /** 资质要求清单（非数据库列，子表单独维护） */
    @TableField(exist = false)
    @Schema(description = "资质要求清单")
    private List<QualificationRequirement> qualifications;
    /** 业绩要求清单（非数据库列，子表单独维护） */
    @TableField(exist = false)
    @Schema(description = "业绩要求清单")
    private List<PerformanceRequirement> performances;
    /** 人员要求清单（非数据库列，子表单独维护） */
    @TableField(exist = false)
    @Schema(description = "人员要求清单")
    private List<PersonnelRequirement> personnel;
    /** 评分点清单（非数据库列，子表单独维护） */
    @TableField(exist = false)
    @Schema(description = "评分点清单")
    private List<ScoringItem> scoringItems;
}