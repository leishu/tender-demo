package org.example.controller;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.model.req.ChapterRequest;
import org.example.parsing.model.TenderRequirements;
import org.example.parsing.model.dto.TenderRequirementSummary;
import org.example.parsing.model.req.TenderRequest;
import org.example.parsing.service.TenderRequirementsService;
import org.example.service.IdempotencyStore;
import org.example.service.RuntimeContextBuilder;
import org.example.task.model.TaskType;
import org.example.task.model.dto.TaskEvent;
import org.example.task.model.dto.TenderTaskCreated;
import org.example.task.model.dto.TenderTaskView;
import org.example.task.service.TenderTaskService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@Tag(name = "招标文件解析与投标生成")
@RestController
@RequestMapping("/tender")
public class TenderController {

    private final ObjectProvider<HarnessAgent.Builder> builderProvider;
    private final TenderRequirementsService requirementService;
    private final RuntimeContextBuilder contextBuilder;
    private final IdempotencyStore idempotencyStore;
    private final TenderTaskService taskService;

    public TenderController(ObjectProvider<HarnessAgent.Builder> builderProvider,
                            TenderRequirementsService requirementService,
                            RuntimeContextBuilder contextBuilder,
                            IdempotencyStore idempotencyStore,
                            TenderTaskService taskService) {
        this.builderProvider = builderProvider;
        this.requirementService = requirementService;
        this.contextBuilder = contextBuilder;
        this.idempotencyStore = idempotencyStore;
        this.taskService = taskService;
    }

