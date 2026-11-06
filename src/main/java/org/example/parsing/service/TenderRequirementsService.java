package org.example.parsing.service;

import org.example.parsing.model.TenderRequirements;
import org.example.parsing.repo.TenderRequirementsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenderRequirementsService {

    private final TenderRequirementsRepository requirementRepository;

    public TenderRequirementsService(TenderRequirementsRepository requirementRepository) {
        this.requirementRepository = requirementRepository;
    }

    /**
     * 确认解析结果并入库存档，返回入库后的实体（含自增 id）。
     * 主表与四张子表在同一事务内写入，任一子表失败则整体回滚。
     */
    @Transactional
    public TenderRequirements save(TenderRequirements requirements) {
        return requirementRepository.save(requirements);
    }
}
