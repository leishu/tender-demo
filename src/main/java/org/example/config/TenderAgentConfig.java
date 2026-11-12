package org.example.config;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.enterprise.tool.EnterpriseDataTools;
import org.example.middleware.LangfuseSpanEnrichmentMiddleware;
import org.example.middleware.ModelCallAuditMiddleware;
import org.example.parsing.tool.TenderTools;
import org.example.state.MysqlAgentStateStore;
import org.example.state.mapper.AgentStateMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;


@Configuration
@RequiredArgsConstructor
@Slf4j
@EnableConfigurationProperties(LangfuseProperties.class)
public class TenderAgentConfig {

    @Value("${agentscope.workspace-path}")
    private String workspacePath;

    @Value("${agentscope.model}")
    private String model;

    @Value("${agentscope.api-key}")
    private String apiKey;

    /**
     * 可选的思考 token 上限。人员匹配曾出现 2.2 万 reasoning token 只产出 57 个
     * output token、单轮推理 140s 的情况；配置后可截断过长的思考。
     * 留空表示不限制（保持模型默认行为）。
     */
    @Value("${agentscope.thinking-budget:#{null}}")
    private Integer thinkingBudget;

    /** Langfuse 启用时才会存在 OTel SDK Bean */
    private final ObjectProvider<OpenTelemetrySdk> openTelemetrySdkProvider;

    /** Langfuse 启用时才会存在的内容补全中间件 */
    private final ObjectProvider<LangfuseSpanEnrichmentMiddleware> langfuseMiddlewareProvider;

    /**
     * 智能体状态存储：MySQL 持久化（表 t_agent_state，见 src/main/db/agent_state.sql）。
     *
     * <p>换成 MySQL 后：进程重启不再丢会话记忆；多个实例共享同一份 agent_state，
     * 由 t_agent_state.version 的乐观锁保证并发写入不互相覆盖。</p>
     *
     * <p>过期清理按天执行，默认保留 90 天，配置项见 {@code agentscope.state-store.cleanup.*}；
     * cron 由 {@code @Scheduled} 上的占位符解析，因此不在此处传入。</p>
     */
    @Bean
    public AgentStateStore agentStateStore(
            AgentStateMapper agentStateMapper,
            @Value("${agentscope.state-store.cleanup.enabled:true}") boolean cleanupEnabled,
            @Value("${agentscope.state-store.cleanup.retain-days:90}") int cleanupRetainDays,
            @Value("${agentscope.state-store.cleanup.batch-size:1000}") int cleanupBatchSize) {
        log.info("AgentStateStore 使用 MySQL 持久化：table=t_agent_state, 过期清理={}, 保留天数={}, 批大小={}",
                cleanupEnabled ? "开启" : "关闭", cleanupRetainDays, cleanupBatchSize);
        return new MysqlAgentStateStore(agentStateMapper, cleanupEnabled, cleanupRetainDays, cleanupBatchSize);
    }

    /**
     * 招标助手智能体配置。
     *
     * PR #2542，标题就是“修复 ReActAgent.Builder.build() 会修改（可能共享的）Builder 的 hooks/middlewares”。
     * PR 描述里写得很清楚：共享 Builder 每次 build() 都会往非线程安全的集合里泄漏新的 hook/middleware 实例，
     * 修复方式是改成基于每次构建的副本装配。
     */
    @Bean
    @Scope("prototype")
    public HarnessAgent.Builder tenderAgent(
            TenderTools tenderTools,
            EnterpriseDataTools enterpriseDataTools,
            AgentStateStore stateStore,
            ModelCallAuditMiddleware modelCallAuditMiddleware) {

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tenderTools);
        toolkit.registerTool(enterpriseDataTools);

        DashScopeChatModel.Builder modelBuilder = DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(model);
        if (thinkingBudget != null && thinkingBudget > 0) {
            modelBuilder.defaultOptions(GenerateOptions.builder()
                    .thinkingBudget(thinkingBudget)
                    .build());
        }

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("TenderAssistant")
                .sysPrompt("你是一个投标服务助手。请严格遵循工作区中的行为约定和调度规则。")
                .model(modelBuilder.build())
                .toolkit(toolkit)
                .stateStore(stateStore)
                .workspace(workspacePath)
                .middleware(modelCallAuditMiddleware)
                //.enableAgentTracingLog(false)
                //.disableShellTool()
                .disableWebTools()
                .disableMemoryTools()
                .disableMemoryHooks()
                //.disableFilesystemTools()
        ;

        // 配置了 Langfuse 时才注册 OTel 追踪，避免未启用时引入 Reactor 全局 Hook
        if (openTelemetrySdkProvider.getIfAvailable() != null) {
            builder.middleware(new OtelTracingMiddleware());
            LangfuseSpanEnrichmentMiddleware langfuseMiddleware =
                    langfuseMiddlewareProvider.getIfAvailable();
            if (langfuseMiddleware != null) {
                builder.middleware(langfuseMiddleware);
            }
        }

        return builder;
    }
}
