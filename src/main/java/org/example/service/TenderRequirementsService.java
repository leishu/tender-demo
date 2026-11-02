package org.example.service;

import org.example.model.require.TenderRequirements;
import org.example.repo.TenderRequirementsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenderRequirementsService {

    /** 前端暂未提供企业选择，未显式传入时统一归入该默认企业，保证主表与子表企业维度不为空 */
    private static final long DEFAULT_ENTERPRISE_ID = 1L;

    private final TenderRequirementsRepository requirementRepository;

    public TenderRequirementsService(TenderRequirementsRepository requirementRepository) {
        this.requirementRepository = requirementRepository;
    }

    /**
     * 确认解析结果并入库存档，返回入库后的实体（含自增 id）。
     * 未传入企业ID时回退到默认企业，避免主表及四张子表的 enterprise_id 落空。
     */
    @Transactional
    public TenderRequirements save(TenderRequirements requirements) {
        if (requirements.getEnterpriseId() == null) {
            requirements.setEnterpriseId(DEFAULT_ENTERPRISE_ID);
        }
        return requirementRepository.save(requirements);
    }
}