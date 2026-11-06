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
import org.example.parsing.model.TenderRequirements;
import org.example.parsing.repo.TenderRequirementsRepository;
import org.example.service.RuntimeContextBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 人员匹配与企业资质匹配的编排服务。
 *
 * <p>一次请求内创建两个工作区声明的子 Agent（{@code personnel-matcher}、
 * {@code qualification-matcher}），用 {@link Flux#merge(Iterable)} 并发订阅两路事件流：
 * 任一子 Agent 每输出一条完整匹配结果，就立即转换为 SSE 事件推送给客户端；
 * 两路都结束后再追加一条 {@code completed} 事件。</p>
 *
 * <p>这里没有让主 Agent 通过 {@code agent_spawn} 自行调度，而是由 Java 侧确定性编排：
 * 两路并发是固定的，SSE 结果的模块归属和结束时机也可控。子 Agent 仍按工作区
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
     * 执行匹配并返回 SSE 事件流。
     *
     * <p>读取招标要求在 {@link Schedulers#boundedElastic()} 上执行，避免阻塞 WebFlux
     * 事件线程；招标要求不存在时返回 404。</p>
     */
    public Flux<MatchEvent<?>> match(MatchRequest request) {
        return Mono.fromCallable(() -> loadRequirements(request.getTenderRequirementId()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(requirements -> run(request.getUserId(), requirements));
    }

    private TenderRequirements loadRequirements(Long tenderRequirementId) {
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

    private Flux<MatchEvent<?>> run(String userId, TenderRequirements requirements) {
        RuntimeContext ctx = contextBuilder.build(userId);
        HarnessAgent.Builder builder = builderProvider.getObject();
        if (!registerMatcherDeclarations(builder)) {
            return Flux.just(
                    MatchEvent.error("匹配子 Agent 未配置，请检查工作区 subagents/ 目录"),
                    MatchEvent.completed());
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
            return Flux.just(
                    MatchEvent.error("子 Agent 管理器未初始化"),
                    MatchEvent.completed());
        }

        List<Flux<MatchEvent<?>>> streams = new ArrayList<>();
        if (!CollectionUtils.isEmpty(requirements.getPersonnel())) {
            streams.add(runSubagent(manager, PERSONNEL_AGENT,
                    promptProvider.renderPersonnel(requirements, requirements.getPersonnel()),
                    ctx, MatchModule.PERSONNEL, PersonnelMatchResult.class));
        }
        if (!CollectionUtils.isEmpty(requirements.getQualifications())) {
            streams.add(runSubagent(manager, QUALIFICATION_AGENT,
                    promptProvider.renderQualification(requirements, requirements.getQualifications()),
                    ctx, MatchModule.QUALIFICATION, QualificationMatchResult.class));
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
     * 创建并订阅一个匹配子 Agent。
     *
     * <p>子 Agent 的名称来自工作区声明；每个子 Agent 使用独立 sessionId，避免两个
     * 并发调用在共享的 {@code AgentStateStore} 中互相覆盖。</p>
     */
    private <T> Flux<MatchEvent<?>> runSubagent(DefaultAgentManager manager,
                                                String agentId,
                                                String prompt,
                                                RuntimeContext parentCtx,
                                                MatchModule module,
                                                Class<T> type) {
        Agent agent;
        try {
            agent = manager.createAgent(agentId, parentCtx);
        } catch (Exception e) {
            log.error("创建 {} 子 Agent 失败", agentId, e);
            return Flux.just(MatchEvent.error(
                    "创建 " + agentId + " 子 Agent 失败：" + e.getMessage()));
        }
        if (!(agent instanceof HarnessAgent harness)) {
            return Flux.just(MatchEvent.error(agentId + " 不是可流式调用的 HarnessAgent"));
        }

        RuntimeContext childCtx = RuntimeContext.builder(parentCtx)
                .sessionId(parentCtx.getSessionId() + "-" + module.getCode())
                .build();

        return Flux.using(
                () -> harness,
                child -> eventTransformer.toSse(
                        child.streamEvents(new UserMessage(prompt), childCtx), module, type),
                this::closeQuietly
        );
    }

    private void closeQuietly(HarnessAgent agent) {
        try {
            agent.close();
        } catch (Exception e) {
            log.warn("关闭匹配子 Agent 失败", e);
        }
    }
}
