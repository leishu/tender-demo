package org.example.middleware;

import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.example.util.Texts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 模型调用审计：每次模型调用落一个 JSON 文件，便于回溯提示词、回复与耗时。
 *
 * <p>几个关键约束：</p>
 * <ul>
 *   <li><b>不丢记录</b>：完成、异常、取消都会落盘（原先只看 {@code doOnComplete}，
 *       出错的调用恰好没有记录）；</li>
 *   <li><b>不阻塞事件线程</b>：序列化与写文件都在单线程执行器里完成；</li>
 *   <li><b>不覆盖</b>：文件名带全局自增序号，且以 {@code CREATE_NEW} 写入；</li>
 *   <li><b>不无限膨胀</b>：启动时与每写入若干条后按“保留天数 + 最大文件数”清理旧文件，
 *       单条记录的超长文本也会按上限截断。</li>
 * </ul>
 */
@Component
@Slf4j
public class ModelCallAuditMiddleware implements MiddlewareBase {

    @Value("${llm.log-path}")
    private String logDirPath;

    /** 审计文件保留天数，超期文件在清理时删除 */
    @Value("${llm.audit.retain-days:30}")
    private int retainDays;

    /** 审计文件最大数量，超出后删除最旧的文件 */
    @Value("${llm.audit.max-files:5000}")
    private int maxFiles;

    /** 单条记录中思考/回复文本的最大字符数，避免单个文件过大 */
    @Value("${llm.audit.max-content-chars:20000}")
    private int maxContentChars;

    /** 每写入多少条触发一次旧文件清理 */
    private static final int PRUNE_EVERY_WRITES = 200;

    /**
     * 线程安全的 ObjectMapper，应用启动时配置一次即可复用
     * Jackson 3 内置 Java 8 时间支持，无需注册模块，时间类型默认按 ISO 字符串序列化
     * <p>数组缩进器改为逐行输出：文本块数组（content）一块一行，便于在日志中逐块对比；
     * 数组为空时输出 {@code []} 而非 {@code [ ]}。
     */
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .defaultPrettyPrinter(new DefaultPrettyPrinter(
                    Separators.createDefaultInstance().withArrayEmptySeparator(""))
                    .withArrayIndenter(new DefaultIndenter("  ", "\n")))
            .build();

    /** 东八区时区，用于日志时间展示 */
    private static final ZoneId ZONE_EAST8 = ZoneId.of("Asia/Shanghai");

    /** 文件名时间戳格式（不含冒号、斜杠等非法/歧义字符） */
    private static final DateTimeFormatter FILE_NAME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");

    /** 全局自增序号：同一毫秒内的并发调用也能得到不同文件名 */
    private final AtomicLong sequence = new AtomicLong();

    /** 已写入条数，用于控制清理频率 */
    private final AtomicLong writeCount = new AtomicLong();

