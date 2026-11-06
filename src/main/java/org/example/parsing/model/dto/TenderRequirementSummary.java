package org.example.parsing.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 已解析招标文件的列表项。
 *
 * <p>只聚合列表页需要的展示字段与各类要求条数，不返回资质/业绩/人员/评分点明细，
 * 避免列表接口返回过大的响应体。</p>
 */
@Data
@Schema(description = "已解析招标文件列表项")
public class TenderRequirementSummary {

    @Schema(description = "招标要求主键ID")
    private Long id;

    @Schema(description = "项目名称")
    private String projectName;

    @Schema(description = "建设地点")
    private String location;

    @Schema(description = "工期要求")
    private String duration;

    @Schema(description = "预算 / 报价上限")
    private String budget;

    @Schema(description = "资质要求条数")
    private int qualificationCount;

    @Schema(description = "业绩要求条数")
    private int performanceCount;

    @Schema(description = "人员要求条数")
    private int personnelCount;

    @Schema(description = "评分点条数")
    private int scoringItemCount;

    @Schema(description = "是否已保存投标目录")
    private boolean hasCatalog;
}
