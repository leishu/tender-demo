package org.example.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.client.EmbeddingClient;
import org.example.client.EmbeddingException;
import org.example.config.KnowledgeBaseProperties;
import org.example.knowledge.mapper.KnowledgeBaseMapper;
import org.example.knowledge.model.KnowledgeBase;
import org.example.knowledge.model.KnowledgeDocument;
import org.example.knowledge.model.KnowledgeSearchHit;
import org.example.knowledge.repo.KnowledgeBaseRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class KnowledgeBaseService {

    private static final int DEFAULT_TOP_K = 10;

    private static final int MAX_TOP_K = 50;

    private final KnowledgeBaseRepository repository;

    private final EmbeddingClient embeddingClient;

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    private final KnowledgeBaseProperties properties;

    /** 用编程式事务把“元数据落库/计数累加”与 ES 写入明确隔开 */
    private final TransactionTemplate transactionTemplate;

    public KnowledgeBaseService(KnowledgeBaseRepository repository,
                                EmbeddingClient embeddingClient,
                                KnowledgeBaseMapper knowledgeBaseMapper,
                                KnowledgeBaseProperties properties,
                                PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.embeddingClient = embeddingClient;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 写入知识库文档。
     *
     * <p>顺序刻意拆成三步，保证事务边界正确：</p>
     * <ol>
     *   <li>短事务内创建/读取知识库元数据（MySQL）；</li>
     *   <li>事务外做向量化并写入 Elasticsearch；ES 不是事务资源，
     *       若包在数据库事务里，DB 回滚时 ES 文档已经写进去，无法撤销；</li>
     *   <li>写入成功后再用单条 SQL 原子累加文档数。</li>
     * </ol>
     *
     * <p>向量化失败时降级为纯全文写入（不写 embedding），而不是让整次写入失败。</p>
     */
    public Long saveAll(String knowledgeBaseName, List<String> contents) {
        if (!StringUtils.hasText(knowledgeBaseName)) {
            throw new IllegalArgumentException("知识库名称不能为空");
        }

        List<String> texts = contents == null ? List.of() : contents.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();

        // 1) 元数据先落库：即使后面的 ES 写入失败，也只会留下一个空知识库，不会留下孤儿文档
        Long knowledgeBaseId = transactionTemplate.execute(
                status -> getOrCreateKnowledgeBase(knowledgeBaseName).getId());
        if (texts.isEmpty()) {
            return knowledgeBaseId;
        }

        // 2) 向量化是外部调用，必须在事务外完成
        List<List<Float>> vectors = null;
        if (embeddingClient.isEnabled()) {
            try {
                vectors = embeddingClient.embedDocuments(texts);
            } catch (EmbeddingException e) {
                log.warn("知识库 [{}] 向量化失败，本次降级为纯全文索引", knowledgeBaseName, e);
            }
        }

        long now = System.currentTimeMillis();
        List<KnowledgeDocument> documents = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            KnowledgeDocument document = new KnowledgeDocument();
            document.setId(UUID.randomUUID().toString());
            document.setKnowledgeBaseId(knowledgeBaseId);
            document.setContent(texts.get(i));
            document.setCreatedAt(now);
            if (vectors != null) {
                document.setEmbedding(vectors.get(i));
            }
            documents.add(document);
        }

        // 3) 写 ES（事务外）。失败时仓储层会按本次 id 清理已写入的部分，再抛出异常
        repository.saveAll(documents);

        // 4) 写入成功后再原子累加计数；失败只影响统计值，可由对账任务修复，不会产生幽灵文档
        transactionTemplate.executeWithoutResult(status ->
                knowledgeBaseMapper.incrementDocumentCount(knowledgeBaseId, documents.size(), now));

        log.info("知识库 [{}]({}) 写入完成，新增 {} 条", knowledgeBaseName, knowledgeBaseId, documents.size());
        return knowledgeBaseId;
    }

    public List<KnowledgeSearchHit> search(List<String> knowledgeBaseNames, String query, int topK) {
        if (!StringUtils.hasText(query)) {
            throw new IllegalArgumentException("查询内容不能为空");
        }

        List<Long> knowledgeBaseIds = null;
        if (knowledgeBaseNames != null && !knowledgeBaseNames.isEmpty()) {
            LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<KnowledgeBase>()
                    .in(KnowledgeBase::getName, knowledgeBaseNames)
                    .eq(KnowledgeBase::getDeleted, false);
            List<KnowledgeBase> kbs = knowledgeBaseMapper.selectList(wrapper);
            if (kbs.isEmpty()) {
                throw new IllegalArgumentException("知识库不存在: " + knowledgeBaseNames);
            }
            List<String> foundNames = kbs.stream().map(KnowledgeBase::getName).toList();
            List<String> missing = knowledgeBaseNames.stream()
                    .filter(n -> !foundNames.contains(n))
                    .toList();
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("知识库不存在: " + missing);
            }
            knowledgeBaseIds = kbs.stream().map(KnowledgeBase::getId).toList();
        }

        int limit = topK <= 0 ? DEFAULT_TOP_K : Math.min(topK, MAX_TOP_K);

        List<Float> queryVector = null;
        if (embeddingClient.isEnabled()) {
            try {
                queryVector = embeddingClient.embedQuery(query.trim());
            } catch (EmbeddingException e) {
                log.warn("查询向量生成失败，降级为纯全文检索", e);
            }
        }

        return repository.search(knowledgeBaseIds, query.trim(), queryVector, limit);
    }

    private KnowledgeBase getOrCreateKnowledgeBase(String name) {
        KnowledgeBase existing = findKnowledgeBase(name);
        if (existing != null) {
            return existing;
        }

        long now = System.currentTimeMillis();
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(name);
        kb.setDocumentCount(0L);
        // 这里记录“是否配置了向量化”，而不是“当前是否可用”：
        // embeddingClient.isEnabled() 在故障冷却期内会返回 false，不应把知识库永久标记为未启用
        kb.setEmbeddingEnabled(StringUtils.hasText(properties.getEmbedding().getProvider()));
        kb.setEmbeddingDimension(properties.getEmbedding().getDimension());
        kb.setEmbeddingModel(properties.getEmbedding().getProvider());
        kb.setCreatedAt(now);
        kb.setUpdatedAt(now);
        kb.setDeleted(false);
        try {
            knowledgeBaseMapper.insert(kb);
            return kb;
        } catch (DuplicateKeyException e) {
            // 并发首次写入时另一个请求已经创建了同名知识库，回查即可
            KnowledgeBase concurrent = findKnowledgeBase(name);
            if (concurrent != null) {
                return concurrent;
            }
            throw e;
        }
    }

    private KnowledgeBase findKnowledgeBase(String name) {
        return knowledgeBaseMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getName, name)
                        .eq(KnowledgeBase::getDeleted, false)
                        .last("LIMIT 1"));
    }
}