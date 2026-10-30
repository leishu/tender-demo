package org.example.model.catalog;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 投标目录归属。
 *
 * <p>以 {@link #code} 作为落库值（t_bid_catalog.catalog_category）与 JSON 序列化值。</p>
 */
@Schema(enumAsRef = true)
public enum CatalogCategory {

    COMMON("COMMON", "合并目录"),
    BUSINESS("BUSINESS", "商务标"),
    TECHNICAL("TECHNICAL", "技术标");

    @EnumValue
    private final String code;

    private final String label;

    CatalogCategory(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 序列化为 {@code catalogCategory} 字段的实际字符串值 */
    @JsonValue
    public String getCode() {
        return code;
    }

    /** 中文说明 */
    public String getLabel() {
        return label;
    }

    /** 根据字符串值反查枚举；无法匹配时返回 {@code null} */
    public static CatalogCategory fromCode(String code) {
        for (CatalogCategory category : values()) {
            if (category.code.equals(code)) {
                return category;
            }
        }
        return null;
    }
}
