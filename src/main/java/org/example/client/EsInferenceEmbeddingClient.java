package org.example.client;

import lombok.extern.slf4j.Slf4j;
import org.example.config.KnowledgeBaseProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(name = "knowledge-base.embedding.provider", havingValue = "es", matchIfMissing = true)
public class EsInferenceEmbeddingClient implements EmbeddingClient {

    private static final String INFERENCE_PATH_TEMPLATE = "/_inference/%s";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final KnowledgeBaseProperties.Embedding config;
    private final URI inferenceUri;

    public EsInferenceEmbeddingClient(KnowledgeBaseProperties properties) {
        this.config = properties.getEmbedding();
        String esUri = properties.getEs().getUris().stream()
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse("http://127.0.0.1:9200");
        if (esUri.endsWith("/")) {
            esUri = esUri.substring(0, esUri.length() - 1);
        }
        this.inferenceUri = URI.create(esUri + INFERENCE_PATH_TEMPLATE.formatted(config.getEsEndpointId()));
        log.info("知识库向量化使用 ES 内置模型: endpoint={}, dimension={}", config.getEsEndpointId(), config.getDimension());
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public List<Float> embedQuery(String text) {
        List<List<Float>> vectors = request(List.of(text));
        if (vectors.isEmpty()) {
            throw new EmbeddingException("ES inference 未返回查询向量");
        }
        return vectors.get(0);
    }

    @Override
    public List<List<Float>> embedDocuments(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        int batchSize = Math.max(1, config.getBatchSize());
        List<List<Float>> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            int to = Math.min(from + batchSize, texts.size());
            vectors.addAll(request(texts.subList(from, to)));
        }
        if (vectors.size() != texts.size()) {
            throw new EmbeddingException(
                    "ES inference 结果数量与文本数量不一致：期望 " + texts.size() + "，实际 " + vectors.size());
        }
        return vectors;
    }

    private List<List<Float>> request(List<String> texts) {
        ObjectNode body = MAPPER.createObjectNode();
        ArrayNode inputArray = body.putArray("input");
        texts.forEach(inputArray::add);

        byte[] bodyBytes;
        try {
            bodyBytes = MAPPER.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new EmbeddingException("构造 ES inference 请求失败", e);
        }

        HttpRequest request = HttpRequest.newBuilder(inferenceUri)
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                .build();

        HttpResponse<String> response;
        try {
            response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new EmbeddingException("调用 ES inference 失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("调用 ES inference 被中断", e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new EmbeddingException("ES inference 返回异常状态 " + response.statusCode()
                    + "：" + abbreviate(response.body()));
        }

        try {
            JsonNode root = MAPPER.readTree(response.body());
            JsonNode embeddingArray = root.get("text_embedding");
            if (embeddingArray == null || !embeddingArray.isArray() || embeddingArray.isEmpty()) {
                throw new EmbeddingException("ES inference 响应缺少 text_embedding：" + abbreviate(response.body()));
            }
            List<List<Float>> result = new ArrayList<>(embeddingArray.size());
            for (JsonNode item : embeddingArray) {
                JsonNode emb = item.get("embedding");
                if (emb == null || !emb.isArray()) {
                    throw new EmbeddingException("ES inference 响应缺少 embedding 字段：" + abbreviate(response.body()));
                }
                List<Float> vector = new ArrayList<>(emb.size());
                emb.forEach(n -> vector.add((float) n.asDouble()));
                result.add(vector);
            }
            return result;
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("解析 ES inference 响应失败：" + abbreviate(response.body()), e);
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 500 ? text : text.substring(0, 500) + "...";
    }
}