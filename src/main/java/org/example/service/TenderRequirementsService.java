package org.example.service;

import org.example.model.require.TenderRequirements;
import org.example.repo.TenderRequirementsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenderRequirementsService {

    private final TenderRequirementsRepository requirementRepository;

    public TenderRequirementsService(TenderRequirementsRepository requirementRepository) {
        this.requirementRepository = requirementRepository;
    }

    /**
     * 确认解析结果并入库存档，返回入库后的主键 id。
     */
    @Transactional
    public Long save(TenderRequirements requirements) {
        TenderRequirements saved = requirementRepository.save(requirements);
        return saved.getId();
    }
}