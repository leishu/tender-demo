package org.example.matching.model.dto;

import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 匹配事件模块名。
 *
 * <p>以 {@code code} 作为 SSE 事件中 {@code module} 字段的序列化值；
 * {@code entityType} 用于识别子 Agent 返回的 JSON 对象类型。</p>
 */
@Schema(enumAsRef = true)
public enum MatchModule {

    PERSONNEL("personnel", "personnelMatch"),
    QUALIFICATION("qualification", "qualificationMatch"),
    ERROR("error", null),
    COMPLETED("completed", null);

    private final String code;
    private final String entityType;

    MatchModule(String code, String entityType) {
        this.code = code;
        this.entityType = entityType;
    }

    /** 序列化为 {@code module} 字段的实际字符串值 */
    @JsonValue
    public String getCode() {
        return code;
    }

    /** 子 Agent 输出 JSON 中约定的 {@code entityType} 判别值 */
    public String getEntityType() {
        return entityType;
    }
}
