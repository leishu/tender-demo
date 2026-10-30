package org.example.middleware;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@Slf4j
public class ModelCallAuditMiddleware implements MiddlewareBase {

    @Value("${llm.log-path}")
    private String logDirPath;

    /**
     * 线程安全的 ObjectMapper，应用启动时配置一次即可复用
     * Jackson 3 内置 Java 8 时间支持，无需注册模块，时间类型默认按 ISO 字符串序列化
     */
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    /** 东八区时区，用于日志时间展示 */
    private static final ZoneId ZONE_EAST8 = ZoneId.of("Asia/Shanghai");

    /** 文件名时间戳格式（不含冒号、斜杠等非法/歧义字符） */
    private static final DateTimeFormatter FILE_NAME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");

    @PostConstruct
    protected void init() {
        // 确保日志目录（含多级子目录）存在，不存在则递归创建
        File logDir = new File(logDirPath);
        if (!logDir.exists()) {
            if (!logDir.mkdirs()) {
                log.error("Failed to create audit log dir: {}", logDirPath);
            }
        }
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {

        Instant startTime = Instant.now();

        // 用于在 flatMap 中捕获、在 doOnComplete 中读取的线程安全容器
        AtomicLong inputTokens = new AtomicLong(0);
        AtomicLong outputTokens = new AtomicLong(0);

        // 用于累积响应文本，需在 flatMap 之前声明
        StringBuilder responseText = new StringBuilder();
        StringBuilder thinkingText = new StringBuilder();

        return next.apply(input)
                .flatMap(event -> {
                    if (event instanceof ModelCallEndEvent endEvent) {
                        inputTokens.set(endEvent.getUsage().getInputTokens());
                        outputTokens.set(endEvent.getUsage().getOutputTokens());
                    } else if (event instanceof TextBlockDeltaEvent deltaEvent) {
                        // 拼接增量文本
                        String delta = deltaEvent.getDelta();
                        if (delta != null) {
                            responseText.append(delta);
                        }
                    } else if (event instanceof ThinkingBlockDeltaEvent thinkingEvent) {
                        String delta = thinkingEvent.getDelta();
                        if (delta != null) {
                            thinkingText.append(delta);
                        }
                    }
                    return Flux.just(event);
                })
                .doOnComplete(() -> {
                    Instant endTime = Instant.now();
                    long durationMs = endTime.toEpochMilli() - startTime.toEpochMilli();

                    String modelName = input.model() != null
                            ? input.model().getModelName()
                            : "unknown";

                    writeJsonLine(startTime, modelName, inputMsg(input),
                            thinkingText.toString(), responseText.toString(),
                            inputTokens.get(), outputTokens.get(), durationMs);
                });
    }

    private List<PromptMessage> inputMsg(ModelCallInput input) {
        List<Msg> messages = input.messages();
        List<PromptMessage> promptMessages = new ArrayList<>();

        if (messages != null) {
            for (Msg msg : messages) {
                MsgRole role = msg.getRole();
                String roleStr = (role != null) ? role.name() : "UNKNOWN";

                if (Objects.equals(role, MsgRole.TOOL)) {
                    // 收集 ToolResultBlock：提取 output 中的文本，并附带工具名与执行状态
                    StringBuilder toolDetail = new StringBuilder();
                    AtomicReference<String> toolNameRef = new AtomicReference<>();

                    msg.getContent().stream()
                            .filter(block -> block instanceof ToolResultBlock)
                            .forEach(block -> {
                                ToolResultBlock result = (ToolResultBlock) block;
                                String name = result.getName();
                                toolNameRef.set(name);
                                toolDetail.append(result.getOutput().stream()
                                        .filter(cb -> cb instanceof TextBlock)
                                        .map(cb -> ((TextBlock) cb).getText())
                                        .collect(Collectors.joining()));
                            });

                    promptMessages.add(new ToolPromptMessage(roleStr, toolNameRef.get(), toolDetail.toString()));
                }else {
                    // 从 ContentBlock 列表中只提取 TextBlock 的文本
                    String textContent = msg.getContent().stream()
                            .filter(block -> block instanceof TextBlock)
                            .map(block -> ((TextBlock) block).getText())
                            .collect(Collectors.joining());

                    promptMessages.add(new PromptMessage(roleStr, textContent));
                }
            }
        }

        return promptMessages;
    }

    /**
     * 单条提示消息，对应 JSON 数组中的一个对象
     */
    @Getter
    private static class PromptMessage {
        final String role;
        final String content;

        public PromptMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    @Getter
    private static class ToolPromptMessage extends PromptMessage {
        final String toolName;

        public ToolPromptMessage(String role, String toolName, String content) {
            super(role, content);
            this.toolName = toolName;
        }
    }

    private void writeJsonLine(Instant timestamp, String modelName, List<PromptMessage> prompt,
                               String thinkingText,
                               String responseText,
                               long inputTokens, long outputTokens,
                               long durationMs) {
        // 将 UTC 时间转换为东八区展示
        ZonedDateTime east8Time = timestamp.atZone(ZONE_EAST8);

        // 每次调用生成一个独立文件，文件名带格式化的东八区时间戳
        String fileName = "audit-" + east8Time.format(FILE_NAME_FORMAT) + ".json";
        File logFile = new File(logDirPath, fileName);

        // 使用静态复用的 ObjectMapper 序列化，输出缩进美观的格式
        try (FileWriter fw = new FileWriter(logFile, false)) {
            fw.write(MAPPER.writeValueAsString(
                    new AuditLog(east8Time, modelName, prompt, thinkingText, responseText,
                            inputTokens, outputTokens, durationMs)));
        } catch (IOException e) {
            // 审计日志写入失败不应中断主流程，记录到标准错误即可
            log.error("Failed to write audit log: {}", e.getMessage());
        }
    }

    /**
     * 审计日志记录结构，字段名与 JSON 输出一一对应
     */
    private record AuditLog(
            ZonedDateTime timestamp,
            String model,
            List<PromptMessage> prompt,
            String thinkingText,
            String responseText,
            long inputTokens,
            long outputTokens,
            long durationMs) {

    }
}