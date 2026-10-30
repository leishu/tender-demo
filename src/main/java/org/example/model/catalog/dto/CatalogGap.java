package org.example.model.catalog.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 投标目录缺口说明。
 *
 * <p>用于回传解析结果缺失或目录无法覆盖评分点时的问题项，交由上层处理。
 * 落库时对应 {@code org.example.model.catalog.BidCatalogGap}。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "投标目录缺口说明")
public class CatalogGap {

    /** 缺口类型：data_not_found / missing_scoring_item / missing_requirement */
    @Schema(description = "缺口类型",
            allowableValues = {"data_not_found", "missing_scoring_item", "missing_requirement"},
            example = "missing_requirement")
    private String type;

    /** 缺口详情 */
    @Schema(description = "缺口详情", example = "缺少业绩要求")
    private String detail;
}
