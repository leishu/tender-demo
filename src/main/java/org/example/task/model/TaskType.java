package org.example.task.model;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 招标任务类型。
 *
 * <p>以 {@link #code} 作为落库值（t_tender_task.task_type）与 JSON 序列化值。</p>
 */
@Schema(enumAsRef = true)
public enum TaskType {

    /** 招标文件解析：流式任务，结果由事件承载 */
    PARSE("parse", "招标文件解析", true),
    /** 人员与企业资质匹配：流式任务，结果由事件承载（人员/资质两路交错） */
    MATCH("match", "人员与资质匹配", true),
    /** 投标目录生成：同步任务，最终结果可缓存复用 */
    CATALOG("catalog", "投标目录生成", false),
    /** 投标目录调整：同步任务，最终结果可缓存复用 */
    CATALOG_ADJUST("catalog_adjust", "投标目录调整", false),
    /** 投标章节撰写：同步任务，最终结果可缓存复用 */
    CHAPTER("chapter", "投标章节撰写", false);

    @EnumValue
    private final String code;

    private final String label;

    /** 是否为流式任务（产出事件）；同步任务只缓存最终结果，不写事件表 */
    private final boolean streaming;

    TaskType(String code, String label, boolean streaming) {
        this.code = code;
        this.label = label;
        this.streaming = streaming;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public boolean isStreaming() {
        return streaming;
    }

    /** 根据字符串值反查枚举；无法匹配时返回 {@code null} */
    public static TaskType fromCode(String code) {
        for (TaskType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
