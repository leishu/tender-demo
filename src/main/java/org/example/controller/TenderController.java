package org.example.controller;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.catalog.model.dto.CatalogResult;
import org.example.catalog.model.req.CatalogAdjustRequest;
import org.example.catalog.model.req.CatalogRequest;
import org.example.catalog.model.req.CatalogSaveRequest;
import org.example.catalog.prompt.CatalogPromptProvider;
import org.example.catalog.service.BidCatalogService;
import org.example.catalog.service.CatalogGenerationService;
import org.example.model.req.ChapterRequest;
import org.example.parsing.model.TenderRequirements;
import org.example.parsing.model.dto.TenderParseEvent;
import org.example.parsing.model.req.TenderRequest;
import org.example.parsing.service.TenderParseEventTransformer;
import org.example.parsing.service.TenderRequirementsService;
import org.example.service.RuntimeContextBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
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
    private final CatalogPromptProvider catalogPromptProvider;
    private final CatalogGenerationService catalogGenerationService;
    private final BidCatalogService bidCatalogService;

    public TenderController(ObjectProvider<HarnessAgent.Builder> builderProvider,
                            TenderRequirementsService requirementService,
                            TenderParseEventTransformer eventTransformer,
                            RuntimeContextBuilder contextBuilder,
                            CatalogPromptProvider catalogPromptProvider,
                            CatalogGenerationService catalogGenerationService,
                            BidCatalogService bidCatalogService) {
        this.builderProvider = builderProvider;
        this.requirementService = requirementService;
        this.eventTransformer = eventTransformer;
        this.contextBuilder = contextBuilder;
        this.catalogPromptProvider = catalogPromptProvider;
        this.catalogGenerationService = catalogGenerationService;
        this.bidCatalogService = bidCatalogService;
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
            description = "将确认后的解析结果写入数据库，并返回入库后的主键 id。未传入 enterpriseId 时由服务端归入默认企业。")
    @PostMapping("/parse/confirm")
    public Mono<Long> confirmTender(@RequestBody TenderRequirements requirements) {
        return Mono.fromCallable(() -> requirementService.save(requirements).getId());
    }

    @Operation(summary = "生成投标目录",
            description = """
                    根据招标文件解析结果生成最多三级的投标目录，返回结构化 JSON。

                    ### 请求体
                    - `userId`：用户ID（可选）
                    - `tenderRequirementId`：招标要求主键（必填）

                    ### 响应说明
                    - `catalog`：合并目录，顶层为一级章节，子章节在 `children` 中，最多三级；商务/技术分列时为空
                    - `businessCatalog` / `technicalCatalog`：商务标、技术标两套目录，仅分列时返回，此时不含 `catalog`
                    - `structureDecision`：结构判断说明（合并目录 / 商务技术分列及依据）
                    - `gaps`：缺口说明，无缺口时为空数组

                    ### 失败说明
                    - 返回的目录已通过保存前校验（章节编号、必填字段、列宽），可直接调用 `/tender/catalog/save`。
                    - 校验前会先做确定性修正：章节编号按嵌套位置重排，内容提示、结构判断说明、缺口详情
                      超出列宽时截断，因此编号写错或文本略长不会再触发重新生成。
                    - 仍不合法（JSON 无法解析、标题为空、层级被压平等）时，把失败原因回传模型重新生成一次；
                      重试后仍失败返回 502 Bad Gateway。
                    """)
    @PostMapping("/catalog")
    public Mono<CatalogResult> generateCatalog(@RequestBody CatalogRequest request) {
        return catalogGenerationService.generate(request.getUserId(),
                catalogPromptProvider.render(request.getTenderRequirementId()));
    }

    @Operation(summary = "调整投标目录",
            description = """
                    在上一轮生成的投标目录基础上，根据用户调整要求重新生成完整目录，返回结构化 JSON。

                    ### 请求体
                    - `userId`：用户ID（可选）
                    - `tenderRequirementId`：招标要求主键（必填）
                    - `previousCatalog`：上一轮生成的目录结果（必填），原样传入 `/tender/catalog` 或本接口上一轮的响应体
                    - `customPrompt`：用户调整要求（必填），如调整章节顺序、拆分/合并章节、改名、增删章节

                    ### 响应说明
                    与 `/tender/catalog` 一致：`catalog` 或 `businessCatalog` / `technicalCatalog`、`structureDecision`、`gaps`。
                    用户未要求修改的章节会保留原编号与内容提示，仅按要求部分调整并重排编号。

                    ### 失败说明
                    - `previousCatalog` 或 `customPrompt` 为空时返回 400 Bad Request。
                    - 返回的目录已通过保存前校验，可直接调用 `/tender/catalog/save`。
                    - 校验前会先做确定性修正：章节编号按嵌套位置重排，内容提示、结构判断说明、缺口详情
                      超出列宽时截断，因此编号写错或文本略长不会再触发重新生成。
                    - 仍不合法（JSON 无法解析、标题为空、层级被压平等）时，把失败原因回传模型重新生成一次；
                      重试后仍失败返回 502 Bad Gateway。
                    """)
    @PostMapping("/catalog/adjust")
    public Mono<CatalogResult> adjustCatalog(@RequestBody CatalogAdjustRequest request) {
        if (request.getPreviousCatalog() == null) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "上一轮生成的目录 previousCatalog 不能为空"));
        }
        if (request.getCustomPrompt() == null || request.getCustomPrompt().isBlank()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "用户调整要求 customPrompt 不能为空"));
        }
        return catalogGenerationService.generate(request.getUserId(),
                adjustmentBlocks(catalogPromptProvider.renderAdjustment(request.getTenderRequirementId(),
                        request.getPreviousCatalog(), request.getCustomPrompt())));
    }

    @Operation(summary = "获取已保存的投标目录",
            description = """
                    按招标要求查询已保存的投标目录，返回结构与 `/tender/catalog` 一致，可直接用于展示或再次调整。

                    ### 参数
                    - `tenderRequirementId`：招标要求主键（必填）

                    ### 响应说明
                    - `catalog`：合并目录，顶层为一级章节，子章节在 `children` 中，最多三级；商务/技术分列时为空
                    - `businessCatalog` / `technicalCatalog`：商务标、技术标两套目录，仅分列时返回
                    - `structureDecision`：保存时的结构判断说明
                    - `gaps`：缺口说明，无缺口时为空数组

                    ### 失败说明
                    - `tenderRequirementId` 为空时返回 400 Bad Request。
                    - 招标要求不存在，或该招标要求尚未保存目录时返回 404 Not Found。
                    """)
    @GetMapping("/catalog")
    public Mono<CatalogResult> getCatalog(
            @Parameter(description = "招标要求ID", example = "1234567890")
            @RequestParam("tenderRequirementId") Long tenderRequirementId) {
        return Mono.fromCallable(() -> bidCatalogService.find(tenderRequirementId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "保存投标目录",
            description = """
                    将目录生成或调整结果落库。同一个招标要求只保留一份目录，重复保存会覆盖旧目录。

                    ### 请求体
                    - `userId`：用户ID（可选）
                    - `tenderRequirementId`：招标要求主键（必填），须已存在
                    - `catalogResult`：目录结果（必填），原样传入 `/tender/catalog` 或 `/tender/catalog/adjust` 的响应体

                    ### 响应说明
                    - 返回目录批次主键 id（t_bid_catalog_run.id）；章节树、评分点关联与缺口按该批次落库。
                    - 章节按 `children` 嵌套展开为 `parent_id` 自关联，最多三级；超过三级的子节点不落库。
                    - 再次保存同一招标要求时会先删除旧的批次、章节、评分点关联与缺口。

                    ### 失败说明
                    - `tenderRequirementId` 或 `catalogResult` 为空时返回 400 Bad Request。
                    - 数据校验失败时返回 400 Bad Request：
                      - 章节编号需与嵌套位置一致（1、1.1、1.1.1），同级从 1 连续递增，不跳号、不重号、不带前导零；
                      - 章节标题、缺口类型、评分点 id 不能为空；
                      - 字段长度不得超列宽：章节编号 32、标题 64、内容提示 1024、结构判断说明 512、缺口类型 32、缺口详情 512、评分点 id 64。
                    - 招标要求不存在时返回 404 Not Found。
                    """)
    @PostMapping("/catalog/save")
    public Mono<Long> saveCatalog(@RequestBody CatalogSaveRequest request) {
        if (request.getTenderRequirementId() == null) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "招标要求 tenderRequirementId 不能为空"));
        }
        if (request.getCatalogResult() == null) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "目录结果 catalogResult 不能为空"));
        }
        return Mono.fromCallable(() -> bidCatalogService.save(request.getTenderRequirementId(),
                        request.getCatalogResult()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 将调整提示词拆成三个独立的文本内容块：生成规则、上一轮目录、用户调整要求。 */
    private List<ContentBlock> adjustmentBlocks(CatalogPromptProvider.CatalogAdjustPrompt prompt) {
        return List.of(
                TextBlock.builder().text(prompt.rules()).build(),
                TextBlock.builder().text(prompt.previousCatalog()).build(),
                TextBlock.builder().text(prompt.customPrompt()).build());
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