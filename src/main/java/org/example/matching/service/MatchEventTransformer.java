package org.example.matching.service;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import org.example.matching.model.dto.MatchEvent;
import org.example.matching.model.dto.MatchModule;
import org.example.util.JsonStreamExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 将匹配子 Agent 的事件流转换为结构化 SSE 事件流（{@link MatchEvent}）。
 *
 * <p>子 Agent 被要求每匹配完一条要求就输出一个平级 JSON 对象。这里累积
 * {@link TextBlockDeltaEvent} 的增量文本，凑齐一个完整 JSON 对象就立即反序列化并推送，
 * 因此前端能在子 Agent 仍在生成后续结果时就看到已完成的匹配项。</p>
 *
 * <p>两个子 Agent 各自持有一个转换器实例的缓冲区，互不干扰；调用方用
 * {@link Flux#merge(Iterable)} 把两路事件合并后，即可得到并发、逐条到达的 SSE 流。</p>
 */
@Component
public class MatchEventTransformer {

    private static final Logger log = LoggerFactory.getLogger(MatchEventTransformer.class);

    /**
     * 子 Agent 可能输出模型类里没有的字段（如补充说明），忽略未知字段，
     * 避免整条匹配结果因一个多余字段被丢弃。
     */
    private final JsonMapper mapper;

    public MatchEventTransformer(JsonMapper mapper) {
        this.mapper = mapper.rebuild()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /**
     * 把单路子 Agent 事件流转换为匹配事件流。
     *
     * @param events 子 Agent 的事件流
     * @param module 本路事件所属模块（personnel / qualification）
     * @param type   匹配结果类型
     */
    public <T> Flux<MatchEvent<?>> toSse(Flux<AgentEvent> events, MatchModule module, Class<T> type) {
        return Flux.defer(() -> {
            final StringBuilder buffer = new StringBuilder();
            return events
                    .concatMap(event -> {
                        if (event instanceof TextBlockDeltaEvent delta) {
                            buffer.append(delta.getDelta());
                            return extract(buffer, module, type);
                        }
                        return Flux.empty();
                    })
                    .onErrorResume(error -> {
                        log.error("{} 匹配子 Agent 流式处理失败", module.getCode(), error);
                        return Flux.just(MatchEvent.error(
                                moduleLabel(module) + "匹配失败：" + error.getMessage()));
                    });
        });
    }

    private <T> Flux<MatchEvent<?>> extract(StringBuilder buffer, MatchModule module, Class<T> type) {
        List<MatchEvent<?>> results = new ArrayList<>();
        String json;
        while ((json = JsonStreamExtractor.takeFirstCompleteJson(buffer)) != null) {
            try {
                JsonNode node = mapper.readTree(json);
                if (!isMatchResult(node, module)) {
                    continue;
                }
                T data = mapper.convertValue(node, type);
                log.info("匹配子 Agent 输出 {} 结果，发送到客户端: {}", module.getCode(), data);
                results.add(new MatchEvent<>(module, data));
            } catch (Exception e) {
                log.warn("跳过无法解析的匹配结果片段: {}", json, e);
            }
        }
        return Flux.fromIterable(results);
    }

    /**
     * 只接受本路的匹配结果对象。
     *
     * <p>{@code entityType} 正确时直接通过；模型漏写 {@code entityType} 时，
     * 以结果对象必备的 {@code requirementId} 作为兜底判别，避免把解释性 JSON 当成结果。
     */
    private boolean isMatchResult(JsonNode node, MatchModule module) {
        if (node == null || !node.isObject()) {
            return false;
        }
        String entityType = node.path("entityType").asString("");
        if (!entityType.isBlank()) {
            return module.getEntityType().equalsIgnoreCase(entityType);
        }
        return node.has("requirementId") && node.has("status");
    }

    private String moduleLabel(MatchModule module) {
        return switch (module) {
            case PERSONNEL -> "人员";
            case QUALIFICATION -> "企业资质";
            default -> module.getCode();
        };
    }
}
