package org.example.controller;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
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
import org.example.task.model.TaskType;
import org.example.task.service.TenderTaskService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@Tag(name = "投标文件目录")
@RestController
@RequestMapping("/tender")
public class CatalogController {
    private final CatalogPromptProvider catalogPromptProvider;
    private final CatalogGenerationService catalogGenerationService;
    private final BidCatalogService bidCatalogService;
    private final TenderTaskService taskService;

    public CatalogController(CatalogPromptProvider catalogPromptProvider,
                             BidCatalogService bidCatalogService,
                             CatalogGenerationService catalogGenerationService,
                             TenderTaskService taskService) {
        this.catalogPromptProvider = catalogPromptProvider;
        this.bidCatalogService = bidCatalogService;
        this.catalogGenerationService = catalogGenerationService;
        this.taskService = taskService;
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

                    ### 幂等（结果缓存）
                    可携带 `Idempotency-Key` 请求头。同一 key 命中已完成的任务时**直接返回缓存的目录**，
                    不再调用模型；命中执行中的任务返回 409 Conflict。
                    失败后重试同一个 key 会复用原任务重跑，不会新建任务。
                    """)
    @PostMapping("/catalog")
    public Mono<CatalogResult> generateCatalog(
            @RequestBody CatalogRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        // 提示词在真正调用模型时才渲染：命中缓存时连提示词都不必拼
        return taskService.runOnce(TaskType.CATALOG,
                request.getUserId(),
                idempotencyKey,
                request,
                CatalogResult.class,
                () -> catalogGenerationService.generate(request.getUserId(),
                        catalogPromptProvider.render(request.getTenderRequirementId())));
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

                    ### 幂等（结果缓存）
                    可携带 `Idempotency-Key` 请求头。同一 key 命中已完成的任务时直接返回缓存的目录，
                    不再调用模型；命中执行中的任务返回 409 Conflict。
                    """)
    @PostMapping("/catalog/adjust")
    public Mono<CatalogResult> adjustCatalog(
            @RequestBody CatalogAdjustRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (request.getPreviousCatalog() == null) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "上一轮生成的目录 previousCatalog 不能为空"));
        }
        if (request.getCustomPrompt() == null || request.getCustomPrompt().isBlank()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "用户调整要求 customPrompt 不能为空"));
        }
        return taskService.runOnce(TaskType.CATALOG_ADJUST,
                request.getUserId(),
                idempotencyKey,
                request,
                CatalogResult.class,
                () -> catalogGenerationService.generate(request.getUserId(),
                        adjustmentBlocks(catalogPromptProvider.renderAdjustment(
                                request.getTenderRequirementId(),
                                request.getPreviousCatalog(), request.getCustomPrompt()))));
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
}
