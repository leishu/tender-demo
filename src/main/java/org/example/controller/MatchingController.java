package org.example.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.matching.model.req.MatchRequest;
import org.example.matching.service.TenderMatchingService;
import org.example.task.model.TaskType;
import org.example.task.model.dto.TenderTaskCreated;
import org.example.task.service.TaskStream;
import org.example.task.service.TenderTaskService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Tag(name = "人员与企业资质匹配")
@RestController
@RequestMapping("/tender")
public class MatchingController {

    private final TenderTaskService taskService;
    private final TenderMatchingService matchingService;

    public MatchingController(TenderTaskService taskService, TenderMatchingService matchingService) {
        this.taskService = taskService;
        this.matchingService = matchingService;
    }

    @Operation(summary = "创建人员与企业资质匹配任务",
            description = """
                    创建匹配任务并**立即返回** taskId，两路子 Agent 在后台并发执行；匹配进度与结果
                    通过 `GET /tender/tasks/{taskId}/events` 订阅（与解析共用同一套任务与事件接口）。

                    ### 请求体
                    - `userId`：用户ID（可选）
                    - `tenderRequirementId`：已入库的招标要求主键（必填）

                    ### 幂等
                    可携带 `Idempotency-Key` 请求头（建议 UUID）。同一 key 重复提交返回**同一个
                    taskId**，不会让两路匹配再跑一遍；想重新匹配请换一个新 key。

                    ### 响应
                    - `taskId`：任务ID，用于订阅事件与查询状态
                    - `type`：固定为 `match`
                    - `status`：`pending` / `running` / `succeeded` / `failed` / `cancelled`
                    - `eventsUrl`：事件订阅地址

                    ### 事件说明（data 结构）
                    | module | 说明 | data |
                    |--------|------|------|
                    | `personnel` | 人员匹配结果 | `PersonnelMatchResult` |
                    | `qualification` | 企业资质匹配结果 | `QualificationMatchResult` |
                    | `error` | 某一路失败/缺条（**非终止**，另一路继续） | 事件带 message |
                    | `completed` | 全部结束（成功终止信号） | - |

                    人员与资质两路并行，事件可能交错到达；每匹配完一条要求就落库一条，断线重连带
                    `Last-Event-ID` 即可续上，不会重复也不会遗漏。

                    ### 失败说明
                    - `tenderRequirementId` 为空返回 400 Bad Request。
                    - 招标要求不存在返回 404 Not Found。
                    - 匹配本身的失败不体现在状态码上，而是通过 event 流中的 `error` 事件 + 任务状态
                      `failed` 表达。
                    """)
    @PostMapping("/match")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<TenderTaskCreated> match(
            @RequestBody MatchRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Mono.fromCallable(() -> {
            // 先校验、后查幂等键：参数错误（400/404）始终如实暴露，不会被幂等命中掩盖。
            // 副作用是“建任务后招标要求被删”时同 key 重试用会拿到 404 而不是原任务——
            // 窗口极小的边界情况，可以接受。
            TaskStream stream = matchingService.taskStream(request);
            return taskService.createStreamingTask(TaskType.MATCH, request.getUserId(),
                    idempotencyKey, request, stream);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
