package org.example.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.util.context.ContextView;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collection;
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
 * <p>纯工具调用轮（模型只发起 tool_calls、没有文本回复）的 output 会被序列化为
 * {@code [{"toolCallId":...,"name":...,"input":{...}}]}；agent 根节点的 output 取自最终
 * {@code AgentResultEvent}，避免 trace 中出现 output=null 的 generation/agent。</p>
 *
 * <p>tool 节点一轮可能并行执行多个工具，其 output 按 toolCallId 分组为
 * {@code [{"toolCallId":...,"name":...,"output":...}]}，而不是把各调用结果直接拼接
 * （拼接会得到 {@code [][{...}]} 这类非法 JSON，且空结果无法归属到具体调用）。
 * 分组按入参顺序预建，因此没有任何文本返回的调用也会以 {@code output:""} 出现。</p>
 */
@Slf4j
public class LangfuseSpanEnrichmentMiddleware implements MiddlewareBase {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

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
            boolean recording = span.getSpanContext().isValid();
            if (recording) {
                span.setAttribute("langfuse.observation.type", "agent");
                setTraceIdentity(span, ctx);
                if (captureContent) {
                    span.setAttribute("langfuse.observation.input", serializeMessages(input.msgs()));
                }
            }
            return next.apply(input)
                    .doOnNext(event -> {
                        // 最终回复（含结构化结果）挂在 AgentResultEvent 上，
                        // 不补记的话 agent 根节点的 output 永远为空
                        if (recording && captureContent
                                && event instanceof AgentResultEvent result
                                && result.getResult() != null) {
                            span.setAttribute("langfuse.observation.output",
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
            boolean recording = span.getSpanContext().isValid();

            if (recording) {
                span.setAttribute("langfuse.observation.type", "generation");
                setTraceIdentity(span, ctx);
                if (captureContent) {
                    span.setAttribute("langfuse.observation.input", serializeMessages(input.messages()));
                }
            }

            StringBuilder responseText = new StringBuilder();
            StringBuilder thinkingText = new StringBuilder();
            // 工具调用轮没有文本回复，只发 ToolCallStart/Delta 事件；
            // 按 toolCallId 累积名称与参数片段，否则这类 generation 的 output 为 null
            Map<String, String> toolCallNames = new LinkedHashMap<>();
            Map<String, StringBuilder> toolCallArgs = new LinkedHashMap<>();

            return next.apply(input)
                    .doOnNext(event -> {
                        if (!captureContent) {
                            return;
                        }
                        if (event instanceof TextBlockDeltaEvent delta && delta.getDelta() != null) {
                            responseText.append(delta.getDelta());
                        } else if (event instanceof ThinkingBlockDeltaEvent delta
                                && delta.getDelta() != null) {
                            thinkingText.append(delta.getDelta());
                        } else if (event instanceof ToolCallStartEvent start
                                && start.getToolCallId() != null) {
                            toolCallNames.put(start.getToolCallId(), start.getToolCallName());
                            toolCallArgs.computeIfAbsent(start.getToolCallId(), k -> new StringBuilder());
                        } else if (event instanceof ToolCallDeltaEvent delta
                                && delta.getToolCallId() != null) {
                            toolCallNames.putIfAbsent(delta.getToolCallId(), delta.getToolCallName());
                            if (delta.getDelta() != null) {
                                toolCallArgs.computeIfAbsent(delta.getToolCallId(), k -> new StringBuilder())
                                        .append(delta.getDelta());
                            }
                        }
                    })
                    .doOnComplete(() -> {
                        if (!recording || !captureContent) {
                            return;
                        }
                        if (!thinkingText.isEmpty()) {
                            span.setAttribute("langfuse.observation.metadata.thinking", thinkingText.toString());
                        }
                        String output = buildModelOutput(responseText, toolCallNames, toolCallArgs);
                        if (output != null) {
                            span.setAttribute("langfuse.observation.output", output);
                        }
                    });
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
            boolean recording = span.getSpanContext().isValid();
            List<ToolUseBlock> toolCalls = input == null ? null : input.toolCalls();

            if (recording) {
                span.setAttribute("langfuse.observation.type", "tool");
                setTraceIdentity(span, ctx);
                if (captureContent) {
                    span.setAttribute("langfuse.observation.input", serializeToolCalls(toolCalls));
                }
            }

            // 一轮 acting 里多个工具并行执行，结果事件按 toolCallId 交错到达。
            // 先按入参顺序建好分组，保证没有文本返回的调用也出现在 output 里（空结果可归属）
            Map<String, ToolResultCapture> results = new LinkedHashMap<>();
            if (captureContent && toolCalls != null) {
                for (ToolUseBlock toolCall : toolCalls) {
                    results.put(toolResultKey(toolCall.getId(), toolCall.getName()),
                            ToolResultCapture.of(toolCall.getId(), toolCall.getName()));
                }
            }
            return next.apply(input)
                    .doOnNext(event -> {
                        if (captureContent && event instanceof ToolResultTextDeltaEvent delta) {
                            ToolResultCapture capture = results.computeIfAbsent(
                                    toolResultKey(delta.getToolCallId(), delta.getToolCallName()),
                                    key -> ToolResultCapture.of(delta.getToolCallId(), delta.getToolCallName()));
                            if (delta.getDelta() != null) {
                                capture.text().append(delta.getDelta());
                            }
                        }
                    })
                    .doOnComplete(() -> {
                        if (recording && captureContent && !results.isEmpty()) {
                            span.setAttribute("langfuse.observation.output",
                                    buildToolResultsOutput(results.values()));
                        }
                    });
        });
    }

    /** 同一次调用的分组键；埋点缺失 toolCallId 时退回工具名，避免不同调用的结果被拼在一起 */
    private static String toolResultKey(String toolCallId, String toolCallName) {
        return StringUtils.hasText(toolCallId)
                ? toolCallId
                : "name:" + (toolCallName != null ? toolCallName : "unknown");
    }

    /**
     * 组装 tool 节点的 output：每个调用一条 {@code {"toolCallId":...,"name":...,"output":...}}，
     * 按入参顺序排列，整段是合法 JSON，可一次解析后按调用归属审计。
     */
    private static String buildToolResultsOutput(Collection<ToolResultCapture> results) {
        List<Map<String, Object>> payload = new ArrayList<>(results.size());
        for (ToolResultCapture result : results) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("toolCallId", result.toolCallId());
            item.put("name", result.name());
            item.put("output", parseToolResult(result.text()));
            payload.add(item);
        }
        return writeJson(payload);
    }

