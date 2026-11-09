package org.example.matching.service;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import lombok.extern.slf4j.Slf4j;
import org.example.matching.model.MatchStatus;
import org.example.matching.model.PersonnelMatchCandidate;
import org.example.matching.model.PersonnelMatchResult;
import org.example.matching.model.QualificationMatchCandidate;
import org.example.matching.model.QualificationMatchResult;
import org.example.matching.model.dto.MatchEvent;
import org.example.matching.model.dto.MatchModule;
import org.example.util.JsonStreamExtractor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 将匹配子 Agent 的事件流转换为结构化 SSE 事件流（{@link MatchEvent}）。
 *
 * <p>子 Agent 被要求一次性输出多个平级 JSON 对象（每行一个）。这里累积
 * {@link TextBlockDeltaEvent} 的增量文本，凑齐一个完整 JSON 对象就立即反序列化并推送，
 * 因此前端能在后续对象仍在生成时就看到已完成的匹配项。流结束后还会校验
 * requirementId 是否齐全，缺失时追加 error 事件，避免静默漏条。</p>
 *
 * <p>推送前还会过滤 {@code eligible=false} 的候选（完整名单保留在 trace 供审计），
 * 前端只接收合格候选，并把 matchedCount 对齐为过滤后的候选数。</p>
 *
 * <p>人工复核标记不依赖模型自觉：强制资格 UNMATCHED、以及人员结果存在
 * {@code unverifiedRequirements}（工具无数据的强制项）时，应用层强制置
 * {@code requiresManualReview=true}，{@code status} 本身不因此降级。</p>
 *
 * <p>两个子 Agent 各自持有一个转换器实例的缓冲区，互不干扰；调用方用
 * {@link Flux#merge(Iterable)} 把两路事件合并后，即可得到并发、逐条到达的 SSE 流。</p>
 */
@Slf4j
@Component
public class MatchEventTransformer {

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
        return Flux.defer(() -> toSseTracked(events, module, type, new HashSet<>()));
    }

    /**
     * 把单路子 Agent 事件流转换为匹配事件流，并在流结束后校验结果完整性。
     *
     * <p>用于不需要自动重试的场景：缺条时直接追加 error 事件。</p>
     *
     * @param expectedRequirementIds 本次应返回的 requirementId 集合；为空表示不校验
     */
    public <T> Flux<MatchEvent<?>> toSse(Flux<AgentEvent> events,
                                         MatchModule module,
                                         Class<T> type,
                                         Set<Long> expectedRequirementIds) {
        return Flux.defer(() -> {
            Set<Long> seen = new HashSet<>();
            return toSseTracked(events, module, type, seen)
                    .concatWith(Flux.defer(() -> missingResultsEvent(module, seen, expectedRequirementIds)));
        });
    }

    /**
     * 把单路子 Agent 事件流转换为匹配事件流，并把已返回的 requirementId 记录到
     * {@code seen}，供上层编排决定是否需要补跑缺失项。
     *
     * <p>流异常时只记录日志并结束本路，不立即向前端推送 error 事件；上层重试后
     * 仍缺条时再统一报错，避免“重试成功但界面仍显示失败”。</p>
     */
    public <T> Flux<MatchEvent<?>> toSseTracked(Flux<AgentEvent> events,
                                                MatchModule module,
                                                Class<T> type,
                                                Set<Long> seen) {
        return Flux.defer(() -> {
            final StringBuilder buffer = new StringBuilder();
            return events
                    .concatMap(event -> {
                        if (event instanceof TextBlockDeltaEvent delta) {
                            buffer.append(delta.getDelta());
                            return extract(buffer, module, type, seen);
                        }
                        return Flux.empty();
                    })
                    .onErrorResume(error -> {
                        log.error("{} 匹配子 Agent 流式处理失败，交由上层重试", module.getCode(), error);
                        return Flux.empty();
                    });
        });
    }

    private <T> Flux<MatchEvent<?>> extract(StringBuilder buffer,
                                            MatchModule module,
                                            Class<T> type,
                                            Set<Long> seen) {
        List<MatchEvent<?>> results = new ArrayList<>();
        String json;
        while ((json = JsonStreamExtractor.takeFirstCompleteJson(buffer)) != null) {
            try {
                JsonNode node = mapper.readTree(json);
                if (!isMatchResult(node, module)) {
                    continue;
                }
                T data = mapper.convertValue(node, type);
                enforceMandatoryManualReview(data);
                enforceUnverifiedManualReview(data);
                filterIneligibleCandidates(data, module);
                trackRequirementId(node, module, seen);
                log.info("匹配子 Agent 输出 {} 结果，发送到客户端: {}", module.getCode(), data);
                results.add(new MatchEvent<>(module, data));
            } catch (Exception e) {
                log.warn("跳过无法解析的匹配结果片段: {}", json, e);
            }
        }
        return Flux.fromIterable(results);
    }

    /**
     * 应用层兜底：强制资格 UNMATCHED 时强制打上人工复核标记。
     *
     * <p>不能只依赖提示词让模型输出该字段；漏写时会导致 UI 无法对废标风险告警。</p>
     */
    private void enforceMandatoryManualReview(Object data) {
        if (!(data instanceof QualificationMatchResult result)) {
            return;
        }
        if (result.getStatus() != MatchStatus.UNMATCHED) {
            return;
        }
        if (!isMandatoryQualification(result.getRequiredType())) {
            return;
        }
        result.setRequiresManualReview(true);
        result.setRisk("mandatory_qualification_missing");
    }

    /**
     * 应用层兜底：人员结果存在无法核验的强制项时，强制打上人工复核标记。
     *
     * <p>社保记录、是否担任其他在建工程项目经理等项工具没有返回数据，模型可能只字不提
     * 就判 MATCHED。只要清单里有实质内容就必须复核；{@code status} 不降级，仍表达
     * “已核验项满足”，不确定性由该标记单独承载。模型漏写标记时在这里补上并记日志，
     * 便于在生产日志中核实兜底是否真的触发。</p>
     */
    private void enforceUnverifiedManualReview(Object data) {
        if (!(data instanceof PersonnelMatchResult result)) {
            return;
        }
        List<String> unverified = result.getUnverifiedRequirements();
        if (unverified == null || unverified.stream().noneMatch(StringUtils::hasText)) {
            return;
        }
        boolean alreadyFlagged = Boolean.TRUE.equals(result.getRequiresManualReview());
        result.setRequiresManualReview(true);
        if (!alreadyFlagged) {
            log.info("personnel requirementId={} 存在无法核验的强制项但模型未标记复核，应用层已强制标记: {}",
                    result.getRequirementId(), unverified);
        }
    }

    /**
     * 推送前端前过滤不合格候选（eligible=false 或缺失 eligible 字段）。
     *
     * <p>提示词允许模型保留不合格候选以说明排除原因，完整名单保留在 Langfuse trace
     * 供审计；前端只应看到合格候选。应用层做确定性过滤，不依赖模型自觉。
     * 过滤后把 matchedCount 对齐为实际候选数，避免前端计数与列表不一致。</p>
     */
    private void filterIneligibleCandidates(Object data, MatchModule module) {
        if (data instanceof PersonnelMatchResult result && result.getCandidates() != null) {
            int before = result.getCandidates().size();
            List<PersonnelMatchCandidate> kept = result.getCandidates().stream()
                    .filter(c -> Boolean.TRUE.equals(c.getEligible()))
                    .toList();
            result.setCandidates(kept);
            result.setMatchedCount(kept.size());
            logFiltered(module, result.getRequirementId(), before, kept.size());
        } else if (data instanceof QualificationMatchResult result && result.getCandidates() != null) {
            int before = result.getCandidates().size();
            List<QualificationMatchCandidate> kept = result.getCandidates().stream()
                    .filter(c -> Boolean.TRUE.equals(c.getEligible()))
                    .toList();
            result.setCandidates(kept);
            result.setMatchedCount(kept.size());
            logFiltered(module, result.getRequirementId(), before, kept.size());
        }
    }

    private void logFiltered(MatchModule module, Long requirementId, int before, int after) {
        if (before > after) {
            log.info("{} requirementId={} 已过滤 {} 名不合格候选，前端只接收合格候选（完整名单见 trace）",
                    module.getCode(), requirementId, before - after);
        }
    }

    private boolean isMandatoryQualification(String requiredType) {
        if (requiredType == null || requiredType.isBlank()) {
            return false;
        }
        String type = requiredType.trim();
        return type.contains("施工总承包")
                || type.contains("安全生产许可证")
                || type.toUpperCase().contains("ISO9001")
                || type.toUpperCase().contains("ISO14001")
                || type.toUpperCase().contains("ISO45001");
    }

    private void trackRequirementId(JsonNode node, MatchModule module, Set<Long> seen) {
        if (!node.hasNonNull("requirementId")) {
            log.warn("{} 匹配结果缺少 requirementId，无法做完整性校验: {}", module.getCode(), node);
            return;
        }
        long requirementId = node.get("requirementId").asLong();
        if (!seen.add(requirementId)) {
            log.warn("{} 匹配结果出现重复 requirementId: {}", module.getCode(), requirementId);
        }
    }

    /**
     * 对比期望与实际返回的 requirementId，缺少结果时生成 error 事件。
     *
     * <p>自动重试全部结束后仍缺条时，由编排层调用本方法把缺口暴露给前端，
     * 避免界面显示“已完成”却少一条结果。</p>
     */
    public Flux<MatchEvent<?>> missingResultsEvent(MatchModule module,
                                                   Set<Long> seen,
                                                   Set<Long> expected) {
        if (expected.isEmpty()) {
            return Flux.empty();
        }
        Set<Long> missing = new LinkedHashSet<>(expected);
        missing.removeAll(seen);
        if (missing.isEmpty()) {
            return Flux.empty();
        }
        log.error("{} 匹配结果不完整，缺少 requirementId: {}", module.getCode(), missing);
        return Flux.just(MatchEvent.error(
                moduleLabel(module) + "匹配缺少 " + missing.size() + " 条结果，requirementId=" + missing));
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
