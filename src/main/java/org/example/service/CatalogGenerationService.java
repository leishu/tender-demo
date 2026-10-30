package org.example.service;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.extern.slf4j.Slf4j;
import org.example.model.catalog.dto.CatalogResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 投标目录生成与调整的执行流程：新建会话 → 发送提示词 → 解析目录 JSON。
 *
 * <p>模型输出先解析为 {@link CatalogResult}，再用保存前的 {@link CatalogValidator#validate(CatalogResult)}
 * 校验；任一步失败都不直接上报，而是重新带上本次请求的原始提示词，并把原始输出与失败原因
 * 追加为新消息发回同一会话，让它重新生成一次完整目录；重试仍失败才按 502 上报。</p>
 */
@Slf4j
@Service
public class CatalogGenerationService {

    /** 目录解析失败后回传失败原因让模型重新生成的次数。 */
    private static final int PARSE_RETRY_TIMES = 1;

    /** 回传模型上一次输出时的最大字符数，超长输出截断，避免与失败原因一起重复占用上下文。 */
    private static final int FEEDBACK_MAX_CHARS = 6000;

    private final ObjectProvider<HarnessAgent.Builder> builderProvider;
    private final RuntimeContextBuilder contextBuilder;
    private final CatalogResultParser catalogResultParser;
    private final CatalogValidator catalogValidator;

    public CatalogGenerationService(ObjectProvider<HarnessAgent.Builder> builderProvider,
                                    RuntimeContextBuilder contextBuilder,
                                    CatalogResultParser catalogResultParser,
                                    CatalogValidator catalogValidator) {
        this.builderProvider = builderProvider;
        this.contextBuilder = contextBuilder;
        this.catalogResultParser = catalogResultParser;
        this.catalogValidator = catalogValidator;
    }

    /** 以单段提示词生成目录。 */
    public Mono<CatalogResult> generate(String userId, String prompt) {
        return generate(userId, List.of(TextBlock.builder().text(prompt).build()));
    }

    /** 以多个内容块发送，避免将规则、上一轮目录与用户要求拼接成一段超长文本。 */
    public Mono<CatalogResult> generate(String userId, List<ContentBlock> blocks) {
        RuntimeContext ctx = contextBuilder.build(userId);

        HarnessAgent.Builder builder = builderProvider.getObject();

        return Mono.using(
                builder::build,
                agent -> agent.call(new UserMessage(blocks), ctx)
                        .map(Msg::getTextContent)
                        .flatMap(raw -> parseAndValidate(agent, ctx, blocks, raw, PARSE_RETRY_TIMES)),
                HarnessAgent::close
        );
    }

    /**
     * 解析并校验模型输出；任一步失败都重发原始提示词并附上失败原因，让它重新生成完整目录。
     *
     * <p>重试消息里带上 {@code blocks}，不依赖会话记忆仍保留首轮提示词：会话记忆可能被压缩
     * （compaction）、或改由 stateStore 重新加载，只发一段“上一条回复失败”时模型看不到
     * 目录规则与上一轮目录，无法按原格式重生成。</p>
     *
     * <p>校验直接复用保存前的 {@link CatalogValidator#validate(CatalogResult)}，让不合法的目录在
     * 生成阶段就被报回模型修正，而不是等到落库时才返回 400。</p>
     *
     * @param blocks      本次请求的原始提示词内容块，重试时原样重发
     * @param raw         模型本轮返回的原始文本
     * @param retriesLeft 剩余重试次数，用尽后按 502 上报
     */
    private Mono<CatalogResult> parseAndValidate(HarnessAgent agent,
                                                 RuntimeContext ctx,
                                                 List<ContentBlock> blocks,
                                                 String raw,
                                                 int retriesLeft) {
        CatalogResult result;
        try {
            result = catalogResultParser.parse(raw);
        } catch (ResponseStatusException e) {
            return retryOnFailure(agent, ctx, blocks, raw, "目录 JSON 解析失败", e, retriesLeft);
        }
        try {
            catalogValidator.validate(result);
        } catch (ResponseStatusException e) {
            return retryOnFailure(agent, ctx, blocks, raw, "目录数据校验未通过", e, retriesLeft);
        }
        return Mono.just(result);
    }

    /**
     * 失败处置：把模型原始输出与失败原因回传同一会话重新生成；次数用尽后按 502 上报。
     *
     * @param stage 失败环节，如“目录 JSON 解析失败”“目录数据校验未通过”
     */
    private Mono<CatalogResult> retryOnFailure(HarnessAgent agent,
                                               RuntimeContext ctx,
                                               List<ContentBlock> blocks,
                                               String raw,
                                               String stage,
                                               ResponseStatusException failure,
                                               int retriesLeft) {
        String reason = stage + "：" + failureReason(failure);
        if (retriesLeft <= 0) {
            log.error("投标目录重试已用尽（{}）：{}", stage, failure.getReason());
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    reason + "（已重试 " + PARSE_RETRY_TIMES + " 次）", failure));
        }
        log.warn("投标目录{}，重发原始提示词并附上失败原因让模型重新生成：{}", stage, failure.getReason());
        return agent.call(new UserMessage(retryBlocks(blocks, failureFeedback(raw, reason))), ctx)
                .map(Msg::getTextContent)
                .flatMap(next -> parseAndValidate(agent, ctx, blocks, next, retriesLeft - 1));
    }

    /** 重试消息内容块：原始提示词在前，失败反馈追加在后，保证规则与输入不因会话记忆缺失而丢失。 */
    private List<ContentBlock> retryBlocks(List<ContentBlock> blocks, String feedback) {
        List<ContentBlock> retry = new ArrayList<>(blocks.size() + 1);
        retry.addAll(blocks);
        retry.add(TextBlock.builder().text(feedback).build());
        return retry;
    }

    /** 失败原因：异常描述加上底层原因（如 JSON 解析的行列位置），便于模型定位 */
    private String failureReason(ResponseStatusException failure) {
        String reason = failure.getReason() == null ? "未知原因" : failure.getReason();
        Throwable cause = failure.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            reason = reason + "（" + cause.getMessage() + "）";
        }
        return reason;
    }

    /** 失败反馈块：说明上一条回复哪里不合格，附上其原文，要求只重新输出一个完整合法的 JSON。 */
    private String failureFeedback(String raw, String reason) {
        return """
                ## 上一条回复未通过校验，请重新生成目录

                本消息已重新附上本次请求的完整规则与输入（若与上一条消息重复，以本消息为准）。

                你上一条回复生成的目录未通过，失败原因：%s

                上一条回复的原文如下（可能因截断或格式错误而不完整）：
                ```
                %s
                ```

                请仍按上文目录规则重新输出一次，并满足：
                1. 只输出一个完整、语法合法的 JSON 对象，JSON 之外不输出任何解释性文字；
                2. 章节编号与嵌套位置一致（1、1.1、1.1.1），同级从 1 连续递增，不跳号、不重号、不带前导零；
                3. 章节标题、内容提示、结构判断说明、缺口类型与详情不得为空或超出长度上限；
                4. 保持与上文规则一致，不得因重试而减少评分点覆盖；若上次输出被截断，
                   请精简 content_hint 等长文本字段，确保 JSON 完整闭合。
                """.formatted(reason, abbreviate(raw));
    }

    /** 截断过长的模型输出，避免把整段原文再重复一遍占用上下文。 */
    private String abbreviate(String raw) {
        if (raw == null || raw.isBlank()) {
            return "（空）";
        }
        if (raw.length() <= FEEDBACK_MAX_CHARS) {
            return raw;
        }
        return raw.substring(0, FEEDBACK_MAX_CHARS)
                + "\n……（已截断，共 " + raw.length() + " 字符，仅回传前 " + FEEDBACK_MAX_CHARS + " 字符）";
    }
}
