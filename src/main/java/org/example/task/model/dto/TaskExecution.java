package org.example.task.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 同步任务的一次执行结果：业务值 + 本次使用的会话 ID。
 *
 * <p>目录生成/调整、章节撰写这些接口不返回任务对象，客户端拿不到 {@code sessionId}，
 * 也就无法续写。这里把会话 ID 和结果一起交给控制器，由它写成
 * {@code X-Session-Id} 响应头。</p>
 *
 * @param value     业务结果
 * @param sessionId 本次任务使用的 Agent 会话 ID（服务端签发）；幂等命中时是原任务的会话
 */
@Schema(description = "同步任务执行结果")
public record TaskExecution<T>(
        @Schema(description = "业务结果") T value,
        @Schema(description = "本次会话ID，续写时原样回传", example = "2026-11-13-09-3")
        String sessionId
) {
}
