package org.example.matching.service;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import lombok.extern.slf4j.Slf4j;
import org.example.matching.model.PersonnelMatchResult;
import org.example.matching.model.QualificationMatchResult;
import org.example.matching.model.dto.MatchEvent;
import org.example.matching.model.dto.MatchModule;
import org.example.matching.model.req.MatchRequest;
import org.example.matching.prompt.MatchPromptProvider;
import org.example.parsing.model.PersonnelRequirement;
import org.example.parsing.model.QualificationRequirement;
import org.example.parsing.model.TenderRequirements;
import org.example.parsing.repo.TenderRequirementsRepository;
import org.example.service.RuntimeContextBuilder;
import org.example.task.service.TaskStream;
import org.example.task.service.TaskStreamEvent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 人员匹配与企业资质匹配的编排服务。
 *
 * <p>一次请求内创建两个工作区声明的子 Agent（{@code personnel-matcher}、
 * {@code qualification-matcher}），用 {@link Flux#merge(Iterable)} 并发订阅两路事件流：
 * 任一子 Agent 每输出一条完整匹配结果，就立即转换为事件交给任务层落库并推送；
 * 两路都结束后再追加一条 {@code completed} 事件。</p>
 *
 * <p>匹配与解析一样走任务化：{@code POST /tender/match} 只建任务并返回 taskId，
 * 模型在后台执行，客户端订阅 {@code /tender/tasks/{taskId}/events} 先回放后实时，
 * 断线重连不会重跑模型。</p>
 *
 * <p>这里没有让主 Agent 通过 {@code agent_spawn} 自行调度，而是由 Java 侧确定性编排：
 * 两路并发是固定的，事件的模块归属和结束时机也可控。子 Agent 仍按工作区
 * {@code subagents/*.md} 声明创建，工具白名单与系统提示词与主 Agent 调度时一致。</p>
 */
@Slf4j
@Service
public class TenderMatchingService {

    static final String PERSONNEL_AGENT = "personnel-matcher";
    static final String QUALIFICATION_AGENT = "qualification-matcher";

    private static final Set<String> MATCHER_AGENTS = Set.of(PERSONNEL_AGENT, QUALIFICATION_AGENT);

    private final ObjectProvider<HarnessAgent.Builder> builderProvider;
    private final RuntimeContextBuilder contextBuilder;
    private final TenderRequirementsRepository requirementsRepository;
    private final MatchEventTransformer eventTransformer;
    private final MatchPromptProvider promptProvider;

    @Value("${agentscope.workspace-path}")
    private String workspacePath;

    /** 首轮缺失结果时的最大自动重试次数，默认 1 次；设为 0 可关闭重试 */
    @Value("${matching.max-retries:1}")
    private int maxRetries;

    public TenderMatchingService(ObjectProvider<HarnessAgent.Builder> builderProvider,
                                 RuntimeContextBuilder contextBuilder,
                                 TenderRequirementsRepository requirementsRepository,
                                 MatchEventTransformer eventTransformer,
                                 MatchPromptProvider promptProvider) {
        this.builderProvider = builderProvider;
        this.contextBuilder = contextBuilder;
        this.requirementsRepository = requirementsRepository;
        this.eventTransformer = eventTransformer;
        this.promptProvider = promptProvider;
    }

    /**
     * 校验并读取招标要求，供建任务时做同步校验。
     *
     * <p>参数错误必须在建任务时以 400/404 返回，而不是等客户端订阅事件后才从
     * {@code error} 事件里得知；同时把读到的对象交给后台执行，避免重复查库。</p>
     */
    public TenderRequirements requireRequirements(Long tenderRequirementId) {
        if (tenderRequirementId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "招标要求 tenderRequirementId 不能为空");
        }
        TenderRequirements requirements = requirementsRepository.findById(tenderRequirementId);
        if (requirements == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "招标要求不存在：" + tenderRequirementId);
        }
        return requirements;
    }

    /**
     * 校验招标要求并生成匹配任务的流式契约（此时不跑模型）。
     *
     * <p>任务层只认 {@link TaskStream}：建流、落库、推送、终态都不需要知道匹配的内部编排。
     * 校验放在建流之前，参数错误仍以 400/404 的 HTTP 状态码返回；模型调用放在
     * {@link Flux#defer} 里，只有任务真正被订阅时才构建上下文与子 Agent，
     * 幂等命中不会白跑一次（匹配要起两个子 Agent，代价比解析更高）。</p>
     *
     * @param callerId 调用者身份；作为 Agent 会话命名空间，不取请求体里的 {@code userId}
     */
    public TaskStream taskStream(MatchRequest request, String callerId) {
        // 会话 ID 先于查库定下来：非法回传值直接 400，而不是让后台任务失败；
        // 不传时由服务端签发，响应里会把它回给客户端
        String sessionId = contextBuilder.resolveSessionId(request.getSessionId());
        TenderRequirements requirements = requireRequirements(request.getTenderRequirementId());
        Flux<TaskStreamEvent> events = Flux.defer(() -> run(callerId, sessionId, requirements))
                .filter(event -> event.getModule() != null)
                .map(event -> new TaskStreamEvent(
                        event.getModule().getCode(), event, event.getMessage()));
        // 匹配的 error 是非终止告警（某一路失败/缺条），必须随流落库给回放；
        // 只有 completed 才是成功信号
        return new TaskStream(events, true, sessionId);
    }

    private Flux<MatchEvent<?>> run(String userId, String sessionId, TenderRequirements requirements) {
        // 会话 ID 已由 taskStream 定好：主/子 Agent 都续写同一段上下文
        RuntimeContext ctx = contextBuilder.build(userId, sessionId);
        HarnessAgent.Builder builder = builderProvider.getObject();
        if (!registerMatcherDeclarations(builder)) {
            // 不补 completed：配置缺失属于任务失败，任务层据此把状态落为 failed 并保留错误原因
            return Flux.just(
                    MatchEvent.error("匹配子 Agent 未配置，请检查工作区 subagents/ 目录"));
        }

        return Flux.using(
                builder::build,
                coordinator -> runConcurrently(coordinator, ctx, requirements),
                HarnessAgent::close
        );
    }

    /**
     * 把匹配子 Agent 声明注册到本次构建的 Builder。
     *
     * <p>动态子 Agent 中间件要等主 Agent 第一次推理时才扫描工作区，直接创建子 Agent
     * 时其 manager 里还没有这些声明，因此这里显式加载并注册，保证不启动主 Agent
     * 也能通过 {@link DefaultAgentManager#createAgent} 创建。
     */
    private boolean registerMatcherDeclarations(HarnessAgent.Builder builder) {
        Path workspace = Path.of(workspacePath);
        List<SubagentDeclaration> declarations = AgentSpecLoader.loadFromDirectory(
                workspace.resolve("subagents"), workspace);
        List<SubagentDeclaration> matchers = declarations.stream()
                .filter(declaration -> MATCHER_AGENTS.contains(declaration.getName()))
                .toList();
        if (matchers.size() != MATCHER_AGENTS.size()) {
            log.warn("匹配子 Agent 声明不完整：期望 {}，实际 {}", MATCHER_AGENTS, matchers.stream()
                    .map(SubagentDeclaration::getName)
                    .toList());
            return false;
        }
        matchers.forEach(builder::subagent);
        return true;
    }

    private Flux<MatchEvent<?>> runConcurrently(HarnessAgent coordinator,
                                                RuntimeContext ctx,
                                                TenderRequirements requirements) {
        DefaultAgentManager manager = coordinator.getSubagentAgentManager();
        if (manager == null) {
            return Flux.just(MatchEvent.error("子 Agent 管理器未初始化"));
        }

        List<Flux<MatchEvent<?>>> streams = new ArrayList<>();
        if (!CollectionUtils.isEmpty(requirements.getPersonnel())) {
            streams.add(runSubagentWithRetry(manager, PERSONNEL_AGENT, ctx,
                    MatchModule.PERSONNEL, PersonnelMatchResult.class,
                    requirements.getPersonnel(), PersonnelRequirement::getId,
                    items -> promptProvider.renderPersonnel(requirements, items)));
        }
        if (!CollectionUtils.isEmpty(requirements.getQualifications())) {
            streams.add(runSubagentWithRetry(manager, QUALIFICATION_AGENT, ctx,
                    MatchModule.QUALIFICATION, QualificationMatchResult.class,
                    requirements.getQualifications(), QualificationRequirement::getId,
                    items -> promptProvider.renderQualification(requirements, items)));
        }

        log.info("开始并发匹配：人员 {} 条，企业资质 {} 条",
                CollectionUtils.isEmpty(requirements.getPersonnel())
                        ? 0 : requirements.getPersonnel().size(),
                CollectionUtils.isEmpty(requirements.getQualifications())
                        ? 0 : requirements.getQualifications().size());

        return Flux.merge(streams)
                .concatWith(Flux.just(MatchEvent.completed()))
                .doOnComplete(() -> log.info("人员/企业资质匹配全部完成，发送 completed 信号"));
    }

    /**
     * 创建并订阅一个匹配子 Agent，首轮缺条时只针对缺失的 requirementId 自动补跑。
     *
     * <p>每次尝试使用独立的 sessionId，避免重试复用上一轮对话导致模型重复输出。
     * 重试结束后仍缺条时，追加 error 事件把缺口暴露给前端。</p>
     */
    private <R, T> Flux<MatchEvent<?>> runSubagentWithRetry(DefaultAgentManager manager,
                                                            String agentId,
                                                            RuntimeContext parentCtx,
                                                            MatchModule module,
                                                            Class<T> type,
                                                            List<R> allItems,
                                                            Function<R, Long> idOf,
                                                            Function<List<R>, String> promptRenderer) {
        Set<Long> expected = allItems.stream()
                .map(idOf)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> seen = ConcurrentHashMap.newKeySet();

        List<Flux<MatchEvent<?>>> attempts = new ArrayList<>();
        attempts.add(Flux.defer(() -> attempt(manager, agentId,
                promptRenderer.apply(allItems), parentCtx, module, type, seen, 0)));

        for (int i = 1; i <= Math.max(0, maxRetries); i++) {
            final int attemptNo = i;
            attempts.add(Flux.defer(() -> {
                Set<Long> missing = missingIds(expected, seen);
                if (missing.isEmpty()) {
                    return Flux.empty();
                }
                List<R> retryItems = allItems.stream()
                        .filter(item -> missing.contains(idOf.apply(item)))
                        .toList();
                if (retryItems.isEmpty()) {
                    return Flux.empty();
                }
                log.warn("{} 首轮缺少 {} 条结果，开始第 {} 次重试：requirementId={}",
                        module.getCode(), missing.size(), attemptNo, missing);
                return attempt(manager, agentId, promptRenderer.apply(retryItems),
                        parentCtx, module, type, seen, attemptNo);
            }));
        }

        return Flux.concat(attempts)
                .concatWith(Flux.defer(() ->
                        eventTransformer.missingResultsEvent(module, seen, expected)));
    }

    /**
     * 创建并订阅一次匹配子 Agent。
     *
     * <p>子 Agent 的名称来自工作区声明；每个子 Agent 使用独立 sessionId，避免两个
     * 并发调用在共享的 {@code AgentStateStore} 中互相覆盖。</p>
     */
    private <T> Flux<MatchEvent<?>> attempt(DefaultAgentManager manager,
                                            String agentId,
                                            String prompt,
                                            RuntimeContext parentCtx,
                                            MatchModule module,
                                            Class<T> type,
                                            Set<Long> seen,
                                            int attemptNo) {
        Agent agent;
        try {
            agent = manager.createAgent(agentId, parentCtx);
        } catch (Exception e) {
            log.error("创建 {} 子 Agent 失败（attempt {}）", agentId, attemptNo, e);
            return Flux.empty();
        }
        if (!(agent instanceof HarnessAgent harness)) {
            log.error("{} 不是可流式调用的 HarnessAgent（attempt {}）", agentId, attemptNo);
            return Flux.empty();
        }

        RuntimeContext childCtx = RuntimeContext.builder(parentCtx)
                .sessionId(parentCtx.getSessionId() + "-" + module.getCode()
                        + (attemptNo == 0 ? "" : "-retry" + attemptNo))
                .build();

        return Flux.using(
                () -> harness,
                child -> eventTransformer.toSseTracked(
                        child.streamEvents(new UserMessage(prompt), childCtx), module, type, seen),
                this::closeQuietly
        );
    }

    /** 计算本轮仍缺失的 requirementId，保持输入顺序，便于日志与重试提示可读 */
    static Set<Long> missingIds(Set<Long> expected, Set<Long> seen) {
        Set<Long> missing = new LinkedHashSet<>(expected);
        missing.removeAll(seen);
        return missing;
    }

    private void closeQuietly(HarnessAgent agent) {
        try {
            agent.close();
        } catch (Exception e) {
            log.warn("关闭匹配子 Agent 失败", e);
        }
    }
}
