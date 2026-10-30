package org.example.service;

import io.agentscope.core.agent.RuntimeContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RuntimeContextBuilder {
    public RuntimeContext build(String userId) {
        return RuntimeContext.builder()
                .userId(userId)
                .sessionId("session-"+System.currentTimeMillis())
                .build();
    }
}
