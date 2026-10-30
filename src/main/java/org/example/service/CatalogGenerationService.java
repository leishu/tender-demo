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

import java.util.List;

/**
 * 投标目录生成与调整的执行流程：新建会话 → 发送提示词 → 解析目录 JSON。
 *
 * <p>模型输出先解析为 {@link CatalogResult}，再由 {@link CatalogNormalizer} 就地修正可由结构确定的
 * 问题（章节编号按嵌套位置重排、超长自由文本截断），最后用保存前的
 * {@link CatalogValidator#validate(CatalogResult)} 校验。解析或校验失败时不直接上报，而是把失败原因
 * 发回同一会话，让它重新生成一次完整目录；重试仍失败才按 502 上报。</p>
 *
 * <p>重试消息只带失败原因，不再重发本次请求的提示词：同一 {@code (userId, sessionId)} 的会话记忆里
 * 已经有原始提示词与模型上一条回复，重发只是重复计费。</p>
 */
@Slf4j
@Service
public class CatalogGenerationService {

    /** 目录解析失败后回传失败原因让模型重新生成的次数。 */
    private static final int PARSE_RETRY_TIMES = 1;

    private final ObjectProvider<HarnessAgent.Builder> builderProvider;
    private final RuntimeContextBuilder contextBuilder;
    private final CatalogResultParser catalogResultParser;
    private final CatalogNormalizer catalogNormalizer;
    private final CatalogValidator catalogValidator;

    public CatalogGenerationService(ObjectProvider<HarnessAgent.Builder> builderProvider,
                                    RuntimeContextBuilder contextBuilder,
                                    CatalogResultParser catalogResultParser,
                                    CatalogNormalizer catalogNormalizer,
                                    CatalogValidator catalogValidator) {
        this.builderProvider = builderProvider;
        this.contextBuilder = contextBuilder;
        this.catalogResultParser = catalogResultParser;
        this.catalogNormalizer = catalogNormalizer;
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
                        .flatMap(raw -> parseAndValidate(agent, ctx, raw, PARSE_RETRY_TIMES)),
                HarnessAgent::close
        );
    }

    /**
     * 解析、规范化并校验模型输出；任一步失败都把失败原因发回同一会话，让它重新生成完整目录。
     *
     * <p>规范化先于校验：章节编号按嵌套位置重排、自由文本按列宽截断后，多数“格式不对”的目录
     * 不必再花一次模型调用。只有规范化也解决不了的问题（JSON 不合法、标题为空、层级被压平等）
     * 才回传失败原因重试。</p>
     *
     * <p>校验直接复用保存前的 {@link CatalogValidator#validate(CatalogResult)}，让不合法的目录在
     * 生成阶段就被报回模型修正，而不是等到落库时才返回 400。</p>
     *
     * @param raw         模型本轮返回的原始文本
     * @param retriesLeft 剩余重试次数，用尽后按 502 上报
     */
    private Mono<CatalogResult> parseAndValidate(HarnessAgent agent,
                                                 RuntimeContext ctx,
                                                 String raw,
                                                 int retriesLeft) {
        CatalogResult result;
        try {
            result = catalogResultParser.parse(raw);
        } catch (ResponseStatusException e) {
            return retryOnFailure(agent, ctx, "目录 JSON 解析失败", e, retriesLeft);
        }
        try {
            catalogNormalizer.normalize(result);
            catalogValidator.validate(result);
        } catch (ResponseStatusException e) {
            return retryOnFailure(agent, ctx, "目录数据校验未通过", e, retriesLeft);
        }
        return Mono.just(result);
    }

    /**
     * 失败处置：把失败原因发回同一会话重新生成；次数用尽后按 502 上报。
     *
     * <p>不重发原始提示词与上一条回复：它们已经在同一 {@code (userId, sessionId)} 的会话记忆里，
     * 重复发送只会增加输入 token。这个前提在当前流程下成立：{@link #generate} 全程复用同一个
     * {@link RuntimeContext} 与 agent 实例，而目录会话只有一轮输入加一次回复，远低于 compaction 的
     * 触发阈值（模型上下文窗口减 20k token，或 50 条消息）。</p>
     *
     * @param stage 失败环节，如“目录 JSON 解析失败”“目录数据校验未通过”
     */
    private Mono<CatalogResult> retryOnFailure(HarnessAgent agent,
                                               RuntimeContext ctx,
                                               String stage,
                                               ResponseStatusException failure,
                                               int retriesLeft) {
        String reason = stage + "：" + failureReason(failure);
        if (retriesLeft <= 0) {
            log.error("投标目录重试已用尽（{}）：{}", stage, failure.getReason());
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    reason + "（已重试 " + PARSE_RETRY_TIMES + " 次）", failure));
        }
        log.warn("投标目录{}，发回失败原因让模型重新生成：{}", stage, failure.getReason());
        return agent.call(new UserMessage(failureFeedback(reason)), ctx)
                .map(Msg::getTextContent)
                .flatMap(next -> parseAndValidate(agent, ctx, next, retriesLeft - 1));
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

    /**
     * 失败反馈块：说明上一条回复哪里不合格，要求重新输出一个完整合法的 JSON。
     *
     * <p>不再附带上一轮原文：它就在会话记忆里，再贴一遍（最多 6000 字符）是纯重复输入；
     * JSON 解析失败时行列位置已在失败原因里给出，足够模型定位。</p>
     */
    private String failureFeedback(String reason) {
        return """
                ## 上一条回复未通过校验，请重新生成目录

                你上一条回复生成的目录未通过，失败原因：%s

                请对照你上一条回复自查后，仍按上文目录规则重新输出一次，并满足：
                1. 只输出一个完整、语法合法的 JSON 对象，JSON 之外不输出任何解释性文字；
                2. 章节编号与嵌套位置一致（1、1.1、1.1.1），同级从 1 连续递增，不跳号、不重号、不带前导零；
                3. 章节标题、内容提示、结构判断说明、缺口类型与详情不得为空或超出长度上限；
                4. 保持与上文规则一致，不得因重试而减少评分点覆盖；若上次输出被截断，
                   请精简 content_hint 等长文本字段，确保 JSON 完整闭合。
                """.formatted(reason);
    }
}
