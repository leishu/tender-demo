package org.example.model;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@TableName("t_project_performance")
@Schema(description = "企业类似项目业绩")
public class ProjectPerformance {
    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    /** 企业ID */
    @Schema(description = "企业ID")
    private Long enterpriseId;

    /** 项目名称 */
    @Schema(description = "项目名称", example = "XX 产业园一期工程施工总承包")
    private String projectName;

    /** 业主/委托方 */
    @Schema(description = "业主/委托方", example = "XX 建设投资有限公司")
    private String client;

    /** 合同金额 */
    @Schema(description = "合同金额（元）", example = "128000000.00")
    private BigDecimal amount;

    /** 完工/签合同日期 */
    @Schema(description = "完工/签合同日期", example = "2024-06-30")
    private LocalDate signDate;
}
