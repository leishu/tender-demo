package org.example.catalog.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 投标目录生成结果。
 *
 * <p>对应 catalog-generation 提示词输出的顶层 JSON：合并目录时使用 {@code catalog}；
 * 商务与技术分列时改用 {@code businessCatalog} 与 {@code technicalCatalog}，
 * 不再输出 {@code catalog}。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "投标目录生成结果")
public class CatalogResult {

    /** 合并目录（两套目录分列时不输出） */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Schema(description = "合并目录，商务/技术分列时为空")
    private List<CatalogNode> catalog = new ArrayList<>();

    /** 商务标目录（仅商务/技术分列时输出） */
    @JsonProperty("business_catalog")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Schema(description = "商务标目录，仅商务/技术分列时输出")
    private List<CatalogNode> businessCatalog;

    /** 技术标目录（仅商务/技术分列时输出） */
    @JsonProperty("technical_catalog")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Schema(description = "技术标目录，仅商务/技术分列时输出")
    private List<CatalogNode> technicalCatalog;

    /** 结构判断说明：合并目录或商务技术分列，并附一句依据 */
    @JsonProperty("structure_decision")
    @Schema(description = "结构判断说明：合并目录或商务技术分列，并附一句依据",
            example = "合并目录：原文商务与技术内容混合编排")
    private String structureDecision;

    /** 缺口说明，无缺口时为空数组 */
    @Schema(description = "缺口说明，无缺口时为空数组")
    private List<CatalogGap> gaps = new ArrayList<>();

    /**
     * 是否为商务/技术分列结构。
     *
     * @return 两套目录任一非空时返回 true
     */
    @JsonIgnore
    public boolean isSplit() {
        return (businessCatalog != null && !businessCatalog.isEmpty())
                || (technicalCatalog != null && !technicalCatalog.isEmpty());
    }
}
