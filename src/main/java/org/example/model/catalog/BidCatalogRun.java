package org.example.model.catalog;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 投标目录生成批次（对应表 t_bid_catalog_run）。
 *
 * <p>一次目录生成产出 1 条批次记录，承载结构判断结果，
 * 章节树与缺口分别挂在 {@link BidCatalog}、{@link BidCatalogGap} 上。</p>
 */
@Data
@TableName("t_bid_catalog_run")
@Schema(description = "投标目录生成批次")
public class BidCatalogRun {

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    /** 关联的 t_tender_requirements 主键 */
    @Schema(description = "关联的招标要求主键", example = "1")
    private Long tenderRequirementId;

    /** 企业ID */
    @Schema(description = "企业ID")
    private Long enterpriseId;

    /** 是否商务/技术分列 */
    @Schema(description = "是否商务/技术分列：false 合并目录，true 分列")
    private Boolean splitFlag;

    /** 结构判断说明 */
    @Schema(description = "结构判断说明：合并目录 / 商务技术分列及一句依据",
            example = "合并目录：原文商务与技术内容混合编排")
    private String structureDecision;

    /** 创建时间（epoch 毫秒） */
    @Schema(description = "创建时间（epoch 毫秒）")
    private Long createdAt;
}
