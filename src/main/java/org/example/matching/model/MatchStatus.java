package org.example.matching.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Locale;

/**
 * 单条招标要求与企业数据的匹配状态。
 */
@Schema(enumAsRef = true)
public enum MatchStatus {

    /** 要求全部满足 */
    MATCHED,
    /** 部分满足（如证书过期、等级不足、人数不够） */
    PARTIAL,
    /** 没有可用数据 */
    UNMATCHED;

    /** 容错反序列化：模型输出大小写不一致或未知值时按未匹配处理 */
    @JsonCreator
    public static MatchStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return UNMATCHED;
        }
        try {
            return valueOf(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNMATCHED;
        }
    }
}