    /** 工具返回值多为 JSON，解析成结构后无需二次解析；非 JSON（如错误文本）原样保留，保证不丢数据 */
    private static Object parseToolResult(StringBuilder text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String raw = text.toString();
        try {
            return MAPPER.readTree(raw);
        } catch (RuntimeException e) {
            return raw;
        }
    }

    /** 单个工具调用的结果累积器；text 可变，因此用 record 持有引用而非值 */
    private record ToolResultCapture(String toolCallId, String name, StringBuilder text) {
        static ToolResultCapture of(String toolCallId, String name) {
            return new ToolResultCapture(toolCallId, name, new StringBuilder());
        }
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

    private static String serializeMessages(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> payload = new ArrayList<>(messages.size());
        for (Msg msg : messages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", msg.getRole() != null ? msg.getRole().name() : "UNKNOWN");
            item.put("content", textOf(msg.getContent()));
            List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);
            if (!toolCalls.isEmpty()) {
                item.put("tool_calls", toolCallPayload(toolCalls));
            }
            payload.add(item);
        }
        return writeJson(payload);
    }

    /** agent 最终回复：纯文本直接上报，含工具调用时包一层 JSON */
    private static String serializeResultMsg(Msg msg) {
        String text = msg.getTextContent();
        List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);
        if (toolCalls.isEmpty()) {
            return text != null ? text : "";
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if (StringUtils.hasText(text)) {
            payload.put("text", text);
        }
        payload.put("tool_calls", toolCallPayload(toolCalls));
        return writeJson(payload);
    }

    /**
     * 组装 generation 的 output：只有文本时保持原样；只有工具调用时输出
     * {@code [{"toolCallId":...,"name":...,"input":{...}}]}（与 onActing 的 input 格式一致，
     * 可按 toolCallId 与 tool 节点的 output 对齐）；两者都有时包一层 JSON。
     * 全空返回 null，表示不设置该属性。
     */
    private static String buildModelOutput(StringBuilder responseText,
                                           Map<String, String> toolCallNames,
                                           Map<String, StringBuilder> toolCallArgs) {
        boolean hasText = !responseText.isEmpty();
        if (toolCallNames.isEmpty()) {
            return hasText ? responseText.toString() : null;
        }
        List<Map<String, Object>> calls = new ArrayList<>(toolCallNames.size());
        toolCallNames.forEach((id, name) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("toolCallId", id);
            item.put("name", name);
            item.put("input", parseToolArgs(toolCallArgs.get(id)));
            calls.add(item);
        });
        if (!hasText) {
            return writeJson(calls);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", responseText.toString());
        payload.put("tool_calls", calls);
        return writeJson(payload);
    }

    /** 流式参数片段拼起来通常是完整 JSON；解析失败时退回原始字符串，保证不丢数据 */
    private static Object parseToolArgs(StringBuilder args) {
        if (args == null || args.isEmpty()) {
            return Map.of();
        }
        String raw = args.toString();
        try {
            return MAPPER.readTree(raw);
        } catch (RuntimeException e) {
            return raw;
        }
    }

    private static List<String> textOf(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<String> texts = new ArrayList<>();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock text && text.getText() != null && !text.getText().isBlank()) {
                texts.add(text.getText());
            } else if (block instanceof ToolResultBlock result) {
                for (ContentBlock output : result.getOutput()) {
                    if (output instanceof TextBlock text
                            && text.getText() != null
                            && !text.getText().isBlank()) {
                        texts.add(text.getText());
                    }
                }
            }
        }
        return texts;
    }

    private static String serializeToolCalls(List<ToolUseBlock> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return "[]";
        }
        return writeJson(toolCallPayload(toolCalls));
    }

    private static List<Map<String, Object>> toolCallPayload(List<ToolUseBlock> toolCalls) {
        List<Map<String, Object>> payload = new ArrayList<>(toolCalls.size());
        for (ToolUseBlock toolCall : toolCalls) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("toolCallId", toolCall.getId());
            item.put("name", toolCall.getName());
            item.put("input", toolCall.getInput());
            payload.add(item);
        }
        return payload;
    }

    private static String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (RuntimeException e) {
            log.warn("Failed to serialize Langfuse attribute: {}", e.getMessage());
            return "[]";
        }
    }
}
