package org.example.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.matching.model.dto.MatchEvent;
import org.example.matching.model.req.MatchRequest;
import org.example.matching.service.TenderMatchingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

@Tag(name = "人员与企业资质匹配")
@RestController
@RequestMapping("/tender")
public class MatchingController {

    private final TenderMatchingService matchingService;

    public MatchingController(TenderMatchingService matchingService) {
        this.matchingService = matchingService;
    }

    @Operation(summary = "并发匹配人员与企业资质",
            description = """
                    根据已入库的招标要求，并发执行人员匹配与企业资质匹配，并以 SSE 流式返回匹配结果。

                    ### 请求体
                    - `userId`：用户ID（可选）
                    - `tenderRequirementId`：已入库的招标要求主键（必填）

                    ### 响应说明
                    - 采用 `text/event-stream` 协议，客户端按 SSE 逐条消费 `data` 行。
                    - 每条数据统一为 `{"module":"<模块名>","data":{...}}`。
                    - 人员匹配与资质匹配并行执行；每匹配完一条要求就立即推送一条结果，两路事件可能交错到达。
                    - 人员模块 `module=personnel`，data 为 `PersonnelMatchResult`；
                      资质模块 `module=qualification`，data 为 `QualificationMatchResult`。
                    - 两路全部结束后推送一条 `{"module":"completed"}`，此后连接关闭。
                    - 某个子 Agent 失败时推送 `{"module":"error","message":"..."}`，另一路仍会继续；
                      两路都结束后同样推送 `completed`。

                    ### 匹配状态
                    - `MATCHED`：可核验项全部满足（不代表没有待人工复核项，见下）
                    - `PARTIAL`：部分满足（如证书过期、等级不足、人数不够）
                    - `UNMATCHED`：没有可用数据

                    ### 人工复核标记
                    - `requiresManualReview=true` 表示该条结论需人工复核，客户端应告警，不能当作确定通过。
                    - 资质侧：强制资格 `UNMATCHED` 时必为 true，并带 `risk=mandatory_qualification_missing`。
                    - 人员侧：`unverifiedRequirements` 逐项列出工具无数据、无法核验的招标强制项
                      （如社保记录月数、是否担任其他在建工程项目经理）；非空时必为 true。
                    - 待核验项不会把 `status` 从 `MATCHED` 降级，客户端需把状态与复核标记一并展示。
                    - 标记由应用层强制兜底，不依赖模型自觉输出。

                    ### 失败说明
                    - `tenderRequirementId` 为空返回 400 Bad Request。
                    - 招标要求不存在返回 404 Not Found。
                    """)
    @PostMapping(value = "/match", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<MatchEvent<?>> match(@RequestBody MatchRequest request) {
        if (request.getTenderRequirementId() == null) {
            return Flux.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "招标要求 tenderRequirementId 不能为空"));
        }
        return matchingService.match(request);
    }
}
