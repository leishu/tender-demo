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
import org.example.parsing.model.dto.TenderParseEvent;
import org.example.parsing.model.dto.TenderRequirementSummary;
import org.example.parsing.model.req.TenderRequest;
import org.example.parsing.service.TenderParseEventTransformer;
import org.example.parsing.service.TenderRequirementsService;
import org.example.service.RuntimeContextBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final TenderParseEventTransformer eventTransformer;
    private final RuntimeContextBuilder contextBuilder;

    public TenderController(ObjectProvider<HarnessAgent.Builder> builderProvider,
                            TenderRequirementsService requirementService,
                            TenderParseEventTransformer eventTransformer,
                            RuntimeContextBuilder contextBuilder) {
        this.builderProvider = builderProvider;
        this.requirementService = requirementService;
        this.eventTransformer = eventTransformer;
        this.contextBuilder = contextBuilder;
    }

    @Operation(summary = "解析招标文件",
            description = """
                    解析招标文件文本，实时提取结构化要求，并以 SSE（Server-Sent Events）流式返回解析进度与结果。

                    ### 请求体
                    - `userId`：用户ID（可选）
                    - `sessionId`：会话ID（可选）
                    - `tenderText`：招标文件原始文本（必填）

                    ### 响应说明
                    - 采用 `text/event-stream` 协议，客户端需按 SSE 逐条消费 `data` 行。
                    - 每条数据是一个 JSON 对象，统一为 `{"module":"<模块名>","data":{...}}`。
                    - 解析结束会推送一条 `{"module":"completed"}` 信号；此后连接关闭，客户端应停止接收。
                    - 解析过程中若服务端异常，会推送一条 `{"module":"error","message":"..."}` 事件后正常结束流，客户端应据此提示用户。

                    ### data 结构（按 module 分派）
                    | module | 说明 | data 关键字段 |
                    |--------|------|--------------|
                    | `project` | 招标总体要求 | projectName、location、duration、budget |
                    | `qualification` | 资质要求 | type、level、validRequirement、source |
                    | `performance` | 业绩要求 | projectType、quantity、amount、timeRange、source |
                    | `personnel` | 关键人员要求 | role、quantity、certificate、source |
                    | `scoringItem` | 评分点 | name、score、criterion、suggestedChapter、source |
                    | `error` | 解析失败（无 data，请看 message） | message |
                    | `completed` | 结束信号（无 data） | - |

                    说明：同类型实体可能推送多条（如同一投标人要求拆成 2 条资质、4 条评分点）；各条均携带 `source` 原文出处片段便于复核。""")
    @PostMapping("/parse")
    public Flux<TenderParseEvent<?>> parseTender(@RequestBody TenderRequest request) {
        RuntimeContext ctx = contextBuilder.build(request.getUserId());
        UserMessage prompt = new UserMessage(
                "请解析以下招标文件，提取结构化要求：\n" + request.getTenderText());

        HarnessAgent.Builder builder = builderProvider.getObject();

        return Flux.using(
                builder::build,
                agent -> eventTransformer.toSse(agent.streamEvents(prompt, ctx)),
                HarnessAgent::close
        );
    }

    /**
     * 确认解析结果并入库存档，返回入库后的主键 id
     */
    @Operation(summary = "确认解析结果并入库",
            description = "将确认后的解析结果写入数据库，并返回入库后的主键 id。")
    @PostMapping("/parse/confirm")
    public Mono<Long> confirmTender(@RequestBody TenderRequirements requirements) {
        return Mono.fromCallable(() -> requirementService.save(requirements).getId());
    }

    @Operation(summary = "查询已解析的招标文件列表",
            description = """
                    查询已确认入库的招标文件（招标要求），按主键倒序返回，供列表页展示并选择后续操作。

                    ### 参数
                    - `keyword`：项目名称模糊匹配，可选
                    - `limit`：返回条数上限，默认 50，最大 200

                    ### 响应说明
                    - `id`：招标要求主键，人员/资质匹配、目录生成都使用该 id
                    - `projectName` / `location` / `duration` / `budget`：解析出的项目概况
                    - `qualificationCount` / `performanceCount` / `personnelCount` / `scoringItemCount`：各类要求条数
                    - `hasCatalog`：是否已保存过投标目录

                    说明：主表未记录入库时间，列表以自增主键倒序近似“最近入库在前”。
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
            description = "根据章节标题与关联的评分点撰写投标章节内容。")
    @PostMapping("/chapter")
    public Mono<String> generateChapter(@RequestBody ChapterRequest request) {
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
                HarnessAgent::close
        );
    }
}