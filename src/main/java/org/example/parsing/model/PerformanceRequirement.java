package org.example.parsing.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 业绩要求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_performance_requirement")
@Schema(description = "招标业绩要求")
public class PerformanceRequirement extends TenderModel {
    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    /** 关联的 TenderRequirements 主键 */
    @Schema(description = "关联的 TenderRequirements 主键")
    private Long tenderRequirementId;

    /** 企业ID */
    @Schema(description = "企业ID")
    private Long enterpriseId;

    /** 类似项目类型描述 */
    @Schema(description = "类似项目类型描述", example = "公共建筑工程施工总承包")
    private String projectType;
    /** 数量要求，如 “1 个” */
    @Schema(description = "数量要求，如 “1 个”", example = "1 个")
    private String quantity;
    /** 单项目或累计金额下限 */
    @Schema(description = "单项目或累计金额下限", example = "≥ 5000 万元")
    private String amount;
    /** 业绩时间范围，如 “近三年（2022-2025）” */
    @Schema(description = "业绩时间范围，如 “近三年（2022-2025）”", example = "近三年（2022-2025）")
    private String timeRange;
    /** 原文出处片段 */
    @Schema(description = "原文出处片段")
    private String source;
}