    @Operation(summary = "创建招标文件解析任务",
            description = """
                    创建解析任务并**立即返回** taskId，模型调用在后台执行；进度通过
                    `GET /tender/tasks/{taskId}/events` 订阅。解析耗时通常几十秒，把结果放在 POST 的
                    响应里会让一次网络抖动就丢掉全部已产出的内容，因此改为“建任务 + 订阅事件”。

                    ### 请求体
                    - `userId`：用户ID（可选）
                    - `tenderText`：招标文件原始文本（必填）

                    ### 幂等
                    可携带 `Idempotency-Key` 请求头（建议 UUID）。同一 key 重复提交返回**同一个
                    taskId**，不会重复调用模型；想重新解析请换一个新 key。

                    ### 响应
                    - `taskId`：任务ID，用于订阅事件与查询状态
                    - `type`：固定为 `parse`
                    - `status`：`pending` / `running` / `succeeded` / `failed` / `cancelled`
                    - `eventsUrl`：事件订阅地址

                    ### 失败说明
                    - `tenderText` 为空返回 400 Bad Request。
                    - 解析本身的失败不体现在状态码上，而是通过事件流中的 `error` 事件 + 任务状态
                      `failed` 表达。
                    """)
    @PostMapping("/parse")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<TenderTaskCreated> parseTender(
            @RequestBody TenderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Mono.fromCallable(() -> taskService.createParseTask(request, idempotencyKey))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "订阅解析任务事件（SSE，可断线续传）",
            description = """
                    先回放已落库的事件，再订阅实时事件；连接断开后带上已收到的最后一条 `id` 重连即可续上，
                    不会重复、不会遗漏，也不会重新解析。

                    ### 续传游标
                    - 请求头 `Last-Event-ID`（浏览器原生 EventSource 自动携带）
                    - 或查询参数 `lastEventId=N`
                    两者都带时取较大值；缺省为 0，即从头回放。

                    ### 响应说明
                    - 采用 `text/event-stream` 协议；每条事件的 `id` 为任务内递增序号，
                      `event` 为模块名，`data` 为 JSON。
                    - 每条 `data` 统一为 `{"module":"<模块名>","data":{...}}`，与改造前一致。
                    - 任务结束时会推送 `completed`（成功）或 `error`（失败/取消）后关闭连接；
                      若任务已结束，回放完历史事件即关闭。

                    ### data 结构（按 module 分派）
                    | module | 说明 | data 关键字段 |
                    |--------|------|--------------|
                    | `project` | 招标总体要求 | projectName、location、duration、budget |
                    | `qualification` | 资质要求 | type、level、validRequirement、source |
                    | `performance` | 业绩要求 | projectType、quantity、amount、timeRange、source |
                    | `personnel` | 关键人员要求 | role、quantity、certificate、source |
                    | `scoringItem` | 评分点 | name、score、criterion、suggestedChapter、source |
                    | `error` | 解析失败/取消（无 data，请看 message） | message |
                    | `completed` | 结束信号（无 data） | - |

                    ### 失败说明
                    任务不存在时同样返回 200 + 一条 `error` 事件，避免 SSE 响应头已提交后无法改状态码。
                    """)
    @GetMapping(value = "/tasks/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamTaskEvents(
            @Parameter(description = "任务ID") @PathVariable("taskId") long taskId,
            @Parameter(description = "已收到的最大事件序号，缺省 0 表示从头回放")
            @RequestParam(value = "lastEventId", required = false) Integer lastEventId,
            @Parameter(description = "同 lastEventId，SSE 原生重连会自动携带")
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventIdHeader) {
        int cursor = resolveCursor(lastEventId, lastEventIdHeader);
        return taskService.stream(taskId, cursor)
                .map(TenderController::toServerSentEvent)
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "查询任务状态",
            description = """
                    查询任务的当前状态与最终结果，用于事件流断开后判断“任务是否还在跑”。

                    ### 响应
                    - `status`：`pending` / `running` / `succeeded` / `failed` / `cancelled`
                    - `lastEventId`：已落库的最大事件序号，可直接作为续传游标
                    - `errorMessage`：失败或取消原因
                    - `result`：目录/章节任务的缓存结果；解析任务为空（结果由事件承载）

                    ### 失败说明
                    任务不存在返回 404 Not Found。
                    """)
    @GetMapping("/tasks/{taskId}")
    public Mono<TenderTaskView> getTask(
            @Parameter(description = "任务ID") @PathVariable("taskId") long taskId) {
        return Mono.fromCallable(() -> taskService.getTask(taskId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "取消任务",
            description = """
                    取消仍在执行的解析任务：断开上游以停止继续消耗模型 token，补充一条 `error` 事件
                    （message 为“解析已取消”）并把状态置为 `cancelled`。

                    ### 响应说明
                    - 204 No Content：已受理；任务已处于终态时同样返回 204（幂等）。
                    - 任务不存在返回 404 Not Found。
                    """)
    @PostMapping("/tasks/{taskId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> cancelTask(
            @Parameter(description = "任务ID") @PathVariable("taskId") long taskId) {
        return Mono.fromRunnable(() -> taskService.cancel(taskId))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * 确认解析结果并入库存档，返回入库后的主键 id
     */
    @Operation(summary = "确认解析结果并入库",
            description = """
                    将确认后的解析结果写入数据库，并返回入库后的主键 id。

                    ### 幂等
                    可携带 `Idempotency-Key` 请求头（建议使用 UUID）。同一 key 在有效期内重复提交
                    只会入库一次，并返回首次创建的主键 id，避免重复点击或网络重试产生重复数据。
                    """)
    @PostMapping("/parse/confirm")
    public Mono<Long> confirmTender(
            @RequestBody TenderRequirements requirements,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Mono.fromCallable(() -> idempotencyStore.getOrCreate(idempotencyKey,
                        () -> requirementService.save(requirements).getId()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "查询已解析的招标文件列表",
            description = """
                    查询已确认入库的招标文件（招标要求），按录入时间倒序返回，供列表页展示并选择后续操作。

                    ### 参数
                    - `keyword`：项目名称模糊匹配，可选
                    - `limit`：返回条数上限，默认 50，最大 200

                    ### 响应说明
                    - `id`：招标要求主键，人员/资质匹配、目录生成都使用该 id
                    - `projectName` / `location` / `duration` / `budget`：解析出的项目概况
                    - `recordTime`：录入时间（epoch 毫秒），由服务端在入库时写入
                    - `qualificationCount` / `performanceCount` / `personnelCount` / `scoringItemCount`：各类要求条数
                    - `hasCatalog`：是否已保存过投标目录

                    说明：列表按 `record_time` 倒序，同一毫秒录入的多条按主键倒序兜底。
                    """)
    @GetMapping("/requirements")
    public Mono<List<TenderRequirementSummary>> listRequirements(
            @Parameter(description = "项目名称模糊匹配关键字")
            @RequestParam(value = "keyword", required = false) String keyword,
            @Parameter(description = "返回条数上限，默认 50，最大 200")
            @RequestParam(value = "limit", required = false, defaultValue = "50") Integer limit) {
        return Mono.fromCallable(() -> requirementService.list(keyword, limit))
                .subscribeOn(Schedulers.boundedElastic());
    }


    @Operation(summary = "撰写投标章节",
            description = """
                    根据章节标题与关联的评分点撰写投标章节内容。

                    ### 幂等
                    可携带 `Idempotency-Key` 请求头。同一 key 命中已完成的任务时直接返回缓存正文，
                    不重复调用模型；命中执行中的任务返回 409。
                    """)
    @PostMapping("/chapter")
    public Mono<String> generateChapter(@RequestBody ChapterRequest request,
                                        @RequestHeader(value = "Idempotency-Key", required = false)
                                        String idempotencyKey) {
        // 会话在真正需要调用模型时才创建：命中缓存时不消耗 sessionId，也不 build agent
        return taskService.runOnce(TaskType.CHAPTER,
                request.getUserId(),
                idempotencyKey,
                request,
                String.class,
                () -> {
                    RuntimeContext ctx = contextBuilder.build(request.getUserId());
                    HarnessAgent.Builder builder = builderProvider.getObject();
                    return Mono.using(
                            builder::build,
                            agent -> agent.call(
                                    new UserMessage(String.format(
                                            "撰写章节“%s”，对应评分点：%s",
                                            request.getChapterTitle(), request.getScoringPointIds()
                                    )),
                                    ctx
                            ).map(Msg::getTextContent),
                            HarnessAgent::close);
                });
    }

    /** SSE 事件：id 为任务内递增序号，event 为模块名，data 为事件 JSON 原文 */
    private static ServerSentEvent<String> toServerSentEvent(TaskEvent event) {
        return ServerSentEvent.<String>builder()
                .id(String.valueOf(event.seq()))
                .event(event.eventType())
                .data(event.payload())
                .build();
    }

    /** 续传游标：查询参数与 Last-Event-ID 取较大值，缺省从头回放 */
    private static int resolveCursor(Integer queryValue, String headerValue) {
        int fromQuery = queryValue == null ? 0 : Math.max(queryValue, 0);
        return Math.max(fromQuery, parseCursor(headerValue));
    }

    private static int parseCursor(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(raw.trim()), 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
