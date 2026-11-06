package org.example.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
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
            if (span.getSpanContext().isValid()) {
                span.setAttribute("langfuse.observation.type", "agent");
                setTraceIdentity(span, ctx);
                if (captureContent) {
                    span.setAttribute("langfuse.observation.input", serializeMessages(input.msgs()));
                }
            }
            return next.apply(input);
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
                        }
                    })
                    .doOnComplete(() -> {
                        if (!recording || !captureContent) {
                            return;
                        }
                        if (!thinkingText.isEmpty()) {
                            span.setAttribute("langfuse.observation.metadata.thinking", thinkingText.toString());
                        }
                        if (!responseText.isEmpty()) {
                            span.setAttribute("langfuse.observation.output", responseText.toString());
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

            if (recording) {
                span.setAttribute("langfuse.observation.type", "tool");
                setTraceIdentity(span, ctx);
                if (captureContent) {
                    span.setAttribute("langfuse.observation.input", serializeToolCalls(input.toolCalls()));
                }
            }

            StringBuilder output = new StringBuilder();
            return next.apply(input)
                    .doOnNext(event -> {
                        if (captureContent
                                && event instanceof ToolResultTextDeltaEvent delta
                                && delta.getDelta() != null) {
                            output.append(delta.getDelta());
                        }
                    })
                    .doOnComplete(() -> {
                        if (recording && captureContent && !output.isEmpty()) {
                            span.setAttribute("langfuse.observation.output", output.toString());
                        }
                    });
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

    private static String serializeMessages(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> payload = new ArrayList<>(messages.size());
        for (Msg msg : messages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", msg.getRole() != null ? msg.getRole().name() : "UNKNOWN");
            item.put("content", textOf(msg.getContent()));
            payload.add(item);
        }
        return writeJson(payload);
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
        List<Map<String, Object>> payload = new ArrayList<>(toolCalls.size());
        for (ToolUseBlock toolCall : toolCalls) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", toolCall.getName());
            item.put("input", toolCall.getInput());
            payload.add(item);
        }
        return writeJson(payload);
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
