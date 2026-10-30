package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.model.catalog.dto.CatalogResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;

/**
 * 将 catalog-generation 提示词返回的文本解析为 {@link CatalogResult}。
 *
 * <p>模型输出可能带 Markdown 代码块围栏、前后解释性文字，这里统一从首个
 * {@code &#123;} 开始按字符串感知的大括号配对截取首个完整 JSON 对象再反序列化；
 * 文本为空、找不到 JSON 或 JSON 不合法时按 502（上游返回异常）抛出。</p>
 */
@Component
public class CatalogResultParser {

    private static final Logger log = LoggerFactory.getLogger(CatalogResultParser.class);

    private final ObjectMapper mapper;

    public CatalogResultParser(ObjectMapper mapper) {
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
        } catch (JsonProcessingException e) {
            log.error("投标目录 JSON 解析失败，原文：{}", json, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型返回的目录 JSON 无法解析", e);
        }
        normalize(result);
        return result;
    }

    /** 集合字段补空数组，避免下游判空；同时记录空目录告警。 */
    private void normalize(CatalogResult result) {
        if (result.getCatalog() == null) {
            result.setCatalog(new ArrayList<>());
        }
        if (result.getGaps() == null) {
            result.setGaps(new ArrayList<>());
        }
        if (result.getCatalog().isEmpty() && !result.isSplit() && result.getGaps().isEmpty()) {
            log.warn("投标目录生成结果为空，structureDecision={}", result.getStructureDecision());
        }
    }

    /**
     * 截取文本中第一个完整闭合的 JSON 对象（带字符串感知的大括号配对）。
     *
     * @return 完整 JSON 原文；文本为空或大括号不闭合时返回 {@code null}
     */
    private String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int start = raw.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
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
                case '"' -> inString = true;
                case '{' -> depth++;
                case '}' -> {
                    depth--;
                    if (depth == 0) {
                        return raw.substring(start, i + 1);
                    }
                }
                default -> {
                }
            }
        }
        return null;
    }
}
