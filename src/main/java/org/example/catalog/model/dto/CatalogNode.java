package org.example.catalog.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 投标目录条目（树形节点）。
 *
 * <p>对应 catalog-generation 提示词输出的目录条目：顶层数组元素为一级章节，
 * {@code children} 依次承载二级、三级章节，三级节点的 {@code children} 为空数组。
 * 落库时由转换器展开为 {@code BidCatalog} 记录（children → parent_id）。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "投标目录条目")
public class CatalogNode {

    /** 章节编号，必须与嵌套位置一致：一级 "1"、二级 "1.1"、三级 "1.1.1" */
    @JsonProperty("chapter_no")
    @Schema(description = "章节编号，与嵌套位置一致", example = "1.1")
    private String chapterNo;

    /** 章节标题，不超过 20 字，不使用兜底章节名 */
    @Schema(description = "章节标题", example = "施工组织设计")
    private String title;

    /** 承载的评分点 id，原样引用 t_scoring_item.item_id；纯结构归集的父章节为空数组 */
    @JsonProperty("scoring_item_ids")
    @Schema(description = "承载的评分点 id 列表，原样引用 t_scoring_item.item_id")
    private List<String> scoringItemIds = new ArrayList<>();

    /** 内容要点提示，1-2 句，用于指导该章节撰写 */
    @JsonProperty("content_hint")
    @Schema(description = "内容要点提示，1-2 句，用于指导该章节撰写")
    private String contentHint;

    /** 子章节，最多嵌套至三级；无子章节时为空数组 */
    @Schema(description = "子章节，最多嵌套至三级；无子章节时为空数组")
    private List<CatalogNode> children = new ArrayList<>();

    /**
     * 是否承载评分点。
     *
     * @return 评分点 id 列表非空时返回 true
     */
    @JsonIgnore
    public boolean hasScoringItem() {
        return scoringItemIds != null && !scoringItemIds.isEmpty();
    }
}
