package org.example.catalog.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 投标目录与评分点关联（对应表 t_bid_catalog_scoring_item）。
 *
 * <p>一个章节可承载多个评分点；{@link #itemId} 原样保存评分点 id，
 * 可匹配到实体时再回填 {@link #scoringItemId}。</p>
 */
@Data
@TableName("t_bid_catalog_scoring_item")
@Schema(description = "投标目录与评分点关联")
public class BidCatalogScoringItem {

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    /** 关联的 t_bid_catalog 主键 */
    @Schema(description = "关联的目录章节主键")
    private Long catalogId;

    /** 关联的 t_scoring_item 主键，按 item_id 回填；无法匹配时为 NULL */
    @Schema(description = "关联的评分点主键，按 itemId 回填；无法匹配时为 NULL")
    private Long scoringItemId;

    /** 评分点 id，原样引用 t_scoring_item.item_id */
    @Schema(description = "评分点 id，原样引用 t_scoring_item.item_id", example = "SC-01")
    private String itemId;

    /** 同一章节内评分点排序号 */
    @Schema(description = "同一章节内评分点排序号")
    private Integer sortNo;
}
