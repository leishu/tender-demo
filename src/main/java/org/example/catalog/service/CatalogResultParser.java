package org.example.catalog.service;

import org.example.catalog.model.dto.CatalogNode;
import org.example.catalog.model.dto.CatalogResult;
import org.example.util.JsonStreamExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 将 catalog-generation 提示词返回的文本解析为 {@link CatalogResult}。
 *
 * <p>模型输出可能带 Markdown 代码块围栏、前后解释性文字，这里统一交给
 * {@link JsonStreamExtractor.Reader} 按字符串感知的大括号配对截取首个完整 JSON
 * 对象（顺带修复正文中漏转义的裸引号）再反序列化；
 * 文本为空、找不到 JSON 或 JSON 不合法时按 502（上游返回异常）抛出。</p>
 */
@Component
public class CatalogResultParser {

    private static final Logger log = LoggerFactory.getLogger(CatalogResultParser.class);

    private final JsonMapper mapper;

    public CatalogResultParser(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 解析模型输出的投标目录 JSON。
     *
     * @param raw 模型返回的原始文本
     * @return 解析后的目录结果，集合字段保证非 null
     * @throws ResponseStatusException 文本为空、未找到 JSON 对象或 JSON 不合法时抛出（502）
     */
    public CatalogResult parse(String raw) {
        String json = extractJsonObject(raw);
        if (json == null) {
            log.error("投标目录生成结果中未找到 JSON 对象，原始文本：{}", raw);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型未返回可解析的目录 JSON");
        }
        CatalogResult result;
        try {
            result = mapper.readValue(json, CatalogResult.class);
        } catch (JacksonException e) {
            log.error("投标目录 JSON 解析失败，原文：{}", json, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型返回的目录 JSON 无法解析", e);
        }
        normalize(result);
        return result;
    }

    /** 集合字段补空数组、清理 null 占位的评分点 id，避免下游判空；同时记录空目录告警。 */
    private void normalize(CatalogResult result) {
        if (result.getCatalog() == null) {
            result.setCatalog(new ArrayList<>());
        }
        if (result.getGaps() == null) {
            result.setGaps(new ArrayList<>());
        }
        cleanScoringItemIds(result.getCatalog());
        cleanScoringItemIds(result.getBusinessCatalog());
        cleanScoringItemIds(result.getTechnicalCatalog());
        if (result.getCatalog().isEmpty() && !result.isSplit() && result.getGaps().isEmpty()) {
            log.warn("投标目录生成结果为空，structureDecision={}", result.getStructureDecision());
        }
    }

    /**
     * 模型在评分点缺少 itemId 时用 {@code [null]} 占位，这里统一清理为无评分点，
     * 避免保存前校验因空评分点 id 而拒绝真实模型输出。
     */
    private void cleanScoringItemIds(List<CatalogNode> nodes) {
        if (nodes == null) {
            return;
        }
        for (CatalogNode node : nodes) {
            if (node == null) {
                continue;
            }
            if (node.getScoringItemIds() == null) {
                node.setScoringItemIds(new ArrayList<>());
            } else {
                node.getScoringItemIds().removeIf(id -> id == null || id.isBlank());
            }
            cleanScoringItemIds(node.getChildren());
        }
    }

    /**
     * 截取文本中第一个完整闭合的 JSON 对象。
     *
     * <p>复用流式解析共用的 {@link JsonStreamExtractor.Reader}：字符串感知的大括号
     * 配对，并修复模型漏转义的正文裸引号，与解析/匹配 SSE 链路保持同一套提取行为，
     * 避免同一段模型输出在这边解析失败、在那边却能通过。</p>
     *
     * @return 完整 JSON 原文（裸引号已修复）；文本为空或大括号不闭合时返回 {@code null}
     */
    private String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        JsonStreamExtractor.Reader reader = new JsonStreamExtractor.Reader();
        reader.append(raw);
        return reader.poll();
    }
}
