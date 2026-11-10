package org.example.client;

import lombok.extern.slf4j.Slf4j;
import org.example.config.EsAuthHeaders;
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

/**
 * 使用 Elasticsearch 内置 inference endpoint 做文本向量化。
 *
 * <p>与官方 Java Client 共用 {@code knowledge-base.es} 的认证与节点配置：
 * 每个请求都会带上配置的 API Key / Basic 认证，并在多个节点间做故障转移。
 * 之前这里自建裸 {@link HttpClient} 且不带头，安全集群下会直接 401。</p>
 *
 * <p>连续失败后进入短暂冷却期（{@link #UNAVAILABLE_COOLDOWN}），期间 {@link #isEnabled()}
 * 返回 {@code false}，调用方自动降级为纯全文检索，避免每次写入都卡在超时上。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "knowledge-base.embedding.provider", havingValue = "es", matchIfMissing = true)
public class EsInferenceEmbeddingClient implements EmbeddingClient {

    private static final String INFERENCE_PATH_TEMPLATE = "/_inference/%s";

    private static final String DEFAULT_ES_URI = "http://127.0.0.1:9200";

    /** 失败后的降级时长：期间不再尝试向量化，查询/写入走纯全文检索 */
    private static final long UNAVAILABLE_COOLDOWN_MS = Duration.ofMinutes(1).toMillis();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final KnowledgeBaseProperties.Embedding config;

    /** 所有可用节点的 inference 地址，按配置顺序尝试 */
    private final List<URI> inferenceUris;

    /** 认证头，未配置认证时为 null */
    private final String authorization;

    /** 冷却截止时间（epoch ms），0 表示可用 */
    private volatile long unavailableUntil;

    public EsInferenceEmbeddingClient(KnowledgeBaseProperties properties) {
        this.config = properties.getEmbedding();
        String endpointId = config.getEsEndpointId();
        List<URI> uris = properties.getEs().getUris().stream()
                .filter(StringUtils::hasText)
                .map(base -> normalizeBaseUri(base) + INFERENCE_PATH_TEMPLATE.formatted(endpointId))
                .map(URI::create)
                .toList();
        this.inferenceUris = uris.isEmpty()
                ? List.of(URI.create(DEFAULT_ES_URI + INFERENCE_PATH_TEMPLATE.formatted(endpointId)))
                : uris;
        this.authorization = EsAuthHeaders.authorization(properties.getEs());
        log.info("知识库向量化使用 ES 内置模型: endpoint={}, dimension={}, nodes={}, auth={}",
                endpointId, config.getDimension(), this.inferenceUris.size(), authorization != null);
    }

    private static String normalizeBaseUri(String uri) {
        return uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    }

    /**
     * 是否可用。ES inference 失败进入冷却期后返回 {@code false}，
     * 业务侧据此降级为纯全文检索，而不是反复等待超时。
     */
    @Override
    public boolean isEnabled() {
        return System.currentTimeMillis() >= unavailableUntil;
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
        byte[] bodyBytes = buildBody(texts);

        HttpResponse<String> response = sendWithFailover(bodyBytes);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // 4xx 多为配置/模型问题（401/403 认证、404 endpoint 不存在），重试其他节点没有意义，
            // 但还是要进入冷却期，避免每次调用都重复打日志与等待。
            markUnavailable();
            throw new EmbeddingException("ES inference 返回异常状态 " + response.statusCode()
                    + "：" + abbreviate(response.body()));
        }
        unavailableUntil = 0;
        return parse(response.body());
    }

    private byte[] buildBody(List<String> texts) {
        ObjectNode body = MAPPER.createObjectNode();
        ArrayNode inputArray = body.putArray("input");
        texts.forEach(inputArray::add);
        try {
            return MAPPER.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new EmbeddingException("构造 ES inference 请求失败", e);
        }
    }

    /**
     * 依次尝试配置的节点：连接失败、429 或 5xx 时换下一个节点；
     * 其余状态码直接返回给调用方判断。
     */
    private HttpResponse<String> sendWithFailover(byte[] bodyBytes) {
        IOException lastError = null;
        for (URI uri : inferenceUris) {
            HttpRequest request = buildRequest(uri, bodyBytes);
            try {
                HttpResponse<String> response =
                        HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status == 429 || status >= 500) {
                    log.warn("ES inference 节点 {} 返回 {}，尝试下一个节点", uri, status);
                    continue;
                }
                return response;
            } catch (IOException e) {
                lastError = e;
                log.warn("ES inference 节点 {} 调用失败，尝试下一个节点: {}", uri, e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EmbeddingException("调用 ES inference 被中断", e);
            }
        }
        markUnavailable();
        throw new EmbeddingException("所有 ES inference 节点均不可用，已降级为纯全文检索", lastError);
    }

    private HttpRequest buildRequest(URI uri, byte[] bodyBytes) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return builder.build();
    }

    private void markUnavailable() {
        unavailableUntil = System.currentTimeMillis() + UNAVAILABLE_COOLDOWN_MS;
    }

    private List<List<Float>> parse(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode embeddingArray = root.get("text_embedding");
            if (embeddingArray == null || !embeddingArray.isArray() || embeddingArray.isEmpty()) {
                throw new EmbeddingException("ES inference 响应缺少 text_embedding：" + abbreviate(body));
            }
            List<List<Float>> result = new ArrayList<>(embeddingArray.size());
            for (JsonNode item : embeddingArray) {
                JsonNode emb = item.get("embedding");
                if (emb == null || !emb.isArray()) {
                    throw new EmbeddingException("ES inference 响应缺少 embedding 字段：" + abbreviate(body));
                }
                List<Float> vector = new ArrayList<>(emb.size());
                emb.forEach(n -> vector.add((float) n.asDouble()));
                result.add(vector);
            }
            return result;
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("解析 ES inference 响应失败：" + abbreviate(body), e);
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 500 ? text : text.substring(0, 500) + "...";
    }
}
