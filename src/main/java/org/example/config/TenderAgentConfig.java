package org.example.config;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
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

    @Bean
    public AgentStateStore agentStateStore() {
        // 开发阶段先用内存存储；生产环境建议换成 RedisAgentStateStore
        return new InMemoryAgentStateStore();
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
