package org.example.middleware;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

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
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
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
 *   <li><b>不阻塞事件线程</b>：事件线程只做快照，序列化与写文件都在单线程执行器里完成；</li>
 *   <li><b>不覆盖</b>：文件名带全局自增序号，且以 {@code CREATE_NEW} 写入；</li>
 *   <li><b>不无限膨胀</b>：启动时与每写入若干条后按“保留天数 + 最大文件数”清理旧文件，
 *       单条记录的超长文本也会按上限截断。</li>
 * </ul>
 *
 * <p>内容字段（prompt / thinkingText / responseText / toolCalls）与 Langfuse 中间件共用
 * {@link ModelCallContentCollector} / {@link ModelCallContent}，因此两边对同一次调用给出
 * 的字段结构与截断结果一致。隐私场景可用 {@code llm.audit.capture-content=false} 关闭正文，
 * 只保留模型名、token、耗时与状态。</p>
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

    /**
     * 单条记录中思考/回复文本的最大字符数，避免单个文件过大。
     * 小于等于 0 视为未配置，退回 {@link ModelCallContent#DEFAULT_MAX_TEXT_CHARS}。
     */
    @Value("${llm.audit.max-content-chars:50000}")
    private int maxContentChars;

    /** 是否记录提示词、思考过程、回复与工具调用；关闭后只留元数据 */
    @Value("${llm.audit.capture-content:true}")
    private boolean captureContent;

    /** 每写入多少条触发一次旧文件清理 */
    private static final int PRUNE_EVERY_WRITES = 200;

    /** 写盘任务队列容量；磁盘慢或调用密集时避免无限堆积 */
    private static final int WRITE_QUEUE_CAPACITY = 1_000;

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

    /**
     * 单线程写盘：保证顺序、避免并发写同一目录，也不占用 Reactor 事件线程。
     * 使用有界队列，队列满时丢弃并告警，宁可少一条审计也不能拖垮模型调用。
     */
    private final ExecutorService writer = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(WRITE_QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "model-call-audit-writer");
                thread.setDaemon(true);
                return thread;
            },
            (runnable, executor) -> log.warn(
                    "模型调用审计写入任务被拒绝（队列容量 {}，或写入线程已关闭），丢弃一条记录",
                    WRITE_QUEUE_CAPACITY));

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
        // 每次订阅一份独立状态：同一个 Flux 被重放/重试时，两次记录不会互相污染
        return Flux.defer(() -> {
            Instant startTime = Instant.now();
            ModelCallContentCollector collector = new ModelCallContentCollector(maxContentChars);
            AtomicReference<ChatUsage> usage = new AtomicReference<>();
            AtomicReference<String> replyId = new AtomicReference<>();
            // 异常信息：doFinally 拿不到异常对象，先在这里捕获
            AtomicReference<String> errorMessage = new AtomicReference<>();

            return next.apply(input)
                    .doOnNext(event -> {
                        if (event instanceof ModelCallStartEvent start) {
                            if (start.getReplyId() != null) {
                                replyId.set(start.getReplyId());
                            }
                        } else if (event instanceof ModelCallEndEvent end) {
                            if (end.getReplyId() != null) {
                                replyId.set(end.getReplyId());
                            }
                            // 模型未返回 usage 时该字段可能为 null，必须判空，
                            // 否则一次正常调用会在这里抛 NPE
                            if (end.getUsage() != null) {
                                usage.set(end.getUsage());
                            }
                        }
                        if (captureContent) {
                            collector.onEvent(event);
                        }
                    })
                    .doOnError(error -> errorMessage.set(describe(error)))
                    // doFinally 覆盖 ON_COMPLETE / ON_ERROR / ON_CANCEL 三种收尾，
                    // 异常与客户端断开也会留下记录
                    .doFinally(signal -> persist(
                            startTime,
                            ctx,
                            input,
                            collector,
                            usage.get(),
                            replyId.get(),
                            statusOf(signal),
                            errorMessage.get()));
        });
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
        return ModelCallContent.truncateText(text, 2_000);
    }

    /**
     * 事件线程只做快照：记录引用与耗时，序列化、截断与写盘都交给后台单线程，
     * 避免在模型调用链路上做大数据量的 JSON 处理。
     */
    private void persist(Instant startTime,
                         RuntimeContext ctx,
                         ModelCallInput input,
                         ModelCallContentCollector collector,
                         ChatUsage usage,
                         String replyId,
                         String status,
                         String error) {
        AuditSnapshot snapshot = new AuditSnapshot(
                startTime,
                ctx != null ? ctx.getSessionId() : null,
                ctx != null ? ctx.getUserId() : null,
                replyId,
                input,
                collector,
                usage,
                Instant.now().toEpochMilli() - startTime.toEpochMilli(),
                status,
                error);
        try {
            writer.execute(() -> write(buildRecord(snapshot)));
        } catch (RejectedExecutionException e) {
            // 应用关闭或队列拒绝：审计失败不能影响已经结束的模型调用
            log.warn("模型调用审计任务被拒绝，丢弃一条记录: {}", e.getMessage());
        }
    }

    /** 在写盘线程构建记录：提示词、思考过程、回复与工具调用都在这里序列化。 */
    private AuditLog buildRecord(AuditSnapshot snapshot) {
        ModelCallInput input = snapshot.input();
        List<Msg> messages = input.messages();
        ModelCallContentCollector collector = snapshot.collector();
        ChatUsage usage = snapshot.usage();

        List<ModelCallContent.Message> prompt =
                captureContent ? ModelCallContent.messages(messages, maxContentChars) : null;
        String thinkingText = captureContent && collector.hasThinkingText()
                ? collector.thinkingText()
                : null;
        String responseText = captureContent ? collector.responseText() : null;
        List<ModelCallContent.ToolCall> toolCalls =
                captureContent && collector.hasToolCalls() ? collector.toolCalls() : null;

        return new AuditLog(
                snapshot.startTime().atZone(ZONE_EAST8),
                snapshot.sessionId(),
                snapshot.userId(),
                snapshot.replyId(),
                input.model() != null ? input.model().getModelName() : "unknown",
                prompt,
                thinkingText,
                responseText,
                toolCalls,
                usage != null ? usage.getInputTokens() : 0,
                usage != null ? usage.getOutputTokens() : 0,
                snapshot.durationMs(),
                snapshot.status(),
                snapshot.error());
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

    /** 事件线程到写盘线程的不可变快照；collector 在流终止后不再被修改 */
    private record AuditSnapshot(
            Instant startTime,
            String sessionId,
            String userId,
            String replyId,
            ModelCallInput input,
            ModelCallContentCollector collector,
            ChatUsage usage,
            long durationMs,
            String status,
            String error) {
    }

    /**
     * 审计日志记录结构，字段名与 JSON 输出一一对应。
     * 内容字段在 {@code capture-content=false} 时为 null，序列化时直接省略。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record AuditLog(
            ZonedDateTime timestamp,
            String sessionId,
            String userId,
            String replyId,
            String model,
            List<ModelCallContent.Message> prompt,
            String thinkingText,
            String responseText,
            List<ModelCallContent.ToolCall> toolCalls,
            long inputTokens,
            long outputTokens,
            long durationMs,
            String status,
            String error) {
    }
}