    /** 单线程写盘：保证顺序、避免并发写同一目录，也不占用 Reactor 事件线程 */
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "model-call-audit-writer");
        thread.setDaemon(true);
        return thread;
    });

    @PostConstruct
    protected void init() {
        Path dir = Path.of(logDirPath);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("Failed to create audit log dir: {}", logDirPath, e);
            return;
        }
        prune(dir);
    }

    @PreDestroy
    protected void shutdown() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("模型调用审计写入未在 5 秒内完成，仍有任务未落盘");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {

        Instant startTime = Instant.now();

        // 用于在 flatMap 中捕获、在收尾时读取的线程安全容器
        AtomicLong inputTokens = new AtomicLong(0);
        AtomicLong outputTokens = new AtomicLong(0);

        // 用于累积响应文本，需在 flatMap 之前声明
        StringBuilder responseText = new StringBuilder();
        StringBuilder thinkingText = new StringBuilder();

        // 异常信息：doFinally 拿不到异常对象，先在这里捕获
        AtomicReference<String> errorMessage = new AtomicReference<>();

        return next.apply(input)
                .flatMap(event -> {
                    if (event instanceof ModelCallEndEvent endEvent) {
                        inputTokens.set(endEvent.getUsage().getInputTokens());
                        outputTokens.set(endEvent.getUsage().getOutputTokens());
                    } else if (event instanceof TextBlockDeltaEvent deltaEvent) {
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
                .doOnError(error -> errorMessage.set(describe(error)))
                // doFinally 覆盖 ON_COMPLETE / ON_ERROR / ON_CANCEL 三种收尾，
                // 异常与客户端断开也会留下记录
                .doFinally(signal -> persist(startTime, input, thinkingText, responseText,
                        inputTokens.get(), outputTokens.get(), statusOf(signal), errorMessage.get()));
    }

    private static String statusOf(SignalType signal) {
        if (signal == SignalType.ON_COMPLETE) {
            return "COMPLETED";
        }
        if (signal == SignalType.ON_ERROR) {
            return "ERROR";
        }
        if (signal == SignalType.CANCEL) {
            return "CANCELLED";
        }
        return "UNKNOWN";
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return null;
        }
        String message = error.getMessage();
        String text = error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return Texts.truncate(text, 2_000);
    }

    /**
     * 在事件线程上只做数据整理，真正的序列化与写盘交给后台单线程执行。
     */
    private void persist(Instant startTime,
                         ModelCallInput input,
                         StringBuilder thinkingText,
                         StringBuilder responseText,
                         long inputTokens,
                         long outputTokens,
                         String status,
                         String error) {
        ZonedDateTime east8Time = startTime.atZone(ZONE_EAST8);
        long durationMs = Instant.now().toEpochMilli() - startTime.toEpochMilli();
        String modelName = input.model() != null ? input.model().getModelName() : "unknown";

        AuditLog record = new AuditLog(
                east8Time,
                modelName,
                inputMsg(input),
                truncate(thinkingText.toString()),
                truncate(responseText.toString()),
                inputTokens,
                outputTokens,
                durationMs,
                status,
                error);

        writer.execute(() -> write(record));
    }

    private String truncate(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        if (maxContentChars <= 0 || Texts.codePointLength(content) <= maxContentChars) {
            return content;
        }
        return Texts.truncate(content, maxContentChars) + "\n…（已截断，原始长度 "
                + Texts.codePointLength(content) + " 字符）";
    }

    private List<PromptMessage> inputMsg(ModelCallInput input) {
        List<Msg> messages = input.messages();
        List<PromptMessage> promptMessages = new ArrayList<>();

        if (messages != null) {
            for (Msg msg : messages) {
                MsgRole role = msg.getRole();
                String roleStr = (role != null) ? role.name() : "UNKNOWN";

                if (Objects.equals(role, MsgRole.TOOL)) {
                    promptMessages.add(buildToolMessage(roleStr, msg));
                } else {
                    promptMessages.add(new PromptMessage(roleStr, textOf(msg.getContent())));
                }
            }
        }

        return promptMessages;
    }

    /** 工具结果消息：记录工具名，并把各 ToolResultBlock 中的文本块按块收集为数组。 */
    private ToolPromptMessage buildToolMessage(String role, Msg msg) {
        List<String> contents = new ArrayList<>();
        AtomicReference<String> toolNameRef = new AtomicReference<>();

        for (ContentBlock block : msg.getContent()) {
            if (block instanceof ToolResultBlock result) {
                toolNameRef.set(result.getName());
                contents.addAll(textOf(result.getOutput()));
            }
        }

        return new ToolPromptMessage(role, toolNameRef.get(), contents);
    }

    /**
     * 提取内容块中的文本：每个 TextBlock 单独保留为数组中的一个元素，
     * 便于在审计日志中回溯原始分块结构，而不是被拼接成一段长文本。
     */
    private static List<String> textOf(List<ContentBlock> blocks) {
        if (blocks == null) {
            return List.of();
        }
        return blocks.stream()
                .filter(TextBlock.class::isInstance)
                .map(block -> ((TextBlock) block).getText())
                .filter(text -> !text.isBlank())
                .toList();
    }

    /** 真正写盘的逻辑，运行在 audit-writer 线程。 */
    private void write(AuditLog record) {
        Path dir = Path.of(logDirPath);
        String fileName = "audit-" + record.timestamp().format(FILE_NAME_FORMAT)
                + "-" + String.format("%06d", sequence.incrementAndGet()) + ".json";
        Path file = dir.resolve(fileName);

        try {
            String json = MAPPER.writeValueAsString(record);
            // CREATE_NEW：即使命名意外重复也宁可报错，不覆盖已有审计记录
            Files.writeString(file, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            log.error("Failed to write audit log: {}", file, e);
            return;
        }

        if (writeCount.incrementAndGet() % PRUNE_EVERY_WRITES == 0) {
            prune(dir);
        }
    }

    /**
     * 清理过期/超量的审计文件：先按保留天数删除过期文件，
     * 若数量仍超过上限，再按最后修改时间从旧到新删除。
     */
    private void prune(Path dir) {
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream
                    .filter(path -> path.getFileName().toString().startsWith("audit-"))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong(ModelCallAuditMiddleware::lastModified))
                    .toList();
        } catch (IOException e) {
            log.warn("扫描审计日志目录失败: {}", dir, e);
            return;
        }

        long cutoff = System.currentTimeMillis() - Duration.ofDays(Math.max(retainDays, 1)).toMillis();
        int remaining = files.size();
        for (Path file : files) {
            boolean expired = lastModified(file) < cutoff;
            boolean overflow = remaining > Math.max(maxFiles, 1);
            if (!expired && !overflow) {
                break;
            }
            try {
                Files.deleteIfExists(file);
                remaining--;
            } catch (IOException e) {
                log.warn("删除审计日志失败: {}", file, e);
            }
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * 单条提示消息，对应 JSON 数组中的一个对象；
     * {@code content} 为文本块数组，一个 TextBlock 对应一个元素。
     */
    @Getter
    private static class PromptMessage {
        final String role;
        final List<String> content;

        public PromptMessage(String role, List<String> content) {
            this.role = role;
            this.content = content;
        }
    }

    @Getter
    private static class ToolPromptMessage extends PromptMessage {
        final String toolName;

        public ToolPromptMessage(String role, String toolName, List<String> content) {
            super(role, content);
            this.toolName = toolName;
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
            long durationMs,
            String status,
            String error) {

    }
}
