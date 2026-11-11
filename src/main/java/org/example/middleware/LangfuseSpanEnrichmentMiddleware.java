package org.example.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.util.context.ContextView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 为 AgentScope 的 {@code OtelTracingMiddleware} 补充 Langfuse 语义属性。
 *
 * <p>AgentScope 自带的 OTel 中间件只记录模型名、token 用量等元数据，不会上报提示词与
 * 模型回复。本中间件运行在 OTel 中间件内层（{@link #order()} 更小），因此可以拿到当前
 * span 并补充 Langfuse 约定的属性：</p>
 *
 * <ul>
 *   <li>{@code langfuse.observation.type}：agent / generation / tool</li>
 *   <li>{@code langfuse.observation.input} / {@code output}：输入输出内容</li>
 *   <li>{@code langfuse.user.id} / {@code langfuse.session.id}：按用户、会话过滤</li>
 * </ul>
 *
 * <p>未启用 Langfuse 时不会创建该 Bean，也不会注册到 Agent 上。</p>
 *
 * <p>内容采集与序列化统一交给 {@link ModelCallContentCollector} /
 * {@link ModelCallContent}，与本地审计中间件共用同一套截断规则和字段结构；本类只负责
 * 「把采集结果写到当前 span」。</p>
 *
 * <p>纯工具调用轮（模型只发起 tool_calls、没有文本回复）的 output 会被序列化为
 * {@code [{"toolCallId":...,"name":...,"input":{...}}]}；agent 根节点的 output 取自最终
 * {@code AgentResultEvent}，避免 trace 中出现 output=null 的 generation/agent。</p>
 *
 * <p>tool 节点一轮可能并行执行多个工具，其 output 按 toolCallId 分组为
 * {@code [{"toolCallId":...,"name":...,"output":...,"state":...}]}，而不是把各调用结果
 * 直接拼接（拼接会得到 {@code [][{...}]} 这类非法 JSON，且空结果无法归属到具体调用）。
 * 分组按入参顺序预建，因此没有任何文本返回的调用也会以 {@code output:""} 出现；
 * 图片/文件等非文本结果记录在 {@code otherBlocks}，执行状态记录在 {@code state}。</p>
 *
 * <p>异常与取消也会补写已累积的 output（{@code doOnComplete} 之外的 {@code doOnError} /
 * {@code doOnCancel}），避免失败轮次在 Langfuse 上显示为 output=null。</p>
 *
 * <p>所有属性写入前都会按字符上限截断，防止提示词、长对话或大工具结果撑爆
 * Langfuse/OTLP 的单批大小；截断后的结构仍是合法 JSON（整体超限时退化为带 preview
 * 的截断对象）。</p>
 */
public class LangfuseSpanEnrichmentMiddleware implements MiddlewareBase {

    private static final String ATTR_TYPE = "langfuse.observation.type";
    private static final String ATTR_INPUT = "langfuse.observation.input";
    private static final String ATTR_OUTPUT = "langfuse.observation.output";
    private static final String ATTR_THINKING = "langfuse.observation.metadata.thinking";

    private final boolean captureContent;

    public LangfuseSpanEnrichmentMiddleware(boolean captureContent) {
        this.captureContent = captureContent;
    }

    /**
     * 数值越小越靠近内层。OTel 中间件使用默认值 1，本中间件必须位于其内部，
     * 才能读到 OTel 创建的当前 span。
     */
    @Override
    public int order() {
        return 0;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(view -> {
            Span span = currentSpan(view);
            // 采信 isRecording 而不是 spanContext.isValid：采样关闭时 spanContext 仍然有效，
            // 但属性会被丢弃，提前判断可以避免白做序列化
            boolean recording = span.isRecording();
            if (recording) {
                span.setAttribute(ATTR_TYPE, "agent");
                setTraceIdentity(span, ctx);
                if (captureContent) {
                    setAttributeIfPresent(span, ATTR_INPUT,
                            ModelCallContent.messagesJson(input.msgs()));
                }
            }
            return next.apply(input)
                    .doOnNext(event -> {
                        // 最终回复（含结构化结果）挂在 AgentResultEvent 上，
                        // 不补记的话 agent 根节点的 output 永远为空
                        if (recording && captureContent
                                && event instanceof AgentResultEvent result
                                && result.getResult() != null) {
                            setAttributeIfPresent(span, ATTR_OUTPUT,
                                    serializeResultMsg(result.getResult()));
                        }
                    });
        });
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(view -> {
            Span span = currentSpan(view);
            boolean recording = span.isRecording();

            if (recording) {
                span.setAttribute(ATTR_TYPE, "generation");
                setTraceIdentity(span, ctx);
                if (captureContent) {
                    setAttributeIfPresent(span, ATTR_INPUT,
                            ModelCallContent.messagesJson(input.messages()));
                }
            }

            // 每次订阅一份独立状态：同一个 Flux 被重放/重试时内容不会互相累加
            ModelCallContentCollector collector = new ModelCallContentCollector();

            Runnable flush = () -> {
                if (!recording || !captureContent) {
                    return;
                }
                if (collector.hasThinkingText()) {
                    setAttributeIfPresent(span, ATTR_THINKING, collector.thinkingText());
                }
                setAttributeIfPresent(span, ATTR_OUTPUT, collector.outputJson());
            };

            return next.apply(input)
                    .doOnNext(event -> {
                        if (captureContent) {
                            collector.onEvent(event);
                        }
                    })
                    .doOnComplete(flush)
                    // 失败/取消时也落一次已生成的内容：只挂 doOnComplete 会让报错轮次
                    // 在 Langfuse 上表现为 output=null
                    .doOnError(error -> flush.run())
                    .doOnCancel(flush);
        });
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(view -> {
            Span span = currentSpan(view);
            boolean recording = span.isRecording();
            List<ToolUseBlock> toolCalls = input == null ? null : input.toolCalls();

            if (recording) {
                span.setAttribute(ATTR_TYPE, "tool");
                setTraceIdentity(span, ctx);
                if (captureContent) {
                    setAttributeIfPresent(span, ATTR_INPUT,
                            ModelCallContent.toolCallsJson(toolCalls));
                }
            }

            // 一轮 acting 里多个工具并行执行，结果事件按 toolCallId 交错到达。
            // 先按入参顺序建好分组，保证没有文本返回的调用也出现在 output 里（空结果可归属）
            ModelCallContentCollector collector = new ModelCallContentCollector();
            if (captureContent) {
                collector.seedToolResults(toolCalls);
            }

            Runnable flush = () -> {
                if (!recording || !captureContent) {
                    return;
                }
                setAttributeIfPresent(span, ATTR_OUTPUT, collector.toolResultsOutputJson());
            };

            return next.apply(input)
                    .doOnNext(event -> {
                        if (captureContent) {
                            collector.onEvent(event);
                        }
                    })
                    .doOnComplete(flush)
                    .doOnError(error -> flush.run())
                    .doOnCancel(flush);
        });
    }

    private static Span currentSpan(ContextView view) {
        Context context = ContextPropagationOperator.getOpenTelemetryContextFromContextView(
                view, Context.current());
        return Span.fromContext(context);
    }

    private static void setTraceIdentity(Span span, RuntimeContext ctx) {
        if (ctx == null) {
            return;
        }
        if (StringUtils.hasText(ctx.getUserId())) {
            span.setAttribute("langfuse.user.id", ctx.getUserId());
        }
        if (StringUtils.hasText(ctx.getSessionId())) {
            span.setAttribute("langfuse.session.id", ctx.getSessionId());
        }
    }

    /** agent 最终回复：纯文本直接上报，含工具调用时包一层 JSON */
    private static String serializeResultMsg(Msg msg) {
        String text = msg.getTextContent();
        List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);
        if (toolCalls.isEmpty()) {
            return text == null ? "" : ModelCallContent.truncateText(
                    text, ModelCallContent.MAX_ATTRIBUTE_CHARS);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if (StringUtils.hasText(text)) {
            payload.put("text", ModelCallContent.truncateText(
                    text, ModelCallContent.MAX_ATTRIBUTE_CHARS));
        }
        payload.put("tool_calls", ModelCallContent.toolCalls(toolCalls));
        return ModelCallContent.json(payload);
    }

    /** 仅在序列化成功时写属性；序列化失败返回 null 时直接跳过，避免写入伪造的空值 */
    private static void setAttributeIfPresent(Span span, String key, String value) {
        if (value != null) {
            span.setAttribute(key, value);
        }
    }
}
