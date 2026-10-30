package org.example.repo;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import lombok.extern.slf4j.Slf4j;
import org.example.config.KnowledgeBaseProperties;
import org.example.model.knowledge.KnowledgeDocument;
import org.example.model.knowledge.KnowledgeSearchHit;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 知识库 Elasticsearch 访问层。
 *
 * <p>索引同时保存原始文本（{@code text}，中文按 unigram + bigram 分词）和
 * 文本向量（{@code dense_vector}）。查询时分别执行 BM25 全文检索与 kNN
 * 向量检索，再在应用侧用 RRF（Reciprocal Rank Fusion）融合排序，返回统一结果。
 * 这样不依赖 ES 商业版的 RRF 能力，Basic License 也可使用。</p>
 */
@Slf4j
@Repository
public class KnowledgeBaseRepository {

    /** 批量写入时每个 bulk 请求的最大文档数 */
    private static final int BULK_BATCH_SIZE = 500;

    /** 每一路召回的候选数量下限（最终还会按 topK 截断） */
    private static final int MIN_CANDIDATE_SIZE = 50;

    /** RRF 公式中的平滑常数，经验值 60 */
    private static final int RRF_RANK_CONSTANT = 60;

    private final ElasticsearchClient client;
    private final KnowledgeBaseProperties properties;

    private final AtomicBoolean indexReady = new AtomicBoolean(false);

    public KnowledgeBaseRepository(ElasticsearchClient client, KnowledgeBaseProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * 批量写入文档。
     */
    public void saveAll(List<KnowledgeDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        ensureIndex();

        for (int from = 0; from < documents.size(); from += BULK_BATCH_SIZE) {
            int to = Math.min(from + BULK_BATCH_SIZE, documents.size());
            bulkIndex(documents.subList(from, to));
        }
    }

    /**
     * 混合检索：全文（BM25）+ 向量（kNN），使用 RRF 融合排序。
     *
     * @param knowledgeBaseIds 知识库 ID 列表，为空时不按知识库过滤（跨所有库检索）
     * @param queryText        查询文本，不能为空
     * @param queryVector      查询向量，为空时退化为纯全文检索
     * @param topK             返回条数
     */
    public List<KnowledgeSearchHit> search(List<Long> knowledgeBaseIds,
                                           String queryText,
                                           List<Float> queryVector,
                                           int topK) {
        ensureIndex();

        int candidateSize = Math.max(MIN_CANDIDATE_SIZE, topK);
        List<KnowledgeSearchHit> textHits = fullTextSearch(knowledgeBaseIds, queryText, candidateSize);
        List<KnowledgeSearchHit> vectorHits = queryVector == null || queryVector.isEmpty()
                ? List.of()
                : vectorSearch(knowledgeBaseIds, queryVector, candidateSize);

        return fuse(textHits, vectorHits, topK);
    }

    private List<KnowledgeSearchHit> fullTextSearch(List<Long> knowledgeBaseIds, String queryText, int size) {
        SearchRequest.Builder builder = new SearchRequest.Builder()
                .index(properties.getIndexName())
                .size(size)
                .source(s -> s.filter(f -> f.excludes("embedding")));

        if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
            List<FieldValue> fieldValues = knowledgeBaseIds.stream().map(FieldValue::of).toList();
            builder.query(q -> q.bool(b -> b
                    .filter(f -> f.terms(t -> t.field("knowledgeBaseId").terms(v -> v.value(fieldValues))))
                    .must(m -> m.match(mm -> mm.field("content").query(queryText)))));
        } else {
            builder.query(q -> q.match(m -> m.field("content").query(queryText)));
        }
        return execute(builder.build());
    }

