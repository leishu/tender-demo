package org.example.repo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.mapper.BidCatalogGapMapper;
import org.example.mapper.BidCatalogMapper;
import org.example.mapper.BidCatalogRunMapper;
import org.example.mapper.BidCatalogScoringItemMapper;
import org.example.mapper.ScoringItemMapper;
import org.example.model.catalog.BidCatalog;
import org.example.model.catalog.BidCatalogGap;
import org.example.model.catalog.BidCatalogRun;
import org.example.model.catalog.BidCatalogScoringItem;
import org.example.model.catalog.CatalogCategory;
import org.example.model.catalog.dto.CatalogGap;
import org.example.model.catalog.dto.CatalogNode;
import org.example.model.catalog.dto.CatalogResult;
import org.example.model.require.ScoringItem;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 投标目录落库访问层。
 *
 * <p>一个招标要求只保留一份目录：{@link #replace} 先清理该招标要求下的旧批次
 * （章节树、章节评分点关联、缺口），再写入新批次，因此重复保存等价于覆盖。</p>
 */
@Slf4j
@Repository
public class BidCatalogRepository {

    /** 目录最多三级，超过三级的子节点不再落库 */
    private static final int MAX_CHAPTER_LEVEL = 3;

    /** 标题缺失时的兜底名称，避免非空列写入失败 */
    private static final String FALLBACK_TITLE = "未命名章节";

    // ---------- 长度归一化：与建库脚本列定义对齐，超长字段截断，避免写入时报错 ----------

    private static final int CHAPTER_NO_MAX = 32;
    private static final int TITLE_MAX = 64;
    private static final int CONTENT_HINT_MAX = 1024;
    private static final int STRUCTURE_DECISION_MAX = 512;
    private static final int GAP_TYPE_MAX = 32;
    private static final int GAP_DETAIL_MAX = 512;
    private static final int ITEM_ID_MAX = 64;

    private final BidCatalogRunMapper runMapper;
    private final BidCatalogMapper catalogMapper;
    private final BidCatalogGapMapper gapMapper;
    private final BidCatalogScoringItemMapper scoringItemRelMapper;
    private final ScoringItemMapper scoringItemMapper;

    public BidCatalogRepository(BidCatalogRunMapper runMapper,
                                BidCatalogMapper catalogMapper,
                                BidCatalogGapMapper gapMapper,
                                BidCatalogScoringItemMapper scoringItemRelMapper,
                                ScoringItemMapper scoringItemMapper) {
        this.runMapper = runMapper;
        this.catalogMapper = catalogMapper;
        this.gapMapper = gapMapper;
        this.scoringItemRelMapper = scoringItemRelMapper;
        this.scoringItemMapper = scoringItemMapper;
    }

    /**
     * 覆盖保存目录：清空该招标要求已有目录后写入新批次。
     *
     * @return 新批次的 t_bid_catalog_run 主键
     */
    public Long replace(Long tenderRequirementId, Long enterpriseId, CatalogResult result) {
        deleteByTenderRequirementId(tenderRequirementId);

        long now = System.currentTimeMillis();
        BidCatalogRun run = new BidCatalogRun();
        run.setTenderRequirementId(tenderRequirementId);
        run.setEnterpriseId(enterpriseId);
        run.setSplitFlag(result.isSplit());
        run.setStructureDecision(truncate(result.getStructureDecision(), STRUCTURE_DECISION_MAX));
        run.setCreatedAt(now);
        runMapper.insert(run);
        Long runId = run.getId();

        Map<String, Long> itemIdIndex = loadScoringItemIndex(tenderRequirementId);
        if (result.isSplit()) {
            saveNodes(runId, CatalogCategory.BUSINESS, result.getBusinessCatalog(), now, itemIdIndex);
            saveNodes(runId, CatalogCategory.TECHNICAL, result.getTechnicalCatalog(), now, itemIdIndex);
        } else {
            saveNodes(runId, CatalogCategory.COMMON, result.getCatalog(), now, itemIdIndex);
        }
        saveGaps(runId, result.getGaps(), now);
        return runId;
    }

    /** 删除指定招标要求下的目录批次及其章节、评分点关联、缺口 */
    public void deleteByTenderRequirementId(Long tenderRequirementId) {
        List<BidCatalogRun> runs = runMapper.selectList(new LambdaQueryWrapper<BidCatalogRun>()
                .eq(BidCatalogRun::getTenderRequirementId, tenderRequirementId));
        if (runs.isEmpty()) {
            return;
        }
        List<Long> runIds = runs.stream().map(BidCatalogRun::getId).toList();

        List<BidCatalog> catalogs = catalogMapper.selectList(new LambdaQueryWrapper<BidCatalog>()
                .in(BidCatalog::getCatalogRunId, runIds));
        if (!catalogs.isEmpty()) {
            List<Long> catalogIds = catalogs.stream().map(BidCatalog::getId).toList();
            scoringItemRelMapper.delete(new LambdaQueryWrapper<BidCatalogScoringItem>()
                    .in(BidCatalogScoringItem::getCatalogId, catalogIds));
        }
        catalogMapper.delete(new LambdaQueryWrapper<BidCatalog>()
                .in(BidCatalog::getCatalogRunId, runIds));
        gapMapper.delete(new LambdaQueryWrapper<BidCatalogGap>()
                .in(BidCatalogGap::getCatalogRunId, runIds));
        runMapper.delete(new LambdaQueryWrapper<BidCatalogRun>()
                .in(BidCatalogRun::getId, runIds));
    }

    /** 写入一套目录（合并目录或商务/技术其中一套）的一级章节树 */
    private void saveNodes(Long runId, CatalogCategory category, List<CatalogNode> nodes,
                           long now, Map<String, Long> itemIdIndex) {
        if (CollectionUtils.isEmpty(nodes)) {
            return;
        }
        saveLevel(runId, category, null, null, 1, nodes, now, itemIdIndex);
    }

    /** 逐层写入章节，children 通过 parent_id 展开，最多三级 */
    private void saveLevel(Long runId, CatalogCategory category, Long parentId, String parentChapterNo, int level,
                           List<CatalogNode> nodes, long now, Map<String, Long> itemIdIndex) {
        int sortNo = 0;
        for (CatalogNode node : nodes) {
            if (node == null) {
                continue;
            }
            sortNo++;
            String chapterNo = resolveChapterNo(node.getChapterNo(), parentChapterNo, sortNo);

            BidCatalog catalog = new BidCatalog();
            catalog.setCatalogRunId(runId);
            catalog.setParentId(parentId);
            catalog.setChapterLevel(level);
            catalog.setCatalogCategory(category);
            catalog.setChapterNo(chapterNo);
            catalog.setTitle(StringUtils.hasText(node.getTitle())
                    ? truncate(node.getTitle(), TITLE_MAX) : FALLBACK_TITLE);
            catalog.setContentHint(truncate(node.getContentHint(), CONTENT_HINT_MAX));
            catalog.setSortNo(sortNo);
            catalog.setCreatedAt(now);
            catalogMapper.insert(catalog);

            saveScoringItems(catalog.getId(), node.getScoringItemIds(), itemIdIndex);

            if (level < MAX_CHAPTER_LEVEL) {
                saveLevel(runId, category, catalog.getId(), chapterNo, level + 1,
                        node.getChildren(), now, itemIdIndex);
            } else if (!CollectionUtils.isEmpty(node.getChildren())) {
                log.warn("目录节点超过三级，子节点未落库：catalogRunId={}, chapterNo={}", runId, chapterNo);
            }
        }
    }

    /** 写入章节承载的评分点关联，item_id 可匹配到评分点实体时回填主键 */
    private void saveScoringItems(Long catalogId, List<String> itemIds, Map<String, Long> itemIdIndex) {
        if (CollectionUtils.isEmpty(itemIds)) {
            return;
        }
        Set<String> saved = new HashSet<>();
        int sortNo = 0;
        for (String itemId : itemIds) {
            if (!StringUtils.hasText(itemId)) {
                continue;
            }
            String normalized = truncate(itemId, ITEM_ID_MAX);
            if (!saved.add(normalized)) {
                continue;
            }
            sortNo++;
            BidCatalogScoringItem relation = new BidCatalogScoringItem();
            relation.setCatalogId(catalogId);
            relation.setItemId(normalized);
            relation.setScoringItemId(itemIdIndex.get(normalized));
            relation.setSortNo(sortNo);
            scoringItemRelMapper.insert(relation);
        }
    }

    /** 写入缺口说明，gap_type 为空的行跳过（列非空） */
    private void saveGaps(Long runId, List<CatalogGap> gaps, long now) {
        if (CollectionUtils.isEmpty(gaps)) {
            return;
        }
        for (CatalogGap gap : gaps) {
            if (gap == null || !StringUtils.hasText(gap.getType())) {
                continue;
            }
            BidCatalogGap entity = new BidCatalogGap();
            entity.setCatalogRunId(runId);
            entity.setGapType(truncate(gap.getType(), GAP_TYPE_MAX));
            entity.setDetail(truncate(gap.getDetail(), GAP_DETAIL_MAX));
            entity.setCreatedAt(now);
            gapMapper.insert(entity);
        }
    }

    /** 按招标要求加载评分点索引：item_id -> t_scoring_item 主键 */
    private Map<String, Long> loadScoringItemIndex(Long tenderRequirementId) {
        List<ScoringItem> items = scoringItemMapper.selectList(new LambdaQueryWrapper<ScoringItem>()
                .eq(ScoringItem::getTenderRequirementId, tenderRequirementId));
        Map<String, Long> index = new HashMap<>();
        for (ScoringItem item : items) {
            if (StringUtils.hasText(item.getItemId())) {
                index.putIfAbsent(item.getItemId(), item.getId());
            }
        }
        return index;
    }

    /** 章节编号缺失时按层级与排序号补齐：1、1.1、1.1.1 */
    private static String resolveChapterNo(String chapterNo, String parentChapterNo, int sortNo) {
        if (StringUtils.hasText(chapterNo)) {
            return truncate(chapterNo, CHAPTER_NO_MAX);
        }
        String generated = parentChapterNo == null
                ? String.valueOf(sortNo)
                : parentChapterNo + "." + sortNo;
        return truncate(generated, CHAPTER_NO_MAX);
    }

    /** 按字符截断到目标长度：超长则保留前 max 个字符，避免插入时触发 "Data too long" 报错 */
    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
