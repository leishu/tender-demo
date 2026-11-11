package org.example.middleware;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型调用内容的共享转换、截断与 JSON 序列化。
 *
 * <p>Langfuse 属性与本地审计文件都从这里取数据，避免两边对同一轮调用给出不同的
 * 内容（字段名、截断上限、工具结果归属）。这里的方法都是无状态的，可以在事件线程与
 * 写盘线程之间安全复用。</p>
 */
@Slf4j
public final class ModelCallContent {

    /** 单个文本字段（消息内容、思考过程）的默认字符上限 */
    public static final int DEFAULT_MAX_TEXT_CHARS = 50_000;

    /** 单个工具结果/参数参与 JSON 解析的字符上限，超过后直接按纯文本截断 */
    public static final int MAX_TOOL_PAYLOAD_CHARS = 100_000;

    /** 单个 span 属性的字符硬上限；超限时改为带 preview 的截断对象 */
    public static final int MAX_ATTRIBUTE_CHARS = 200_000;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ModelCallContent() {
    }

    /** 一次工具调用请求，{@code input} 为已解析的参数结构（或解析失败时的原始文本） */
    public record ToolCall(String toolCallId, String name, Object input) {
    }

    /** 一次工具执行结果；非文本块与执行状态单独记录，避免表现为“空输出” */
    public record ToolResult(String toolCallId,
                             String name,
                             List<String> content,
                             List<String> otherBlocks,
                             String state) {
    }

    /** 提示消息；字段名与 Langfuse 期望的 input 结构保持一致 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Message(String role,
                          List<String> content,
                          @JsonProperty("tool_calls") List<ToolCall> toolCalls,
                          @JsonProperty("tool_results") List<ToolResult> toolResults) {
    }

    /** 小于等于 0 视为未配置，退回默认值，避免“不限长度”意外撑爆输出 */
    public static int normalizeMaxTextChars(int maxChars) {
        return maxChars > 0 ? maxChars : DEFAULT_MAX_TEXT_CHARS;
    }

    public static String messagesJson(List<Msg> messages) {
        return json(messages(messages, DEFAULT_MAX_TEXT_CHARS));
    }

    public static List<Message> messages(List<Msg> messages, int maxTextChars) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int limit = normalizeMaxTextChars(maxTextChars);
        List<Message> payload = new ArrayList<>(messages.size());
        for (Msg msg : messages) {
            List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);
            List<ToolResultBlock> toolResults = msg.getContentBlocks(ToolResultBlock.class);
            payload.add(new Message(
                    msg.getRole() != null ? msg.getRole().name() : "UNKNOWN",
                    texts(msg.getContent(), limit),
                    toolCalls.isEmpty() ? null : toolCalls(toolCalls),
                    toolResults.isEmpty() ? null : toolResults(toolResults, limit)));
        }
        return payload;
    }

    public static List<ToolCall> toolCalls(List<ToolUseBlock> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        List<ToolCall> payload = new ArrayList<>(toolCalls.size());
        for (ToolUseBlock toolCall : toolCalls) {
            payload.add(new ToolCall(toolCall.getId(), toolCall.getName(), toolCall.getInput()));
        }
        return payload;
    }

    public static String toolCallsJson(List<ToolUseBlock> toolCalls) {
        return json(toolCalls(toolCalls));
    }

    public static List<ToolResult> toolResults(List<ToolResultBlock> toolResults, int maxTextChars) {
        if (toolResults == null || toolResults.isEmpty()) {
            return List.of();
        }
        int limit = normalizeMaxTextChars(maxTextChars);
        List<ToolResult> payload = new ArrayList<>(toolResults.size());
        for (ToolResultBlock result : toolResults) {
            payload.add(new ToolResult(
                    result.getId(),
                    result.getName(),
                    texts(result.getOutput(), limit),
                    otherBlocks(result.getOutput()),
                    result.getState() != null ? result.getState().getValue() : null));
        }
        return payload;
    }

    /** 提取文本块，逐个截断；工具结果文本不在这里展开，由 {@link #toolResults} 负责 */
    public static List<String> texts(List<ContentBlock> blocks, int maxTextChars) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        int limit = normalizeMaxTextChars(maxTextChars);
        List<String> texts = new ArrayList<>();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock text
                    && text.getText() != null
                    && !text.getText().isBlank()) {
                texts.add(truncateText(text.getText(), limit));
            }
        }
        return texts;
    }

    /** 非文本块只记录类型（如 DataBlock/ImageBlock），至少让审计对象看得到“有内容” */
    public static List<String> otherBlocks(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<String> kinds = new ArrayList<>();
        for (ContentBlock block : blocks) {
            if (block != null && !(block instanceof TextBlock)) {
                kinds.add(block.getClass().getSimpleName());
            }
        }
        return kinds;
    }

    /**
     * 序列化并做总长度兜底：超限时不再截断原始 JSON（会得到非法 JSON），
     * 而是换成带 preview 的截断对象，保证下游仍可解析。失败返回 null。
     */
    public static String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            String raw = MAPPER.writeValueAsString(value);
            if (raw.length() <= MAX_ATTRIBUTE_CHARS) {
                return raw;
            }
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("truncated", true);
            wrapper.put("originalLength", raw.length());
            wrapper.put("preview", truncateText(raw, DEFAULT_MAX_TEXT_CHARS));
            return MAPPER.writeValueAsString(wrapper);
        } catch (RuntimeException e) {
            log.warn("Failed to serialize model call content: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 工具返回值/参数多为 JSON，解析成结构后可直接序列化；非 JSON（如错误文本）原样保留。
     * 超过解析上限时不解析，直接截断为文本，避免对大对象做一次无意义的 parse。
     */
    public static Object parseJsonOrText(String raw, Object emptyValue) {
        if (raw == null || raw.isEmpty()) {
            return emptyValue;
        }
        if (raw.length() > MAX_TOOL_PAYLOAD_CHARS) {
            return truncateText(raw, MAX_TOOL_PAYLOAD_CHARS);
        }
        try {
            return MAPPER.readTree(raw);
        } catch (RuntimeException e) {
            log.debug("Tool payload is not valid JSON, keep raw text: {}", e.getMessage());
            return raw;
        }
    }

    public static boolean hasToolCallIdentity(String toolCallId, String toolCallName) {
        return StringUtils.hasText(toolCallId) || StringUtils.hasText(toolCallName);
    }

    /**
     * 同一次调用的分组键；埋点缺失 toolCallId 时退回工具名。
     * 注意同一轮并行调用同名工具且都缺 id 时会被合并，这是缺失埋点下无法区分的固有限制。
     */
    public static String toolKey(String toolCallId, String toolCallName) {
        return StringUtils.hasText(toolCallId)
                ? toolCallId
                : "name:" + (toolCallName != null ? toolCallName : "unknown");
    }

    /** 按 code point 截断，避免切坏 emoji 等代理对；截断时附上原始长度便于排查 */
    public static String truncateText(String text, int maxChars) {
        if (text == null || maxChars <= 0) {
            return text;
        }
        int length = text.codePointCount(0, text.length());
        if (length <= maxChars) {
            return text;
        }
        int end = text.offsetByCodePoints(0, maxChars);
        return text.substring(0, end) + "…（已截断，原始长度 " + length + " 字符）";
    }
}