    private List<KnowledgeSearchHit> vectorSearch(List<Long> knowledgeBaseIds, List<Float> queryVector, int size) {
        SearchRequest.Builder builder = new SearchRequest.Builder()
                .index(properties.getIndexName())
                .size(size)
                .source(s -> s.filter(f -> f.excludes("embedding")));

        if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
            List<FieldValue> fieldValues = knowledgeBaseIds.stream().map(FieldValue::of).toList();
            builder.knn(k -> k
                    .field("embedding")
                    .queryVector(queryVector)
                    .k(size)
                    .numCandidates(Math.max(size * 2, 100))
                    .filter(f -> f.terms(t -> t.field("knowledgeBaseId").terms(v -> v.value(fieldValues)))));
        } else {
            builder.knn(k -> k
                    .field("embedding")
                    .queryVector(queryVector)
                    .k(size)
                    .numCandidates(Math.max(size * 2, 100)));
        }
        return execute(builder.build());
    }

    private List<KnowledgeSearchHit> execute(SearchRequest request) {
        try {
            SearchResponse<KnowledgeDocument> response =
                    client.search(request, KnowledgeDocument.class);

            List<KnowledgeSearchHit> hits = new ArrayList<>(response.hits().hits().size());
            for (var hit : response.hits().hits()) {
                KnowledgeDocument document = hit.source();
                if (document == null) {
                    continue;
                }
                hits.add(new KnowledgeSearchHit(
                        hit.id(), document.getContent(), document.getCreatedAt(), hit.score()));
            }
            return hits;
        } catch (IOException e) {
            throw new IllegalStateException("查询 Elasticsearch 失败", e);
        }
    }

    /**
     * RRF 融合：score = Σ 1 / (k + rank)，rank 从 1 开始。
     * 同一个文档在两路结果中都出现时分数累加，因此同时被全文和向量命中的文档排名更靠前。
     */
    private List<KnowledgeSearchHit> fuse(List<KnowledgeSearchHit> textHits,
                                          List<KnowledgeSearchHit> vectorHits,
                                          int topK) {
        Map<String, KnowledgeSearchHit> fused = new LinkedHashMap<>();
        accumulate(textHits, fused);
        accumulate(vectorHits, fused);

        return fused.values().stream()
                .sorted(Comparator.comparingDouble(KnowledgeSearchHit::getScore).reversed()
                        .thenComparing(KnowledgeSearchHit::getCreatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(topK)
                .toList();
    }

    private void accumulate(List<KnowledgeSearchHit> hits, Map<String, KnowledgeSearchHit> fused) {
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeSearchHit hit = hits.get(i);
            double score = 1.0 / (RRF_RANK_CONSTANT + i + 1);

            KnowledgeSearchHit existing = fused.get(hit.getId());
            if (existing == null) {
                hit.setScore(score);
                fused.put(hit.getId(), hit);
            } else {
                existing.setScore(existing.getScore() + score);
            }
        }
    }

    private void bulkIndex(List<KnowledgeDocument> documents) {
        BulkRequest.Builder request = new BulkRequest.Builder();
        for (KnowledgeDocument document : documents) {
            request.operations(op -> op.index(idx -> idx
                    .index(properties.getIndexName())
                    .id(document.getId())
                    .document(document)));
        }

        BulkResponse response;
        try {
            // 写入后立即刷新，保证“保存完马上查询”能命中新数据
            response = client.bulk(request.refresh(Refresh.WaitFor).build());
        } catch (IOException e) {
            throw new IllegalStateException("写入 Elasticsearch 失败", e);
        }

        if (response.errors()) {
            String message = response.items().stream()
                    .filter(item -> item.error() != null)
                    .map(this::describeFailure)
                    .collect(Collectors.joining("; "));
            throw new IllegalStateException("部分文档写入 Elasticsearch 失败: " + message);
        }
    }

    private String describeFailure(BulkResponseItem item) {
        return item.id() + ": " + item.error().reason();
    }

    /**
     * 首次访问时确保索引存在。索引 mapping 中的向量维度来自配置，
     * 修改维度后需要删除旧索引重建。
     */
    private void ensureIndex() {
        if (indexReady.get()) {
            return;
        }
        synchronized (indexReady) {
            if (indexReady.get()) {
                return;
            }
            String index = properties.getIndexName();
            try {
                boolean exists = client.indices().exists(e -> e.index(index)).value();
                if (!exists) {
                    createIndex(index);
                    log.info("已创建知识库索引: {}", index);
                }
                indexReady.set(true);
            } catch (IOException e) {
                throw new IllegalStateException("访问 Elasticsearch 索引失败: " + index, e);
            }
        }
    }

    private void createIndex(String index) throws IOException {
        int dimension = properties.getEmbedding().getDimension();
        try {
            client.indices().create(c -> c
                    .index(index)
                    .settings(s -> s
                            .numberOfShards("1")
                            .numberOfReplicas("0"))
                    .mappings(m -> m
                            .properties("id", p -> p.keyword(k -> k))
                            .properties("knowledgeBaseId", p -> p.long_(l -> l))
                            .properties("content", p -> p.text(t -> t
                                    .analyzer("ik_max_word")
                                    .searchAnalyzer("ik_smart")))
                            .properties("createdAt", p -> p.date(d -> d.format("epoch_millis")))
                            .properties("embedding", p -> p.denseVector(d -> d
                                    .dims(dimension)
                                    .index(true)
                                    .similarity(DenseVectorSimilarity.Cosine)))));
        } catch (ElasticsearchException e) {
            if (e.getMessage() != null && e.getMessage().contains("resource_already_exists_exception")) {
                return;
            }
            throw e;
        }
    }
}