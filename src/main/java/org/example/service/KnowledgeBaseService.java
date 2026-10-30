package org.example.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.client.EmbeddingClient;
import org.example.client.EmbeddingException;
import org.example.config.KnowledgeBaseProperties;
import org.example.mapper.KnowledgeBaseMapper;
import org.example.model.knowledge.KnowledgeBase;
import org.example.model.knowledge.KnowledgeDocument;
import org.example.model.knowledge.KnowledgeSearchHit;
import org.example.repo.KnowledgeBaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    public KnowledgeBaseService(KnowledgeBaseRepository repository,
                                EmbeddingClient embeddingClient,
                                KnowledgeBaseMapper knowledgeBaseMapper,
                                KnowledgeBaseProperties properties) {
        this.repository = repository;
        this.embeddingClient = embeddingClient;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.properties = properties;
    }

    @Transactional
    public Long saveAll(String knowledgeBaseName, List<String> contents) {
        if (!StringUtils.hasText(knowledgeBaseName)) {
            throw new IllegalArgumentException("知识库名称不能为空");
        }
        if (contents == null || contents.isEmpty()) {
            KnowledgeBase kb = getOrCreateKnowledgeBase(knowledgeBaseName);
            return kb.getId();
        }

        List<String> texts = contents.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();

        KnowledgeBase kb = getOrCreateKnowledgeBase(knowledgeBaseName);
        Long knowledgeBaseId = kb.getId();

        if (texts.isEmpty()) {
            return knowledgeBaseId;
        }

        List<List<Float>> vectors = null;
        if (embeddingClient.isEnabled()) {
            vectors = embeddingClient.embedDocuments(texts);
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

        repository.saveAll(documents);

        long newCount = kb.getDocumentCount() == null ? 0 : kb.getDocumentCount() + documents.size();
        kb.setDocumentCount(newCount);
        kb.setUpdatedAt(now);
        knowledgeBaseMapper.updateById(kb);

        log.info("知识库 [{}]({}) 写入完成，新增 {} 条，累计 {} 条",
                kb.getName(), knowledgeBaseId, documents.size(), newCount);
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
        KnowledgeBase existing = knowledgeBaseMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getName, name)
                        .eq(KnowledgeBase::getDeleted, false)
                        .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }

        long now = System.currentTimeMillis();
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(name);
        kb.setDocumentCount(0L);
        kb.setEmbeddingEnabled(embeddingClient.isEnabled());
        kb.setEmbeddingDimension(properties.getEmbedding().getDimension());
        kb.setEmbeddingModel(properties.getEmbedding().getProvider());
        kb.setCreatedAt(now);
        kb.setUpdatedAt(now);
        kb.setDeleted(false);
        knowledgeBaseMapper.insert(kb);
        return kb;
    }
}