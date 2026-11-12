package org.example.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.parsing.model.dto.ParseModule;
import org.example.parsing.model.dto.TenderParseEvent;
import org.example.parsing.model.req.TenderRequest;
import org.example.parsing.service.TenderParseEventTransformer;
import org.example.service.RuntimeContextBuilder;
import org.example.task.mapper.TenderTaskMapper;
import org.example.task.model.TaskStatus;
import org.example.task.model.TaskType;
import org.example.task.model.TenderTaskRecord;
import org.example.task.model.dto.TaskEvent;
import org.example.task.model.dto.TenderTaskCreated;
import org.example.task.model.dto.TenderTaskView;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 招标任务的生命周期管理：建任务、跑任务、落终态、断线续看、结果缓存。
 *
 * <p>把原先“一次 POST 拉一条 SSE 流”的解析流程拆成两步：</p>
 * <ol>
 *   <li>{@code POST /tender/parse} 只负责建任务并立刻返回 taskId，任务在后台线程执行；</li>
 *   <li>客户端订阅 {@code GET /tender/tasks/{id}/events}，事件先回放后实时，断线带
 *       {@code Last-Event-ID} 续上。</li>
 * </ol>
 *
 * <p>于是“网络抖动 / 网关超时 / 页面刷新”都不再需要重新解析：模型只在建任务时调用一次，
 * 已经产出的结果始终可以从事件表回放。服务重启时，重启前未完成的任务会被标记为失败并补一条
 * error 事件（不恢复 Agent 会话），已产出的事件仍然可看。</p>
 *
 * <p>目录/章节这类“结果可缓存”的任务复用同一张表：{@link #runOnce} 以幂等键查任务，
 * 命中已完成的任务直接返回缓存结果，不重复调用模型。</p>
 */
@Slf4j
@Service
public class TenderTaskService {

    /** 错误信息列宽 */
    private static final int ERROR_MESSAGE_MAX = 1024;

    /** 幂等键列宽；超出后改用 SHA-256 摘要，保证长度可控且仍然唯一 */
    private static final int IDEMPOTENCY_KEY_MAX = 191;

    private final TenderTaskMapper taskMapper;
    private final TenderTaskEventHub hub;
    private final ObjectProvider<HarnessAgent.Builder> builderProvider;
    private final TenderParseEventTransformer eventTransformer;
    private final RuntimeContextBuilder contextBuilder;
    private final JsonMapper jsonMapper;

    /** 正在执行的流式任务，取消时用它在源头断开上游，停止继续消耗模型 token */
    private final Map<Long, Disposable> running = new ConcurrentHashMap<>();

    /**
     * 启动时是否假设“只有当前实例在跑任务”。
     *
     * <p>单实例部署保持 {@code true}：重启后所有 pending/running 任务都可以安全判定为中断。
     * 多实例/滚动发布必须设为 {@code false}，否则重启的实例会把其它实例正在执行的任务误杀；
     * 此时只恢复超过 {@link #staleMinutes} 没有更新的任务。</p>
     */
    @Value("${tender.task.recovery.assume-exclusive-startup:true}")
    private boolean assumeExclusiveStartup;

    /** 执行中任务多久没有更新视为陈旧；<=0 表示关闭陈旧任务回收 */
    @Value("${tender.task.recovery.stale-minutes:10}")
    private int staleMinutes;

    /** 同步任务的心跳间隔（毫秒）；<=0 表示关闭心跳 */
    @Value("${tender.task.heartbeat-ms:30000}")
    private long heartbeatMillis;

    public TenderTaskService(TenderTaskMapper taskMapper,
                             TenderTaskEventHub hub,
                             ObjectProvider<HarnessAgent.Builder> builderProvider,
                             TenderParseEventTransformer eventTransformer,
                             RuntimeContextBuilder contextBuilder,
                             JsonMapper jsonMapper) {
        this.taskMapper = taskMapper;
        this.hub = hub;
        this.builderProvider = builderProvider;
        this.eventTransformer = eventTransformer;
        this.contextBuilder = contextBuilder;
        this.jsonMapper = jsonMapper;
    }

    // ------------------------------------------------------------- 解析任务

    /**
     * 创建解析任务并立即返回。
     *
     * <p>携带 {@code Idempotency-Key} 时，同一键重复提交返回同一个 taskId：
     * 网络超时后客户端重发、用户重复点击都不会产生第二次模型调用。</p>
     *
     * <p>命中已有任务时不做任何状态改写：任务失败也原样返回，客户端可以回放事件看到失败原因，
     * 想重新解析请使用新的幂等键。</p>
     */
    public TenderTaskCreated createParseTask(TenderRequest request, String idempotencyKey) {
        if (request.getTenderText() == null || request.getTenderText().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "招标文件文本 tenderText 不能为空");
        }

        String key = normalizeKey(idempotencyKey);
        if (key != null) {
            TenderTaskRecord existing = findByIdempotencyKey(key);
            if (existing != null) {
                requireSameType(existing, TaskType.PARSE);
                log.info("命中幂等键，复用已有解析任务: key={}, taskId={}, status={}",
                        key, existing.getId(), existing.getStatus());
                return toCreated(existing);
            }
        }

        TenderTaskRecord task = newTask(TaskType.PARSE, request.getUserId(), key, request);
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            // 并发重复提交：唯一键兜底，回读先创建的那条
            TenderTaskRecord existing = key == null ? null : findByIdempotencyKey(key);
            if (existing == null) {
                throw e;
            }
            requireSameType(existing, TaskType.PARSE);
            return toCreated(existing);
        }

        hub.open(task.getId(), 0);
        startParse(task, request);
        return toCreated(task);
    }

    /**
     * 订阅任务事件：先回放已落库的，再接实时；游标取自客户端的 {@code Last-Event-ID}。
     *
     * <p>任务不存在时返回一条 error 事件而不是 404：SSE 端点一旦开始写响应，
     * 状态码已经无法回退，沿用项目内“SSE 异常走 error 事件”的既有约定。</p>
     */
    public Flux<TaskEvent> stream(long taskId, int lastEventId) {
        return Flux.defer(() -> {
            if (!exists(taskId)) {
                return Flux.just(new TaskEvent(0, ParseModule.ERROR.getCode(),
                        writeJson(TenderParseEvent.error("任务不存在: " + taskId))));
            }
            return hub.stream(taskId, lastEventId);
        });
    }

    /** 查询任务状态；不存在时 404 */
    public TenderTaskView getTask(long taskId) {
        TenderTaskRecord task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在: " + taskId);
        }
        return new TenderTaskView(
                task.getId(),
                task.getTaskType(),
                task.getStatus(),
                task.getLastEventSeq() == null ? 0 : task.getLastEventSeq(),
                task.getErrorMessage(),
                readResult(task.getResultJson()),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getFinishedAt());
    }

    public boolean exists(long taskId) {
        return taskMapper.selectById(taskId) != null;
    }

    /**
     * 取消解析任务：断开上游（停止继续调用模型），补一条 error 事件，状态置为 cancelled。
     *
     * <p>取消与自然结束会竞争同一个终态。这里先用条件更新 {@code finish} 抢终态，
     * 只有抢到的线程才允许追加终止事件，避免出现“状态 succeeded、事件流却以
     * error(解析已取消) 结束”这种自相矛盾的结果。</p>
     */
    public void cancel(long taskId) {
        TenderTaskRecord task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在: " + taskId);
        }
        if (task.getTaskType() != null && !task.getTaskType().isStreaming()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有解析任务支持取消");
        }
        if (task.getStatus() != null && task.getStatus().isTerminal()) {
            return;
        }

        Disposable disposable = running.remove(taskId);
        if (disposable != null) {
            disposable.dispose();
        }

        // 抢终态：没抢到说明自然完成/其它线程已经收尾，不能再补 error 事件
        if (!markTerminal(taskId, TaskStatus.CANCELLED, "解析已取消")) {
            return;
        }

        try {
            // 用事件表的最大序号，而不是可能滞后的 last_event_seq
            hub.openFromDatabase(taskId);
            hub.append(taskId, ParseModule.ERROR.getCode(), TenderParseEvent.error("解析已取消"));
        } catch (Exception e) {
            log.warn("任务 {} 追加取消事件失败", taskId, e);
        } finally {
            closeTask(taskId);
        }
    }

    /**
     * 重启自愈：把重启前还处于 pending/running 的任务标记为失败。
     *
     * <p>Agent 会话不在本次改造范围内，重启后无法接着跑，所以这里不假装任务还活着：
     * 补一条 error 事件并落终态，客户端重连时能明确看到“已中断”，已产出的事件照常可回放。
     * 表还没建、库暂时不可用都不阻断启动。</p>
     *
     * <p>单实例部署（默认）可以安全地恢复全部未完成任务；多实例/滚动发布时
     * {@code assume-exclusive-startup=false}，只恢复超过 {@code stale-minutes} 没有更新的任务，
     * 避免把其它实例正在执行的任务误杀。</p>
     */
    @PostConstruct
    public void recoverInterruptedTasks() {
        long updatedBefore = assumeExclusiveStartup ? Long.MAX_VALUE : staleBefore();
        recoverTasks(updatedBefore, true);
    }

    /**
     * 定时回收“长时间无进展”的任务：客户端断开、进程崩溃等都可能留下 running 任务。
     * 默认 10 分钟没有更新才回收，避免误伤仍在正常执行的长任务。
     */
    @Scheduled(fixedDelayString = "${tender.task.recovery.scan-interval-ms:60000}")
    public void recoverStaleTasks() {
        if (staleMinutes <= 0) {
            return;
        }
        recoverTasks(staleBefore(), false);
    }

    private void recoverTasks(long updatedBefore, boolean startup) {
        List<TenderTaskRecord> stale;
        try {
            stale = taskMapper.selectList(new LambdaQueryWrapper<TenderTaskRecord>()
                    .in(TenderTaskRecord::getStatus, TaskStatus.PENDING.getCode(), TaskStatus.RUNNING.getCode())
                    .lt(TenderTaskRecord::getUpdatedAt, updatedBefore));
        } catch (Exception e) {
            log.warn("扫描未完成任务失败（t_tender_task 可能尚未创建），跳过自愈: {}", e.getMessage());
            return;
        }
        if (stale.isEmpty()) {
            return;
        }

        int recovered = 0;
        for (TenderTaskRecord task : stale) {
            try {
                if (recoverOne(task, recoverMessage(task, startup))) {
                    recovered++;
                }
            } catch (Exception e) {
                log.warn("标记中断任务 {} 失败，已跳过: {}", task.getId(), e.getMessage());
            }
        }
        log.info("已把 {} 个未完成任务标记为失败（候选 {} 个，已产出的事件保留可回放）",
                recovered, stale.size());
    }

    /**
     * 恢复单个任务：先抢终态，抢到才追加 error 事件。
     *
     * @return 是否由本次调用完成恢复
     */
    private boolean recoverOne(TenderTaskRecord task, String message) {
        if (!markTerminal(task.getId(), TaskStatus.FAILED, message)) {
            return false;
        }
        if (task.getTaskType() == TaskType.PARSE) {
            try {
                hub.openFromDatabase(task.getId());
                hub.append(task.getId(), ParseModule.ERROR.getCode(), TenderParseEvent.error(message));
            } catch (Exception e) {
                log.warn("任务 {} 追加中断事件失败: {}", task.getId(), e.getMessage());
            }
        }
        closeTask(task.getId());
        return true;
    }

    private static String recoverMessage(TenderTaskRecord task, boolean startup) {
        if (task.getTaskType() == TaskType.PARSE) {
            return startup
                    ? "服务重启导致解析中断，已产出的结果仍可回放；请重新发起解析"
                    : "解析任务长时间无进展，已中断；请重新发起解析";
        }
        return startup ? "服务重启导致任务中断，请重试" : "任务长时间无进展，已中断；请重试";
    }

    /** 陈旧阈值对应的时间点；关闭时返回 {@link Long#MIN_VALUE}，查询不会命中任何行 */
    private long staleBefore() {
        return staleMinutes <= 0
                ? Long.MIN_VALUE
                : now() - Duration.ofMinutes(staleMinutes).toMillis();
    }

    // --------------------------------------------------------- 同步任务缓存

    /**
     * 以幂等键执行一次“结果可缓存”的任务（目录生成、目录调整、章节撰写）。
     *
     * <p>三种命中情形：</p>
     * <ul>
     *   <li>已完成：直接返回 {@code result_json}，不调用模型；</li>
     *   <li>执行中：返回 409，让客户端稍后重试（重试会命中上一条）；</li>
     *   <li>失败/取消：复用同一行重置为执行中后重跑，不新建任务占号。</li>
     * </ul>
     *
     * <p>没有幂等键时退化为“每次都是新任务”，与原语义一致。</p>
     */
    public <T> Mono<T> runOnce(TaskType type,
                               String userId,
                               String idempotencyKey,
                               Object input,
                               Class<T> resultType,
                               Supplier<Mono<T>> action) {
        // 记录本次调用创建/复用的任务行，供取消回调落终态
        AtomicReference<TenderTaskRecord> taskRef = new AtomicReference<>();

        return Mono.fromCallable(() -> {
                    Prepared<T> prepared = prepare(type, userId, idempotencyKey, input, resultType);
                    taskRef.set(prepared.task());
                    return prepared;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(prepared -> {
                    if (prepared.cached() != null) {
                        return Mono.just(prepared.cached());
                    }
                    TenderTaskRecord task = prepared.task();
                    Mono<T> execution = action.get()
                            .flatMap(result -> Mono.fromCallable(() -> completeSync(task, result))
                                    .subscribeOn(Schedulers.boundedElastic()))
                            .onErrorResume(error -> Mono
                                    .fromCallable(() -> {
                                        failSync(task, error);
                                        return Boolean.TRUE;
                                    })
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .then(Mono.error(error)));
                    return withHeartbeat(task, execution);
                })
                // 客户端断开/请求超时会取消整条链，onErrorResume 不会触发；
                // 这里异步落 cancelled，避免任务永久停在 running、幂等键被一直占用
                .doOnCancel(() -> {
                    TenderTaskRecord task = taskRef.get();
                    if (task != null) {
                        markCancelledAsync(task);
                    }
                });
    }

    /**
     * 给同步任务挂心跳：执行期间周期性刷新 {@code updated_at}，让陈旧回收知道任务还活着。
     *
     * <p>心跳只在真正调用模型时启动，命中缓存直接返回的路径不会产生额外数据库写入。</p>
     */
    private <T> Mono<T> withHeartbeat(TenderTaskRecord task, Mono<T> execution) {
        if (heartbeatMillis <= 0) {
            return execution;
        }
        return Mono.defer(() -> {
            AtomicReference<Disposable> beat = new AtomicReference<>();
            return execution
                    .doOnSubscribe(subscription -> beat.set(
                            Flux.interval(Duration.ofMillis(heartbeatMillis), Schedulers.boundedElastic())
                                    .subscribe(tick -> touch(task.getId()))))
                    .doFinally(signal -> {
                        Disposable disposable = beat.get();
                        if (disposable != null) {
                            disposable.dispose();
                        }
                    });
        });
    }

    private void touch(long taskId) {
        try {
            taskMapper.touch(taskId, now());
        } catch (Exception e) {
            // 心跳失败不影响任务本身；真的长时间无更新时，陈旧回收会接管
            log.debug("任务 {} 心跳更新失败: {}", taskId, e.getMessage());
        }
    }

    // --------------------------------------------------------------- 任务执行

    /** 在 boundedElastic 上跑解析流，逐条事件落库并推送给订阅者 */
    private void startParse(TenderTaskRecord task, TenderRequest request) {
        try {
            RuntimeContext ctx = contextBuilder.build(task.getUserId());
            UserMessage prompt = new UserMessage(
                    "请解析以下招标文件，提取结构化要求：\n" + request.getTenderText());
            HarnessAgent.Builder builder = builderProvider.getObject();

            // 记录最后一条事件：解析是否正常结束以“是否收到 completed”为准，连接关闭本身不算成功
            AtomicReference<TenderParseEvent<?>> lastEvent = new AtomicReference<>();

            Flux<TenderParseEvent<?>> stream = Flux.using(
                            builder::build,
                            agent -> eventTransformer.toSse(agent.streamEvents(prompt, ctx)),
                            HarnessAgent::close)
                    .doOnNext(event -> {
                        lastEvent.set(event);
                        // 终止事件（completed/error）不在这里写：等 finishParse 用条件更新抢到终态后再写，
                        // 避免取消与自然完成并发时事件流里出现两个互相矛盾的终止事件
                        ParseModule module = event.getModule();
                        if (module != null && module != ParseModule.COMPLETED && module != ParseModule.ERROR) {
                            hub.append(task.getId(), module.getCode(), event);
                        }
                    });

            Disposable disposable = stream
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            event -> {
                                // 事件已在 doOnNext 中落库并推送，这里无需重复处理
                            },
                            error -> finishParse(task, lastEvent.get(), error),
                            () -> finishParse(task, lastEvent.get(), null));

            running.put(task.getId(), disposable);
            if (disposable.isDisposed()) {
                // 任务在 put 之前就已结束（如构建 Agent 立即失败），避免留下永远不会被清理的句柄
                running.remove(task.getId(), disposable);
            }
        } catch (Exception e) {
            // 构建上下文/Agent 等同步异常不会进入 reactive 的 onError，必须在这里收尾，
            // 否则任务行会永远停在 running，直到重启或陈旧回收
            log.error("解析任务 {} 启动失败", task.getId(), e);
            failParse(task.getId(), "解析启动失败：" + rootMessage(e));
        }
    }

    /**
     * 收尾解析任务：只有收到 {@code completed} 才算成功。
     *
     * <p>流异常结束、或被网关/客户端中途断开导致上游提前完成时，一律按失败处理，
     * 并补一条 error 事件，保证客户端回放时能拿到明确的终止原因。</p>
     */
    private void finishParse(TenderTaskRecord task, TenderParseEvent<?> lastEvent, Throwable error) {
        long taskId = task.getId();
        running.remove(taskId);

        if (error != null) {
            log.error("解析任务 {} 执行失败", taskId, error);
            failParse(taskId, "解析失败：" + rootMessage(error));
            return;
        }

        ParseModule last = lastEvent == null ? null : lastEvent.getModule();
        if (last == ParseModule.COMPLETED) {
            succeedParse(taskId, lastEvent);
            return;
        }
        if (last == ParseModule.ERROR && lastEvent.getMessage() != null) {
            failParse(taskId, lastEvent.getMessage());
            return;
        }

        String message = "解析流在完成信号前结束（最后事件：" + (last == null ? "无" : last.getCode()) + "）";
        failParse(taskId, message);
    }

    /** 成功收尾：抢到终态才写 completed 事件并关闭订阅 */
    private void succeedParse(long taskId, TenderParseEvent<?> completedEvent) {
        if (!markTerminal(taskId, TaskStatus.SUCCEEDED, null)) {
            return;
        }
        try {
            hub.append(taskId, ParseModule.COMPLETED.getCode(), completedEvent);
        } catch (Exception e) {
            log.warn("任务 {} 追加 completed 事件失败", taskId, e);
        } finally {
            closeTask(taskId);
        }
    }

    /** 失败收尾：抢到终态才写 error 事件并关闭订阅 */
    private void failParse(long taskId, String message) {
        if (!markTerminal(taskId, TaskStatus.FAILED, message)) {
            return;
        }
        try {
            hub.append(taskId, ParseModule.ERROR.getCode(), TenderParseEvent.error(message));
        } catch (Exception e) {
            log.warn("任务 {} 追加 error 事件失败", taskId, e);
        } finally {
            closeTask(taskId);
        }
    }

    /**
     * 条件落地终态。
     *
     * @return {@code true} 表示本次调用抢到了终态；{@code false} 表示任务已被其它流程收尾，
     *         调用方不应再写终止事件
     */
    private boolean markTerminal(long taskId, TaskStatus status, String errorMessage) {
        int updated = taskMapper.finish(taskId, status.getCode(), truncate(errorMessage),
                null, now());
        if (updated == 0) {
            log.info("任务 {} 已处于终态，忽略本次 {} 落地", taskId, status.getCode());
            return false;
        }
        log.info("任务 {} 结束: status={}", taskId, status.getCode());
        return true;
    }

    /** 关闭该任务的事件流并释放内存 */
    private void closeTask(long taskId) {
        hub.complete(taskId);
        hub.close(taskId);
    }

    // ------------------------------------------------------------- 同步任务内部

    private record Prepared<T>(TenderTaskRecord task, T cached) {
    }

    /** 幂等判定：命中缓存 / 正在执行 / 需要重跑 / 新建，全部在这里定下来（阻塞调用） */
    private <T> Prepared<T> prepare(TaskType type, String userId, String idempotencyKey,
                                    Object input, Class<T> resultType) {
        String key = normalizeKey(idempotencyKey);
        if (key != null) {
            TenderTaskRecord existing = findByIdempotencyKey(key);
            if (existing != null) {
                requireSameType(existing, type);
                TaskStatus status = existing.getStatus();
                if (status == TaskStatus.SUCCEEDED && existing.getResultJson() != null) {
                    log.info("命中已完成任务，直接返回缓存结果: taskId={}, key={}", existing.getId(), key);
                    return new Prepared<>(existing, readResult(existing.getResultJson(), resultType));
                }
                if (status == TaskStatus.RUNNING || status == TaskStatus.PENDING) {
                    // 客户端断开/进程崩溃可能留下永久 running 的任务；
                    // 超过租约时间没有更新就允许接管重跑，否则继续 409
                    if (staleMinutes > 0
                            && taskMapper.reopenStale(existing.getId(), now(), staleBefore()) > 0) {
                        log.info("复用陈旧的执行中任务重跑: taskId={}, key={}", existing.getId(), key);
                        existing.setStatus(TaskStatus.RUNNING);
                        existing.setResultJson(null);
                        existing.setErrorMessage(null);
                        return new Prepared<>(existing, null);
                    }
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "相同幂等键的任务正在执行，请稍后重试");
                }
                // 失败/取消：复用同一行重跑，保持幂等键不被释放
                if (taskMapper.reopen(existing.getId(), now()) > 0) {
                    log.info("复用失败的幂等任务重跑: taskId={}, key={}", existing.getId(), key);
                    existing.setStatus(TaskStatus.RUNNING);
                    existing.setResultJson(null);
                    existing.setErrorMessage(null);
                    return new Prepared<>(existing, null);
                }
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "相同幂等键的任务正在重试，请稍后重试");
            }
        }

        TenderTaskRecord task = newTask(type, userId, key, input);
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            // 并发同键：唯一索引兜底，让调用方稍后重试（重试会命中缓存）
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "相同幂等键的任务正在执行，请稍后重试", e);
        }
        return new Prepared<>(task, null);
    }

    private <T> T completeSync(TenderTaskRecord task, T result) {
        String json = writeJson(result);
        int updated = taskMapper.finish(task.getId(), TaskStatus.SUCCEEDED.getCode(), null, json, now());
        if (updated == 0) {
            // 例如任务已被取消或陈旧回收抢先落终态。结果本身仍返回给当前调用方，
            // 但不再假装它被缓存成功，避免下次幂等重试读到不一致的状态。
            log.warn("任务 {} 已被其它流程置为终态，本次结果未写入缓存: type={}",
                    task.getId(), task.getTaskType());
        } else {
            log.info("任务 {} 完成并缓存结果: type={}", task.getId(), task.getTaskType());
        }
        return result;
    }

    private void failSync(TenderTaskRecord task, Throwable error) {
        String message = truncate(errorMessage(error));
        int updated = taskMapper.finish(task.getId(), TaskStatus.FAILED.getCode(), message, null, now());
        if (updated == 0) {
            log.info("任务 {} 已处于终态，忽略本次失败落地: type={}", task.getId(), task.getTaskType());
        } else {
            log.warn("任务 {} 失败: type={}, message={}", task.getId(), task.getTaskType(), message);
        }
    }

    /**
     * 客户端取消时异步落终态。
     *
     * <p>取消回调可能运行在 reactor-http 线程，不能在那里做阻塞 JDBC 写，因此切到
     * {@link Schedulers#boundedElastic()}。条件更新保证只会覆盖仍在执行的任务。</p>
     */
    private void markCancelledAsync(TenderTaskRecord task) {
        Schedulers.boundedElastic().schedule(() -> {
            try {
                int updated = taskMapper.finish(task.getId(), TaskStatus.CANCELLED.getCode(),
                        truncate("客户端断开或请求被取消"), null, now());
                if (updated > 0) {
                    log.info("任务 {} 因客户端取消而终止: type={}", task.getId(), task.getTaskType());
                }
            } catch (Exception e) {
                log.warn("标记任务 {} 取消失败: {}", task.getId(), e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ 工具

    private TenderTaskRecord newTask(TaskType type, String userId, String key,
                                     Object input) {
        long now = now();
        TenderTaskRecord task = new TenderTaskRecord();
        task.setTaskType(type);
        task.setStatus(TaskStatus.RUNNING);
        task.setUserId(StringUtils.hasText(userId) ? userId : "__anon__");
        task.setIdempotencyKey(key);
        task.setInputJson(writeJson(input));
        task.setLastEventSeq(0);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return task;
    }

    private TenderTaskCreated toCreated(TenderTaskRecord task) {
        return new TenderTaskCreated(task.getId(), task.getTaskType(), task.getStatus(), eventsUrl(task.getId()));
    }

    private TenderTaskRecord findByIdempotencyKey(String key) {
        return taskMapper.selectOne(new LambdaQueryWrapper<TenderTaskRecord>()
                .eq(TenderTaskRecord::getIdempotencyKey, key)
                .last("LIMIT 1"));
    }

    private static void requireSameType(TenderTaskRecord task, TaskType expected) {
        if (task.getTaskType() != expected) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该幂等键已被其它类型的任务占用（" + task.getTaskType().getCode() + "）");
        }
    }

    private String writeJson(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("任务输入序列化失败", e);
        }
    }

    private Object readResult(String json) {
        return readResult(json, Object.class);
    }

    private <T> T readResult(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return jsonMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("任务结果反序列化失败，按空结果处理: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 幂等键归一化：空值视为“不做幂等”；超长时取 SHA-256 摘要，
     * 既满足列宽又保留“同一输入同一键”的语义。
     */
    private static String normalizeKey(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        String trimmed = key.trim();
        if (trimmed.length() <= IDEMPOTENCY_KEY_MAX) {
            return trimmed;
        }
        return "sha256:" + sha256(trimmed);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // SHA-256 一定存在；退化到截断，保证不因摘要算法缺失而阻塞业务
            return value.substring(0, IDEMPOTENCY_KEY_MAX);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return StringUtils.hasText(message) ? message : current.getClass().getSimpleName();
    }

    /** 失败原因：显式状态异常优先用 reason，避免把 “500 INTERNAL_SERVER_ERROR” 原样抛给前端 */
    private static String errorMessage(Throwable error) {
        if (error instanceof ResponseStatusException statusError) {
            String reason = statusError.getReason();
            if (StringUtils.hasText(reason)) {
                return reason;
            }
        }
        return rootMessage(error);
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= TenderTaskService.ERROR_MESSAGE_MAX) {
            return value;
        }
        return value.substring(0, TenderTaskService.ERROR_MESSAGE_MAX);
    }

    private static String eventsUrl(long taskId) {
        return "/tender/tasks/" + taskId + "/events";
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
