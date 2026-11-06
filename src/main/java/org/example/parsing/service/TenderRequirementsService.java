package org.example.parsing.service;

import org.example.catalog.repo.BidCatalogRepository;
import org.example.parsing.model.TenderRequirements;
import org.example.parsing.model.dto.TenderRequirementSummary;
import org.example.parsing.repo.TenderRequirementsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
public class TenderRequirementsService {

    /** 列表默认返回条数 */
    private static final int DEFAULT_LIMIT = 50;
    /** 列表最大返回条数，避免一次拉取过多数据 */
    private static final int MAX_LIMIT = 200;

    private final TenderRequirementsRepository requirementRepository;
    private final BidCatalogRepository bidCatalogRepository;

    public TenderRequirementsService(TenderRequirementsRepository requirementRepository,
                                     BidCatalogRepository bidCatalogRepository) {
        this.requirementRepository = requirementRepository;
        this.bidCatalogRepository = bidCatalogRepository;
    }

    /**
     * 确认解析结果并入库存档，返回入库后的实体（含自增 id）。
     * 主表与四张子表在同一事务内写入，任一子表失败则整体回滚。
     */
    @Transactional
    public TenderRequirements save(TenderRequirements requirements) {
        return requirementRepository.save(requirements);
    }

    /**
     * 查询已解析招标文件列表（按录入时间倒序），并补充"是否已保存投标目录"状态。
     *
     * @param keyword 项目名称模糊匹配关键字，为空时返回全部
     * @param limit   返回条数上限，为空或非法时取默认值，超过上限时按上限截断
     */
    @Transactional(readOnly = true)
    public List<TenderRequirementSummary> list(String keyword, Integer limit) {
        int size = (limit == null || limit < 1) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        List<TenderRequirementSummary> summaries = requirementRepository.findSummaries(keyword, size);
        if (summaries.isEmpty()) {
            return summaries;
        }
        Set<Long> idsWithCatalog = bidCatalogRepository.findTenderRequirementIdsWithCatalog(
                summaries.stream().map(TenderRequirementSummary::getId).toList());
        for (TenderRequirementSummary summary : summaries) {
            summary.setHasCatalog(idsWithCatalog.contains(summary.getId()));
        }
        return summaries;
    }
}
