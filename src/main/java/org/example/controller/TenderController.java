package org.example.controller;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.example.model.catalog.dto.CatalogResult;
import org.example.model.req.CatalogAdjustRequest;
import org.example.model.req.CatalogRequest;
import org.example.model.req.ChapterRequest;
import org.example.model.req.TenderRequest;
import org.example.model.require.TenderRequirements;
import org.example.model.resp.TenderParseEvent;
import org.example.prompts.CatalogPromptProvider;
import org.example.service.CatalogResultParser;
import org.example.service.RuntimeContextBuilder;
import org.example.service.TenderParseEventTransformer;
import org.example.service.TenderRequirementsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Tag(name = "招标文件解析与投标生成")
@RestController
@RequestMapping("/tender")
public class TenderController {

    private final ObjectProvider<HarnessAgent.Builder> builderProvider;
    private final TenderRequirementsService requirementService;
    private final TenderParseEventTransformer eventTransformer;
    private final RuntimeContextBuilder contextBuilder;
    private final CatalogPromptProvider catalogPromptProvider;
    private final CatalogResultParser catalogResultParser;

    public TenderController(ObjectProvider<HarnessAgent.Builder> builderProvider,
                            TenderRequirementsService requirementService,
                            TenderParseEventTransformer eventTransformer,
                            RuntimeContextBuilder contextBuilder,
                            CatalogPromptProvider catalogPromptProvider,
                            CatalogResultParser catalogResultParser) {
        this.builderProvider = builderProvider;
        this.requirementService = requirementService;
        this.eventTransformer = eventTransformer;
        this.contextBuilder = contextBuilder;
        this.catalogPromptProvider = catalogPromptProvider;
        this.catalogResultParser = catalogResultParser;
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
        return Mono.fromCallable(() -> requirementService.save(requirements));
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
                    - 模型未返回可解析的 JSON 或 JSON 不合法时返回 502 Bad Gateway。
                    """)
    @PostMapping("/catalog")
    public Mono<CatalogResult> generateCatalog(@RequestBody CatalogRequest request) {
        return runCatalogGeneration(request.getUserId(),
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
                    - 模型未返回可解析的 JSON 或 JSON 不合法时返回 502 Bad Gateway。
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
        return runCatalogGeneration(request.getUserId(),
                catalogPromptProvider.renderAdjustment(request.getTenderRequirementId(),
                        request.getPreviousCatalog(), request.getCustomPrompt()));
    }

    /** 目录生成与调整共用的执行流程：新建会话、发送提示词、解析目录 JSON。 */
    private Mono<CatalogResult> runCatalogGeneration(String userId, String prompt) {
        RuntimeContext ctx = contextBuilder.build(userId);

        HarnessAgent.Builder builder = builderProvider.getObject();

        return Mono.using(
                builder::build,
                agent -> agent.call(new UserMessage(prompt), ctx)
                        .map(Msg::getTextContent)
                        .map(catalogResultParser::parse),
                HarnessAgent::close
        );
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