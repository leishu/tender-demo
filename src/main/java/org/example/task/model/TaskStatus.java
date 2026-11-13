package org.example.task.model;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 任务状态。
 *
 * <p>状态流转：{@code running → succeeded / failed / cancelled}，终态不再变化。
 * 落库值与 JSON 序列化值统一为小写 {@link #code}。</p>
 *
 * <p>{@link #PENDING} 在本服务里<b>没有产生路径</b>：任务建行时直接就是 {@code running}
 * （见 {@code TenderTaskService#newTask}），没有排队/入队阶段。它只为两类历史数据保留：
 * 早期版本写入的行，以及外部直接写库的行；查询与状态流转里带上它只是兼容，
 * 不要把它当成正常流转的一环。</p>
 */
@Schema(enumAsRef = true)
public enum TaskStatus {

    PENDING("pending", "待执行"),
    RUNNING("running", "执行中"),
    SUCCEEDED("succeeded", "已完成"),
    FAILED("failed", "失败"),
    CANCELLED("cancelled", "已取消");

    @EnumValue
    private final String code;

    private final String label;

    TaskStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /** 终态：任务不会再产出新事件，事件流回放完即可关闭 */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
