package org.example.config;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.middleware.ModelCallAuditMiddleware;
import org.example.tools.EnterpriseDataTools;
import org.example.tools.TenderTools;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;


@Configuration
@RequiredArgsConstructor
@Slf4j
public class TenderAgentConfig {

    @Value("${agentscope.workspace-path}")
    private String workspacePath;

    @Value("${agentscope.model}")
    private String model;

    @Value("${agentscope.api-key}")
    private String apiKey;

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

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("TenderAssistant")
                .sysPrompt("你是一个投标服务助手。请严格遵循工作区中的行为约定和调度规则。")
                .model(DashScopeChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(model)
                        .build())
                .toolkit(toolkit)
                .stateStore(stateStore)
                .workspace(workspacePath)
                .middleware(modelCallAuditMiddleware)
                //.enableAgentTracingLog(false)
                //.disableShellTool()
                .disableWebTools()
                //.disableFilesystemTools()
        ;
        return builder;
    }
}
