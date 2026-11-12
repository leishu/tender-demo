package org.example.task.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.task.model.TaskStatus;
import org.example.task.model.TaskType;

/**
 * 任务创建结果：只回传任务标识与订阅地址，进度通过事件流获取。
 *
 * <p>解析接口不再把结果压在 POST 的响应里，而是先建任务再订阅事件，
 * 这样 POST 超时、网络抖动、页面刷新都不会丢失已经产生的解析结果。</p>
 */
@Schema(description = "任务创建结果")
public record TenderTaskCreated(
        @Schema(description = "任务ID，用于订阅事件与查询状态", example = "1024")
        Long taskId,

        @Schema(description = "任务类型：parse/catalog/catalog_adjust/chapter")
        TaskType type,

        @Schema(description = "任务状态：pending/running/succeeded/failed/cancelled")
        TaskStatus status,

        @Schema(description = "事件订阅地址（SSE），可带 ?lastEventId=N 从指定位置续传",
                example = "/tender/tasks/1024/events")
        String eventsUrl
) {
}
