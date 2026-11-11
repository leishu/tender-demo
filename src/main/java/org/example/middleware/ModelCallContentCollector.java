package org.example.middleware;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按订阅累积一次模型调用（或一轮工具执行）产生的内容。
 *
 * <p>本类只做「事件 → 内容」的归一化，不写任何输出端：Langfuse 中间件把结果写成
 * span 属性，审计中间件把结果写进审计文件。因为每次订阅都必须独立，调用方应在
 * {@code Flux.defer}/{@code deferContextual} 内部创建实例，不要跨订阅复用。</p>
 *
 * <p>累积期间不做截断，避免边流式追加边截断带来的 O(n²) 复制；只在读取
 * （{@link #responseText()}、{@link #thinkingText()}、{@link #outputJson()} 等）时按
 * 配置的上限截断一次。</p>
 */
public final class ModelCallContentCollector {

    private final int maxTextChars;
    private final StringBuilder responseText = new StringBuilder();
    private final StringBuilder thinkingText = new StringBuilder();
    private final Map<String, ToolCallCapture> toolCalls = new LinkedHashMap<>();
    private final Map<String, ToolResultCapture> toolResults = new LinkedHashMap<>();

    public ModelCallContentCollector() {
        this(ModelCallContent.DEFAULT_MAX_TEXT_CHARS);
    }

    public ModelCallContentCollector(int maxTextChars) {
        this.maxTextChars = ModelCallContent.normalizeMaxTextChars(maxTextChars);
    }

    /**
     * 处理一个事件。只识别内容相关事件，其余事件（起止、元数据等）直接忽略，
     * 因此调用方可以无脑转发整条事件流。
     */
    public void onEvent(AgentEvent event) {
        if (event == null) {
            return;
        }
        if (event instanceof TextBlockDeltaEvent delta) {
            if (delta.getDelta() != null) {
                responseText.append(delta.getDelta());
            }
        } else if (event instanceof ThinkingBlockDeltaEvent delta) {
            if (delta.getDelta() != null) {
                thinkingText.append(delta.getDelta());
            }
        } else if (event instanceof ToolCallStartEvent start) {
            if (ModelCallContent.hasToolCallIdentity(start.getToolCallId(), start.getToolCallName())) {
                toolCall(start.getToolCallId(), start.getToolCallName());
            }
        } else if (event instanceof ToolCallDeltaEvent delta) {
            if (delta.getDelta() != null
                    && ModelCallContent.hasToolCallIdentity(
                            delta.getToolCallId(), delta.getToolCallName())) {
                toolCall(delta.getToolCallId(), delta.getToolCallName()).append(delta.getDelta());
            }
        } else if (event instanceof ToolResultTextDeltaEvent delta) {
            ToolResultCapture capture = toolResult(delta.getToolCallId(), delta.getToolCallName());
            if (capture != null && delta.getDelta() != null) {
                capture.appendText(delta.getDelta());
            }
        } else if (event instanceof ToolResultDataDeltaEvent delta) {
            ToolResultCapture capture = toolResult(delta.getToolCallId(), delta.getToolCallName());
            if (capture != null) {
                capture.addNonTextBlock(delta.getData());
            }
        } else if (event instanceof ToolResultEndEvent end) {
            ToolResultCapture capture = toolResult(end.getToolCallId(), end.getToolCallName());
            if (capture != null) {
                capture.setState(end.getState());
            }
        }
    }

    /**
     * 工具执行前按入参顺序预建结果分组：没有文本返回的调用也会以 {@code output:""}
     * 出现，空结果才能归属到具体调用。
     */
    public void seedToolResults(List<ToolUseBlock> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        for (ToolUseBlock toolCall : toolCalls) {
            toolResult(toolCall.getId(), toolCall.getName());
        }
    }

    public boolean hasResponseText() {
        return !responseText.isEmpty();
    }

    public boolean hasThinkingText() {
        return !thinkingText.isEmpty();
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /** 已累积的文本回复，按上限截断 */
    public String responseText() {
        return ModelCallContent.truncateText(responseText.toString(), maxTextChars);
    }

    /** 已累积的思考过程，按上限截断 */
    public String thinkingText() {
        return ModelCallContent.truncateText(thinkingText.toString(), maxTextChars);
    }

    /** 已累积的工具调用，参数已解析为结构（解析失败时保留原始文本） */
    public List<ModelCallContent.ToolCall> toolCalls() {
        List<ModelCallContent.ToolCall> payload = new ArrayList<>(toolCalls.size());
        for (ToolCallCapture capture : toolCalls.values()) {
            payload.add(capture.toContent());
        }
        return payload;
    }

    /**
     * generation 节点的 output：只有文本时保持纯文本；只有工具调用时输出
     * {@code [{"toolCallId":...,"name":...,"input":{...}}]}；两者都有时包一层 JSON。
     * 全空返回 {@code null}，表示不应写入该属性。
     */
    public String outputJson() {
        boolean hasText = hasResponseText();
        if (!hasToolCalls()) {
            return hasText ? responseText() : null;
        }
        List<ModelCallContent.ToolCall> calls = toolCalls();
        if (!hasText) {
            return ModelCallContent.json(calls);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", responseText());
        payload.put("tool_calls", calls);
        return ModelCallContent.json(payload);
    }

    /**
     * tool 节点的 output：每个调用一条
     * {@code {"toolCallId":...,"name":...,"output":...,"state":...}}，按入参顺序排列。
     * 无任何结果时返回 {@code null}。
     */
    public String toolResultsOutputJson() {
        if (toolResults.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> payload = new ArrayList<>(toolResults.size());
        for (ToolResultCapture capture : toolResults.values()) {
            payload.add(capture.toOutput());
        }
        return ModelCallContent.json(payload);
    }

    private ToolCallCapture toolCall(String toolCallId, String toolCallName) {
        String key = ModelCallContent.toolKey(toolCallId, toolCallName);
        ToolCallCapture capture = toolCalls.get(key);
        if (capture == null) {
            capture = new ToolCallCapture();
            toolCalls.put(key, capture);
        }
        capture.mergeIdentity(toolCallId, toolCallName);
        return capture;
    }

    /** 无 id 且无名字的结果无法归属，直接丢弃（与 Langfuse 侧原有行为一致） */
    private ToolResultCapture toolResult(String toolCallId, String toolCallName) {
        if (!ModelCallContent.hasToolCallIdentity(toolCallId, toolCallName)) {
            return null;
        }
        String key = ModelCallContent.toolKey(toolCallId, toolCallName);
        ToolResultCapture capture = toolResults.get(key);
        if (capture == null) {
            capture = new ToolResultCapture();
            toolResults.put(key, capture);
        }
        capture.mergeIdentity(toolCallId, toolCallName);
        return capture;
    }

    /** 单个工具调用的参数累积器；id/name 允许后续事件补齐，先到先得 */
    private static final class ToolCallCapture {

        private String toolCallId;
        private String toolCallName;
        private final StringBuilder args = new StringBuilder();

        private void mergeIdentity(String id, String name) {
            if (!StringUtils.hasText(toolCallId) && StringUtils.hasText(id)) {
                toolCallId = id;
            }
            if (!StringUtils.hasText(toolCallName) && StringUtils.hasText(name)) {
                toolCallName = name;
            }
        }

        private void append(String delta) {
            args.append(delta);
        }

        private ModelCallContent.ToolCall toContent() {
            Object input = ModelCallContent.parseJsonOrText(args.toString(), Map.of());
            return new ModelCallContent.ToolCall(toolCallId, toolCallName, input);
        }
    }

    /** 单个工具执行的累积器；text 可变，因此用类持有引用而非 record 值 */
    private static final class ToolResultCapture {

        private String toolCallId;
        private String toolCallName;
        private final StringBuilder text = new StringBuilder();
        private final List<String> nonTextBlocks = new ArrayList<>();
        private ToolResultState state;

        private void mergeIdentity(String id, String name) {
            if (!StringUtils.hasText(toolCallId) && StringUtils.hasText(id)) {
                toolCallId = id;
            }
            if (!StringUtils.hasText(toolCallName) && StringUtils.hasText(name)) {
                toolCallName = name;
            }
        }

        private void appendText(String delta) {
            text.append(delta);
        }

        /** 非文本结果（图片、文件等）没有可读文本，至少记录块类型 */
        private void addNonTextBlock(ContentBlock block) {
            nonTextBlocks.add(block == null ? "unknown" : block.getClass().getSimpleName());
        }

        private void setState(ToolResultState state) {
            if (state != null) {
                this.state = state;
            }
        }

        private Map<String, Object> toOutput() {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("toolCallId", toolCallId);
            item.put("name", toolCallName);
            Object output = text.isEmpty()
                    ? ""
                    : ModelCallContent.parseJsonOrText(text.toString(), "");
            item.put("output", output);
            if (!nonTextBlocks.isEmpty()) {
                item.put("otherBlocks", List.copyOf(nonTextBlocks));
            }
            if (state != null) {
                item.put("state", state.getValue());
            }
            return item;
        }
    }
}
