package org.example.parsing.service;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import org.example.parsing.model.*;
import org.example.parsing.model.dto.ParseModule;
import org.example.parsing.model.dto.TenderParseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.example.parsing.model.dto.ParseModule.*;

/**
 * 将 Agent 事件流转换为结构化 SSE 事件流（{@link TenderParseEvent}）。
 *
 * <p>负责把 TextBlock 增量片段累积拼接、按大括号配对提取完整 JSON、按
 * {@code entityType} 判别分派为对应结构体，并以模块事件逐个推送；流结束时推送
 * {@code completed}，出错时记录日志并向客户端推送 {@code error} 事件。</p>
 */
@Component
public class TenderParseEventTransformer {

    private static final Logger log = LoggerFactory.getLogger(TenderParseEventTransformer.class);

    private record DispatchEntry(ParseModule module, Class<? extends TenderModel> type) {}

    private static final Map<String, DispatchEntry> DISPATCH_MAP = Map.of(
            "tenderRequirements", new DispatchEntry(PROJECT, TenderRequirements.class),
            "qualification", new DispatchEntry(QUALIFICATION, QualificationRequirement.class),
            "performance", new DispatchEntry(PERFORMANCE, PerformanceRequirement.class),
            "personnel", new DispatchEntry(PERSONNEL, PersonnelRequirement.class),
            "scoringItem", new DispatchEntry(SCORING_ITEM, ScoringItem.class)
    );

    /**
     * 反序列化容错：模型可能输出模型类里没有的字段（如招标概况中的保证金、开标时间等），
     * 必须忽略未知字段，否则整条事件会因 Unrecognized field 被丢弃。
     * 基于 Spring 管理的 {@link JsonMapper} 派生，保留全局 JSON 配置。
     */
    private final JsonMapper mapper;

    public TenderParseEventTransformer(JsonMapper mapper) {
        this.mapper = mapper.rebuild()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /**
     * 将 Agent 事件流转换为结构化 SSE 事件流。
     * <ul>
     *   <li>TEXT_BLOCK_DELTA：增量片段累积拼接，每凑齐一个完整 JSON 对象即反序列化并包装为结构体推送；</li>
     *   <li>AGENT_RESULT：不含数据，仅推送一条 completed 结束信号；</li>
     *   <li>错误：记录日志，并向客户端推送一条可读的 error 消息后结束流；</li>
     *   <li>其余事件：忽略。</li>
     * </ul>
     */
    public Flux<TenderParseEvent<?>> toSse(Flux<AgentEvent> events) {
        return Flux.defer(() -> {
            final StringBuilder buffer = new StringBuilder();

            return events
                    .concatMap(event -> {
                        if (event instanceof TextBlockDeltaEvent d) {
                            buffer.append(d.getDelta());
                            return extractRequirements(buffer, mapper);
                        } else if (event instanceof AgentResultEvent) {
                            log.info("解析招标文件完成，发送 completed 信号");
                            return Flux.just(TenderParseEvent.completed());
                        }
                        return Flux.empty();
                    })
                    .onErrorResume(error -> {
                        log.error("解析招标文件流式处理失败", error);
                        return Flux.just(TenderParseEvent.error("解析失败：" + error.getMessage()));
                    });
        });
    }

    private Flux<TenderParseEvent<?>> extractRequirements(StringBuilder buffer, JsonMapper mapper) {
        List<TenderParseEvent<?>> results = new ArrayList<>();
        String json;
        while ((json = takeFirstCompleteJson(buffer)) != null) {
            try {
                JsonNode node = mapper.readTree(json);
                Map.Entry<String, ? extends TenderModel> entry = dispatch(node, mapper);
                if (entry != null) {
                    ParseModule module = ParseModule.fromCode(entry.getKey());
                    if (module != null) {
                        log.info("解析出 {} 结构体，发送到客户端: {}", module, entry.getValue());
                        results.add(new TenderParseEvent<>(module, entry.getValue()));
                    }
                }
            } catch (Exception e) {
                log.warn("跳过无法解析的结构化片段: {}", json, e);
            }
        }
        return Flux.fromIterable(results);
    }

    private Map.Entry<String, ? extends TenderModel> dispatch(JsonNode node, JsonMapper mapper) {
        if (node == null) {
            return null;
        }
        DispatchEntry entry = DISPATCH_MAP.get(node.path("entityType").asString(""));
        if (entry == null) {
            return null;
        }
        return Map.entry(entry.module().getCode(), mapper.convertValue(node, entry.type()));
    }

    /**
     * 从缓冲前部取走第一个完整闭合的 JSON 对象原文（带字符串感知的大括号配对），取不到返回 {@code null}。
     */
    private String takeFirstCompleteJson(StringBuilder buffer) {
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < buffer.length(); i++) {
            char c = buffer.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"':
                    inString = true;
                    break;
                case '{':
                    if (depth == 0) {
                        start = i;
                    }
                    depth++;
                    break;
                case '}':
                    depth--;
                    if (depth == 0 && start >= 0) {
                        String json = buffer.substring(start, i + 1);
                        buffer.delete(start, i + 1);
                        return json;
                    }
                    break;
                default:
                    break;
            }
        }
        return null;
    }
}