package org.example.client;

import lombok.extern.slf4j.Slf4j;
import org.example.config.KnowledgeBaseProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 基于阿里云 DashScope text-embedding 的向量化实现。
 *
 * <p>文档入库时使用 {@code text_type=document}，查询时使用
 * {@code text_type=query}，以获得更好的非对称检索效果。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "knowledge-base.embedding.provider", havingValue = "dashscope")
public class DashScopeEmbeddingClient implements EmbeddingClient {

    /** DashScope text-embedding-v3 单次最多 10 条文本 */
    private static final int MAX_BATCH_SIZE = 10;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final KnowledgeBaseProperties.Embedding config;

    public DashScopeEmbeddingClient(KnowledgeBaseProperties properties) {
        this.config = properties.getEmbedding();
        if (!isEnabled()) {
            log.warn("未配置 knowledge-base.embedding.api-key，知识库将仅使用全文检索");
        }
    }

    @Override
    public boolean isEnabled() {
        return StringUtils.hasText(config.getApiKey());
    }

    @Override
    public List<Float> embedQuery(String text) {
        List<List<Float>> vectors = request(List.of(text), "query");
        if (vectors.isEmpty()) {
            throw new EmbeddingException("向量化服务未返回查询向量");
        }
        return vectors.get(0);
    }

    @Override
    public List<List<Float>> embedDocuments(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        int batchSize = Math.max(1, Math.min(config.getBatchSize(), MAX_BATCH_SIZE));
        List<List<Float>> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            int to = Math.min(from + batchSize, texts.size());
            vectors.addAll(request(texts.subList(from, to), "document"));
        }
        if (vectors.size() != texts.size()) {
            throw new EmbeddingException(
                    "向量化结果数量与文本数量不一致：期望 " + texts.size() + "，实际 " + vectors.size());
        }
        return vectors;
    }

    private List<List<Float>> request(List<String> texts, String textType) {
        if (!isEnabled()) {
            throw new EmbeddingException("未配置 knowledge-base.embedding.api-key");
        }

        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(new EmbeddingRequest(
                    config.getModel(),
                    new Input(texts),
                    new Parameters(config.getDimension(), textType)));
        } catch (Exception e) {
            throw new EmbeddingException("构造向量化请求失败", e);
        }

        HttpRequest request = HttpRequest.newBuilder(buildUri())
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response;
        try {
            response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new EmbeddingException("调用向量化服务失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("调用向量化服务被中断", e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new EmbeddingException("向量化服务返回异常状态 " + response.statusCode()
                    + "：" + abbreviate(response.body()));
        }

        EmbeddingResponse parsed;
        try {
            parsed = MAPPER.readValue(response.body(), EmbeddingResponse.class);
        } catch (Exception e) {
            throw new EmbeddingException("解析向量化响应失败：" + abbreviate(response.body()), e);
        }

        if (parsed == null || parsed.output() == null || parsed.output().embeddings() == null) {
            throw new EmbeddingException("向量化响应缺少 output.embeddings：" + abbreviate(response.body()));
        }

        return parsed.output().embeddings().stream()
                .sorted(Comparator.comparingInt(EmbeddingItem::text_index))
                .map(item -> item.embedding().stream().map(Double::floatValue).toList())
                .toList();
    }

    private URI buildUri() {
        String baseUrl = config.getBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String path = config.getPath();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return URI.create(baseUrl + path);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 500 ? text : text.substring(0, 500) + "...";
    }

    private record EmbeddingRequest(String model, Input input, Parameters parameters) {
    }

    private record Input(List<String> texts) {
    }

    private record Parameters(int dimension, String text_type) {
    }

    private record EmbeddingResponse(Output output) {
    }

    private record Output(List<EmbeddingItem> embeddings) {
    }

    private record EmbeddingItem(int text_index, List<Double> embedding) {
    }
}