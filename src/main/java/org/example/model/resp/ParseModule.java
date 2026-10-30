package org.example.model.resp;

import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 招标文件解析事件模块名。
 *
 * <p>以 {@code code} 作为序列化值（对应 SSE 事件中的 {@code module} 字段），
 * 同时提供 {@link #fromCode(String)} 从字符串反查枚举。</p>
 */
@Schema(enumAsRef = true)
public enum ParseModule {

    PROJECT("project"),
    QUALIFICATION("qualification"),
    PERFORMANCE("performance"),
    PERSONNEL("personnel"),
    SCORING_ITEM("scoringItem"),
    ERROR("error"),
    COMPLETED("completed");

    private final String code;

    ParseModule(String code) {
        this.code = code;
    }

    /** 序列化为 {@code module} 字段的实际字符串值 */
    @JsonValue
    public String getCode() {
        return code;
    }

    /** 根据字符串值反查枚举；无法匹配时返回 {@code null} */
    public static ParseModule fromCode(String code) {
        for (ParseModule m : values()) {
            if (m.code.equals(code)) {
                return m;
            }
        }
        return null;
    }
}