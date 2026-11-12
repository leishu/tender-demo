package org.example.task.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.task.model.TaskStatus;
import org.example.task.model.TaskType;

/**
 * 任务状态视图：不含事件明细，只给“任务跑到哪了、结果还在不在”。
 *
 * <p>前端在事件流断开后用它判断任务是否已经结束：已结束就直接展示结果，
 * 仍在执行则继续带 {@code lastEventId} 重连。</p>
 */
@Schema(description = "任务状态")
public record TenderTaskView(
        @Schema(description = "任务ID") Long taskId,
        @Schema(description = "任务类型") TaskType type,
        @Schema(description = "任务状态") TaskStatus status,
        @Schema(description = "已落库的最大事件序号，可作为 lastEventId 续传游标") Integer lastEventId,
        @Schema(description = "失败或取消原因；成功时为空") String errorMessage,
        @Schema(description = "可缓存的最终结果（目录/章节）；解析任务为空，结果由事件承载") Object result,
        @Schema(description = "创建时间(epoch ms)") Long createdAt,
        @Schema(description = "更新时间(epoch ms)") Long updatedAt,
        @Schema(description = "进入终态的时间(epoch ms)") Long finishedAt
) {
}
