package org.example.model.catalog;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 投标目录章节（对应表 t_bid_catalog）。
 *
 * <p>通过 {@link #parentId} 自关联还原目录树，{@link #chapterLevel} 限定最多三级；
 * 商务/技术分列时用 {@link #catalogCategory} 区分目录归属。</p>
 */
@Data
@TableName("t_bid_catalog")
@Schema(description = "投标目录章节")
public class BidCatalog {

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    /** 关联的 t_bid_catalog_run 主键 */
    @Schema(description = "关联的目录生成批次主键")
    private Long catalogRunId;

    /** 父章节主键，NULL 表示一级章节 */
    @Schema(description = "父章节主键，NULL 表示一级章节")
    private Long parentId;

    /** 层级：1 一级、2 二级、3 三级 */
    @Schema(description = "层级：1 一级、2 二级、3 三级", example = "2")
    private Integer chapterLevel;

    /** 目录归属：COMMON 合并目录、BUSINESS 商务标、TECHNICAL 技术标 */
    @Schema(description = "目录归属：COMMON 合并目录、BUSINESS 商务标、TECHNICAL 技术标", example = "COMMON")
    private CatalogCategory catalogCategory;

    /** 章节编号，与嵌套位置一致：1、1.1、1.1.1 */
    @Schema(description = "章节编号，与嵌套位置一致：1、1.1、1.1.1", example = "1.1")
    private String chapterNo;

    /** 章节标题，不超过 20 字 */
    @Schema(description = "章节标题", example = "施工组织设计")
    private String title;

    /** 内容要点提示 */
    @Schema(description = "内容要点提示，1-2 句", example = "说明施工总体部署与进度安排，对应评分点 S3")
    private String contentHint;

    /** 同级排序号，按章节编号逐段数字升序 */
    @Schema(description = "同级排序号，按章节编号逐段数字升序")
    private Integer sortNo;

    /** 创建时间（epoch 毫秒） */
    @Schema(description = "创建时间（epoch 毫秒）")
    private Long createdAt;
}
