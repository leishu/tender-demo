package org.example.catalog.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 投标目录缺口说明（对应表 t_bid_catalog_gap）。
 *
 * <p>与 DTO {@link org.example.catalog.model.dto.CatalogGap} 一一对应，按批次落库便于回溯生成质量问题。</p>
 */
@Data
@TableName("t_bid_catalog_gap")
@Schema(description = "投标目录缺口说明")
public class BidCatalogGap {

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    /** 关联的 t_bid_catalog_run 主键 */
    @Schema(description = "关联的目录生成批次主键")
    private Long catalogRunId;

    /** 缺口类型：data_not_found / missing_scoring_item / missing_requirement */
    @Schema(description = "缺口类型",
            allowableValues = {"data_not_found", "missing_scoring_item", "missing_requirement"},
            example = "missing_requirement")
    private String gapType;

    /** 缺口详情 */
    @Schema(description = "缺口详情", example = "缺少业绩要求")
    private String detail;

    /** 创建时间（epoch 毫秒） */
    @Schema(description = "创建时间（epoch 毫秒）")
    private Long createdAt;
}
