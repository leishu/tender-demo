package org.example.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.knowledge.model.KnowledgeSearchHit;
import org.example.knowledge.service.KnowledgeBaseService;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@Tag(name = "知识库检索", description = "基于 Elasticsearch 的向量 + 全文混合检索")
@RestController
@RequestMapping("/knowledge")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Operation(summary = "批量保存知识库文本",
            description = """
                    把一组文本写入指定名称的知识库，写入时同步生成文本向量。
                    知识库不存在时会自动创建。

                    ### 参数
                    - `knowledgeBaseName`：知识库名称（路径参数，必填）

                    ### 请求体
                    - 直接传字符串数组，例如 `["文本1", "文本2"]`
                    - 空白字符串会被忽略

                    ### 响应
                    - 返回知识库的数据库自增主键 ID
                    """)
    @PostMapping("/{knowledgeBaseName}")
    public Mono<Long> save(
            @Parameter(description = "知识库名称", example = "tender-docs")
            @PathVariable("knowledgeBaseName") String knowledgeBaseName,
            @RequestBody List<String> contents) {
        return Mono.fromCallable(() -> knowledgeBaseService.saveAll(knowledgeBaseName, contents))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "混合检索知识库",
            description = """
                    同时执行全文检索（BM25）与向量检索（kNN），并用 RRF 融合排序，
                    默认返回最相关的前 10 条。

                    ### 参数
                    - `knowledgeBaseName`：知识库名称，可重复传入指定多个；不传则跨所有知识库检索
                    - `query`：查询内容（必填）
                    - `topK`：返回条数，默认 10，最大 50

                    ### 说明
                    - 查询向量生成失败时自动降级为纯全文检索
                    - 未配置 `knowledge-base.embedding.api-key` 时同样只走全文检索
                    """)
    @GetMapping("/search")
    public Mono<List<KnowledgeSearchHit>> search(
            @Parameter(description = "知识库名称，可重复传入多个；不传则跨所有库检索", example = "tender-docs")
            @RequestParam(value = "knowledgeBaseName", required = false) List<String> knowledgeBaseNames,
            @Parameter(description = "查询内容", example = "投标人需要具备什么资质")
            @RequestParam("query") String query,
            @Parameter(description = "返回条数，默认 10，最大 50")
            @RequestParam(value = "topK", defaultValue = "10") int topK) {

        if (!StringUtils.hasText(query)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "query 不能为空"));
        }

        return Mono.fromCallable(() -> knowledgeBaseService.search(knowledgeBaseNames, query, topK))
                .subscribeOn(Schedulers.boundedElastic());
    }
}